package com.github.kookaburra.isolation;

import com.github.kookaburra.Config;
import com.github.kookaburra.ConflictException;
import com.github.kookaburra.Isolation;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.TxOptions;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.model.Account;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * READ COMMITTED, SNAPSHOT and SERIALIZABLE side by side on the classic anomalies: non-repeatable reads, phantoms,
 * lost updates and write skew; SERIALIZABLE read validation by OID, by query condition (indexed, unindexed,
 * residual, OQL, aggregates), phantoms, precision (unrelated changes do not abort), read-only transactions,
 * retries and eviction ({@link IsolationDiskTest} runs the same cases on disk).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class IsolationTest {

    protected ObjectDB db;

    protected ObjectDB create() {
        return TestDbs.create(config());
    }

    protected Config config() {
        return Config.defaults().withTransactions(true);
    }

    /** Disk variant: flush, compact, close and reopen. */
    protected void checkpoint() {
    }

    @BeforeEach
    void open() {
        db = create();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private long balance(Transaction tx, long oid) {
        return tx.get(Account.class, oid).getBalance();
    }

    private void commitOutside(long oid, long balance) {
        Account a = db.get(Account.class, oid);
        db.update(a.balance(balance));
    }

    @Test
    void nonRepeatableReadsAndPhantoms() {
        long ann = db.insert(new Account("ann", 100));
        checkpoint();
        try (Transaction rc = db.begin(Isolation.READ_COMMITTED);
             Transaction si = db.begin(Isolation.SNAPSHOT);
             Transaction sr = db.begin(Isolation.SERIALIZABLE)) {
            assertThat(rc.isolation()).isEqualTo(Isolation.READ_COMMITTED);
            for (Transaction tx : List.of(rc, si, sr)) {
                assertThat(balance(tx, ann)).isEqualTo(100);
                assertThat(tx.from(Account.class).where(Account::getOwner).eq("bob").count()).isZero();
            }
            commitOutside(ann, 150);
            db.insert(new Account("bob", 1));
            assertThat(balance(rc, ann)).isEqualTo(150);                      // READ COMMITTED: the latest commit
            assertThat(rc.from(Account.class).where(Account::getOwner).eq("bob").count()).isEqualTo(1);   // phantom
            assertThat(rc.oql("SELECT COUNT(*) FROM Account a").list()).containsExactly(2L);
            assertThat(rc.dynamic("Account").get(ann).get("balance")).isEqualTo(150L);
            for (Transaction tx : List.of(si, sr)) {                           // snapshot: stable reads
                assertThat(balance(tx, ann)).isEqualTo(100);
                assertThat(tx.from(Account.class).where(Account::getOwner).eq("bob").count()).isZero();
                assertThat(tx.getAll(Account.class, List.of(ann)).get(ann).getBalance()).isEqualTo(100);
            }
            assertThat(rc.getAll(Account.class, List.of(ann)).get(ann).getBalance()).isEqualTo(150);
            assertThat(rc.get(ann)).isNotNull();
            assertThat(rc.referrers(ann)).isEmpty();
            assertThat(rc.referrers(Account.class, ann)).isEmpty();
            assertThat(rc.snapshotSeq()).isLessThan(db.snapshot().seq());
        }
    }

    @Test
    void lostUpdatesAreRejectedAtEveryLevel() {
        for (Isolation level : Isolation.values()) {
            long oid = db.insert(new Account("x-" + level, 10));
            try (Transaction tx = db.begin(level)) {
                Account a = tx.get(Account.class, oid);
                commitOutside(oid, 20);                                         // someone else wins
                if (level == Isolation.READ_COMMITTED) {
                    // the write reads the latest version: the stale entity fails at once
                    assertThatThrownBy(() -> tx.update(a.balance(a.getBalance() + 5))).isInstanceOf(OptimisticLockException.class);
                } else {
                    tx.update(a.balance(a.getBalance() + 5));
                    assertThatThrownBy(tx::commit).as(level.name()).isInstanceOf(ConflictException.class);
                }
            }
            assertThat(db.get(Account.class, oid).getBalance()).isEqualTo(20);
        }
        checkpoint();
        // READ COMMITTED: a write based on a read after the other commit goes through (SNAPSHOT would fail)
        long oid = db.insert(new Account("late", 10));
        try (Transaction rc = db.begin(Isolation.READ_COMMITTED); Transaction si = db.begin(Isolation.SNAPSHOT)) {
            rc.from(Account.class).count();
            si.from(Account.class).count();
            commitOutside(oid, 20);
            Account fresh = rc.get(Account.class, oid);
            rc.update(fresh.balance(fresh.getBalance() + 5));
            rc.commit();
            Account stale = si.get(Account.class, oid);                       // still version 1 in the snapshot
            si.update(stale.balance(1));
            assertThatThrownBy(si::commit).isInstanceOf(ConflictException.class);
        }
        assertThat(db.get(Account.class, oid).getBalance()).isEqualTo(25);
        // READ COMMITTED with a stale entity: the version check fails at once
        try (Transaction rc = db.begin(Isolation.READ_COMMITTED)) {
            Account old = rc.get(Account.class, oid);
            commitOutside(oid, 30);
            assertThatThrownBy(() -> rc.update(old.balance(1))).isInstanceOf(OptimisticLockException.class);
        }
    }

    /** Two on-call doctors: each checks that someone else is on call, then goes off call. */
    private boolean writeSkew(Isolation level) {
        db.from(Account.class).deleteAll();
        long alice = db.insert(new Account("oncall", 1).note("alice"));
        long bob = db.insert(new Account("oncall", 1).note("bob"));
        try (Transaction t1 = db.begin(level); Transaction t2 = db.begin(level)) {
            assertThat(t1.from(Account.class).where(Account::getOwner).eq("oncall").count()).isEqualTo(2);
            assertThat(t2.from(Account.class).where(Account::getOwner).eq("oncall").count()).isEqualTo(2);
            t1.update(t1.get(Account.class, alice).owner("off"));
            t2.update(t2.get(Account.class, bob).owner("off"));
            t1.commit();
            try {
                t2.commit();
                return true;
            } catch (ConflictException e) {
                assertThat(e.getMessage()).contains("serialization failure");
                return false;
            }
        }
    }

    @Test
    void writeSkewOnlyUnderSerializable() {
        checkpoint();
        assertThat(writeSkew(Isolation.READ_COMMITTED)).isTrue();
        assertThat(writeSkew(Isolation.SNAPSHOT)).isTrue();                     // both went off call
        assertThat(db.from(Account.class).where(Account::getOwner).eq("oncall").count()).isZero();
        assertThat(writeSkew(Isolation.SERIALIZABLE)).isFalse();
        assertThat(db.from(Account.class).where(Account::getOwner).eq("oncall").count()).isEqualTo(1);
    }

    @Test
    void serializableValidatesWhatWasRead() {
        long ann = db.insert(new Account("ann", 100).priority(1).note("n"));
        long bob = db.insert(new Account("bob", 50).priority(2));
        checkpoint();
        // by OID: read ann, write bob; ann changes
        try (Transaction tx = db.begin(Isolation.SERIALIZABLE)) {
            long b = balance(tx, ann);
            tx.update(tx.get(Account.class, bob).balance(b));
            commitOutside(ann, 1);
            assertThatThrownBy(tx::commit).isInstanceOf(ConflictException.class).hasMessageContaining("object " + ann);
        }
        // phantom: a sum over a condition, then a matching insert
        try (Transaction tx = db.begin(Isolation.SERIALIZABLE)) {
            long total = tx.from(Account.class).where(Account::getPriority).ge(1).sum(com.github.kookaburra.query.Q.where(Account::getBalance));
            tx.insert(new Account("total", total));
            db.insert(new Account("cid", 7).priority(5));
            assertThatThrownBy(tx::commit).isInstanceOf(ConflictException.class).hasMessageContaining("WHERE");
        }
        // a change that no longer matches counts too (the before version matched)
        try (Transaction tx = db.begin(Isolation.SERIALIZABLE)) {
            tx.from(Account.class).where(Account::getOwner).eq("bob").list();
            tx.insert(new Account("audit", 0));
            Account b = db.get(Account.class, bob);
            db.update(b.owner("robert"));
            assertThatThrownBy(tx::commit).isInstanceOf(ConflictException.class);
        }
        // precision: changes outside the conditions do not abort
        try (Transaction tx = db.begin(Isolation.SERIALIZABLE)) {
            tx.from(Account.class).where(Account::getOwner).eq("ann").list();
            tx.oql("SELECT a FROM Account a WHERE a.balance > 1000 AND a.note IS NULL").list();      // unindexed
            tx.from(Account.class).filter(a -> a.getNote() != null && a.getNote().startsWith("zz")).list();   // residual
            tx.insert(new Account("audit2", 0));
            db.insert(new Account("dee", 5));
            commitOutside(bob, 51);
            tx.commit();
        }
        // unindexed and residual conditions do catch matching changes
        try (Transaction tx = db.begin(Isolation.SERIALIZABLE)) {
            tx.oql("SELECT COUNT(*) FROM Account a WHERE UPPER(a.note) = 'ZZ'").list();
            tx.insert(new Account("audit3", 0));
            db.insert(new Account("eve", 1).note("zz"));
            assertThatThrownBy(tx::commit).isInstanceOf(ConflictException.class);
        }
        try (Transaction tx = db.begin(Isolation.SERIALIZABLE)) {
            tx.from(Account.class).filter(a -> a.getVisits() > 10).count();
            tx.insert(new Account("audit4", 0));
            db.insert(new Account("fay", 1).visits(11));
            assertThatThrownBy(tx::commit).isInstanceOf(ConflictException.class);
        }
        // dynamic access and OQL group-by read the collection
        try (Transaction tx = db.begin(Isolation.SERIALIZABLE)) {
            tx.oql("SELECT a.owner, SUM(a.balance) FROM Account a GROUP BY a.owner").list();
            DynamicObject a = tx.dynamic("Account").get(ann);
            a.set("note", "seen");
            tx.dynamic("Account").update(a);
            db.insert(new Account("gus", 3));
            assertThatThrownBy(tx::commit).isInstanceOf(ConflictException.class);
        }
        // read-only SERIALIZABLE transactions never fail: they are serialized at their snapshot
        try (Transaction tx = db.begin(Isolation.SERIALIZABLE)) {
            balance(tx, ann);
            commitOutside(ann, 2);
            tx.commit();
        }
    }

    @Test
    void retriesAndDefaults() {
        long ann = db.insert(new Account("ann", 100));
        AtomicInteger attempts = new AtomicInteger();
        long result = db.inTransactionResult(TxOptions.defaults().isolation(Isolation.SERIALIZABLE), tx -> {
            long b = balance(tx, ann);
            if (attempts.incrementAndGet() == 1) {
                commitOutside(ann, 200);                                     // invalidates the first attempt's read
            }
            tx.insert(new Account("copy", b));
            return b;
        });
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(result).isEqualTo(200);
        db.inTransaction(TxOptions.defaults().isolation(Isolation.READ_COMMITTED), tx -> tx.insert(new Account("rc", 1)));
        checkpoint();
        assertThat(db.from(Account.class).where(Account::getOwner).in("copy", "rc").count()).isEqualTo(2);
        try (Transaction tx = db.begin()) {
            assertThat(tx.isolation()).isEqualTo(Isolation.SNAPSHOT);
        }
        try (ObjectDB serial = TestDbs.create(config().withTxIsolation(Isolation.SERIALIZABLE))) {
            try (Transaction tx = serial.begin()) {
                assertThat(tx.isolation()).isEqualTo(Isolation.SERIALIZABLE);
            }
            serial.inTransaction(tx -> assertThat(tx.isolation()).isEqualTo(Isolation.SERIALIZABLE));
        }
        assertThat(Config.fromSettings(java.util.Map.of(Config.TX_ISOLATION, "read committed")).txIsolation())
                .isEqualTo(Isolation.READ_COMMITTED);
        assertThatThrownBy(() -> Config.fromSettings(java.util.Map.of(Config.TX_ISOLATION, "chaos")))
                .hasMessageContaining("expected READ_COMMITTED, SNAPSHOT or SERIALIZABLE");
    }

    @Test
    void serializableEvictionWhenTheChangeLogOverflows() {
        try (ObjectDB tiny = TestDbs.create(config().withTxConflictTableMax(8))) {
            long a = tiny.insert(new Account("a", 1));
            try (Transaction tx = tiny.begin(Isolation.SERIALIZABLE)) {
                tx.get(Account.class, a);
                for (int i = 0; i < 20; i++) {
                    tiny.insert(new Account("n" + i, i));
                }
                tx.insert(new Account("late", 0));
                assertThatThrownBy(tx::commit).isInstanceOf(ConflictException.class).hasMessageContaining("evicted");
            }
            try (Transaction tx = tiny.begin(Isolation.SERIALIZABLE)) {     // a fresh one works again
                tx.get(Account.class, a);
                tx.insert(new Account("ok", 0));
                tx.commit();
            }
        }
    }
}
