package com.github.kookaburra.engine;

import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Config;
import com.github.kookaburra.ConflictException;
import com.github.kookaburra.Isolation;
import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.TransactionTimeoutException;
import com.github.kookaburra.TxOptions;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.model.Account;
import com.github.kookaburra.model.Dog;
import com.github.kookaburra.model.Stats;
import com.github.kookaburra.query.Q;
import com.github.kookaburra.query.Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transactions (docs/transaction-design.md): lifecycle, snapshot isolation, first-committer-wins, stale entities,
 * read-your-writes in every read API, statement-level atomicity, increments, limits and timeouts.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class TransactionTest {

    protected ObjectDB db;

    /** The database under test: in memory here, on disk in {@link TransactionDiskTest}. */
    protected ObjectDB create() {
        return TestDbs.create(Config.defaults().withTransactions(true));
    }

    @BeforeEach
    void open() {
        db = create();
    }

    @AfterEach
    void close() {
        db.close();
    }

    private long account(String owner, long balance) {
        return db.insert(new Account(owner, balance));
    }

    // ---------------------------------------------------------------- switch, lifecycle

    @Test
    void offByDefaultEveryWriteCommitsDirectly() {
        try (ObjectDB plain = TestDbs.create()) {
            assertThatThrownBy(plain::begin).isInstanceOf(KookaburraException.class)
                    .hasMessageContaining("kookaburra.transactions=true");
            assertThatThrownBy(() -> plain.inTransaction(tx -> { })).hasMessageContaining("not enabled");
            long oid = plain.insert(new Account("ann", 10));
            plain.increment(Account.class, oid, "balance", 5);              // atomic ops work without transactions
            assertThat(plain.get(Account.class, oid).getBalance()).isEqualTo(15);
            assertThat(plain.activeTransactions()).isZero();
        }
    }

    @Test
    void commitRollbackAndStates() {
        long ann = account("ann", 100);
        Transaction tx = db.begin(Isolation.SNAPSHOT);
        assertThat(tx.isolation()).isEqualTo(Isolation.SNAPSHOT);
        assertThat(tx.isReadOnly()).isFalse();
        assertThat(tx.state()).isEqualTo(Transaction.State.ACTIVE);
        assertThat(tx.snapshotSeq()).isPositive();
        assertThat(tx.id()).isPositive();
        assertThat(tx.toString()).contains("SNAPSHOT", "ACTIVE");
        assertThat(db.activeTransactions()).isEqualTo(1);
        Account bob = new Account("bob", 5);
        assertThat(tx.pendingWrites()).isZero();
        long bobOid = tx.insert(bob);
        assertThat(tx.pendingWrites()).isEqualTo(1);
        assertThat(bob.getOid()).isEqualTo(bobOid);                     // OID assigned at insert
        assertThat(db.get(Account.class, bobOid)).isNull();              // not visible outside before commit
        Account a = tx.get(Account.class, ann);
        tx.update(a.balance(90));
        assertThat(db.get(Account.class, ann).getBalance()).isEqualTo(100);
        tx.commit();
        assertThat(tx.state()).isEqualTo(Transaction.State.COMMITTED);
        assertThat(db.activeTransactions()).isZero();
        assertThat(db.get(Account.class, bobOid).getBalance()).isEqualTo(5);
        assertThat(db.get(Account.class, ann).getBalance()).isEqualTo(90);
        assertThat(a.getVersion()).isEqualTo(2);                          // version written back at commit
        assertThatThrownBy(() -> tx.get(Account.class, ann)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("committed");
        assertThatThrownBy(tx::commit).isInstanceOf(IllegalStateException.class);
        tx.rollback();                                                     // no-op once ended
        tx.close();

        try (Transaction t2 = db.begin()) {
            t2.insert(new Account("carl", 1));
            t2.rollback();
            assertThat(t2.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        }
        try (Transaction t3 = db.begin(TxOptions.defaults())) {
            t3.insert(new Account("dora", 1));
        }                                                                  // close without commit = rollback
        assertThat(db.query(Account.class).where(Account::getOwner).in("carl", "dora").count()).isZero();
        try (Transaction empty = db.begin()) {
            empty.commit();                                                // nothing to write: no seq used
            assertThat(empty.state()).isEqualTo(Transaction.State.COMMITTED);
        }
    }

    // ---------------------------------------------------------------- isolation

    @Test
    void snapshotIsolationGuarantees() {
        long ann = account("ann", 100);
        try (Transaction tx = db.begin()) {
            assertThat(tx.get(Account.class, ann).getBalance()).isEqualTo(100);
            Account outside = db.get(Account.class, ann);
            db.update(outside.balance(1));                                 // committed after the snapshot
            db.insert(new Account("zed", 7));
            assertThat(tx.get(Account.class, ann).getBalance()).isEqualTo(100);           // no non-repeatable read
            assertThat(tx.query(Account.class).where(Account::getOwner).eq("zed").count()).isZero();   // no phantom
            assertThat(tx.oql("SELECT COUNT(*) FROM Account a").list()).containsExactly(1L);
        }
    }

    @Test
    void firstCommitterWins() {
        long ann = account("ann", 100);
        Transaction t1 = db.begin();
        Transaction t2 = db.begin();
        t1.update(t1.get(Account.class, ann).balance(50));
        t2.update(t2.get(Account.class, ann).balance(70));
        t1.commit();
        assertThatThrownBy(t2::commit).isInstanceOf(ConflictException.class)
                .hasMessageContaining("changed by a concurrent commit")
                .satisfies(e -> assertThat(((ConflictException) e).oid()).isEqualTo(ann));
        assertThat(t2.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        assertThat(db.get(Account.class, ann).getBalance()).isEqualTo(50);

        // autocommit, WriteBatch and OQL bulk writes are seen by open transactions too
        for (int path = 0; path < 4; path++) {
            Transaction tx = db.begin();
            Account mine = tx.get(Account.class, ann);
            switch (path) {
                case 0 -> db.update(db.get(Account.class, ann).note("auto"));
                case 1 -> {
                    try (WriteBatch b = db.batch()) {
                        b.update(db.get(Account.class, ann).note("batch"));
                        b.commit();
                    }
                }
                case 2 -> db.oql("UPDATE Account a SET a.note = 'oql' WHERE a.owner = 'ann'").execute();
                default -> db.delete(Account.class, ann);
            }
            tx.update(mine.balance(1));
            assertThatThrownBy(tx::commit).as("path %d", path).isInstanceOf(ConflictException.class);
        }
        assertThat(db.get(Account.class, ann)).isNull();
    }

    @Test
    void staleEntityIsRejectedAtTheCall() {
        long ann = account("ann", 100);
        Account loadedEarly = db.get(Account.class, ann);
        db.update(db.get(Account.class, ann).balance(1));                 // before the transaction starts
        try (Transaction tx = db.begin()) {
            assertThatThrownBy(() -> tx.update(loadedEarly.balance(500))).isInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining("has version 2 in this transaction, the entity has version 1");
            assertThatThrownBy(() -> tx.delete(loadedEarly)).isInstanceOf(OptimisticLockException.class);
            // the failed calls had no effect; the transaction stays usable
            assertThat(tx.state()).isEqualTo(Transaction.State.ACTIVE);
            Account fresh = tx.get(Account.class, ann);
            tx.update(fresh.balance(2));
            tx.update(fresh.balance(3));                                   // same entity again: version unchanged in tx
            tx.commit();
        }
        assertThat(db.get(Account.class, ann).getBalance()).isEqualTo(3);
        assertThat(db.get(Account.class, ann).getVersion()).isEqualTo(3);  // +1 once per transaction
    }

    @Test
    void writeSkewIsTheKnownLimitOfSnapshotIsolation() {
        long a = account("doctor-a", 1);
        long b = account("doctor-b", 1);
        Transaction t1 = db.begin();
        Transaction t2 = db.begin();
        // both see two doctors on call and each takes a different one off call
        assertThat(t1.query(Account.class).where(Account::getBalance).eq(1L).count()).isEqualTo(2);
        assertThat(t2.query(Account.class).where(Account::getBalance).eq(1L).count()).isEqualTo(2);
        t1.update(t1.get(Account.class, a).balance(0));
        t2.update(t2.get(Account.class, b).balance(0));
        t1.commit();
        t2.commit();                                                       // allowed under SNAPSHOT (design §5)
        assertThat(db.query(Account.class).where(Account::getBalance).eq(1L).count()).isZero();
    }

    // ---------------------------------------------------------------- read-your-writes

    @Test
    void readYourOwnWritesInEveryReadApi() {
        long ann = account("ann", 100);
        long bob = account("bob", 200);
        long cid = account("cid", 300);
        long rex = db.insert(new Dog("Rex", 3, "Lab"));
        try (Transaction tx = db.begin()) {
            long dee = tx.insert(new Account("dee", 400).priority(5));
            tx.update(tx.get(Account.class, ann).balance(150).priority(9));
            tx.delete(Account.class, bob);
            Account c = tx.get(Account.class, cid);
            tx.delete(c);

            assertThat(tx.get(Account.class, dee).getOwner()).isEqualTo("dee");
            assertThat(tx.get(Account.class, bob)).isNull();
            assertThat(tx.get(cid)).isNull();
            assertThat(tx.get(ann)).isInstanceOf(Account.class);
            assertThat(tx.getAll(Account.class, List.of(ann, bob, dee))).containsOnlyKeys(ann, dee);
            assertThat(tx.query(Account.class).orderBy(Account::getBalance).desc().list())
                    .extracting(Account::getOwner).containsExactly("dee", "ann");
            assertThat(tx.from(Account.class).filter(where(Account::getBalance).gt(120L)).count()).isEqualTo(2);
            assertThat(tx.query(Account.class).where(Account::getOwner).eq("ann").list())
                    .singleElement().extracting(Account::getBalance).isEqualTo(150L);
            assertThat(tx.query(Account.class).where(Account::getOwner).eq("bob").exists()).isFalse();
            assertThat(tx.query(Account.class).orderBy(Account::getPriority).desc().limit(1).list())   // ordinal index walk
                    .extracting(Account::getOwner).containsExactly("ann");
            assertThat(tx.from(Account.class).sum(where(Account::getBalance))).isEqualTo(550);
            List<Row> groups = tx.from(Account.class).groupBy(where(Account::getOwner)).agg(Q.count().as("n")).toList();
            assertThat(groups).extracting(r -> r.get("owner")).containsExactlyInAnyOrder("ann", "dee");
            assertThat(tx.oql("SELECT a.owner FROM Account a ORDER BY a.balance").list()).containsExactly("ann", "dee");
            assertThat(tx.oql("SELECT a FROM Account a WHERE a.owner = 'dee'", Account.class).list()).hasSize(1);
            assertThat(tx.oql("EXPLAIN SELECT a FROM Account a WHERE a.owner = 'ann'").list().toString())
                    .contains("masked");
            DynamicCollection accounts = tx.dynamic("Account");
            assertThat(accounts.from().count()).isEqualTo(2);
            assertThat(accounts.get(dee).get("owner")).isEqualTo("dee");
            assertThat(accounts.get(bob)).isNull();
            assertThat(accounts.get(rex)).isNull();                         // another class
            assertThat(accounts.from().where("owner").eq("ann").list()).singleElement()
                    .extracting(d -> d.get("balance")).isEqualTo(150L);
            assertThat(tx.query(Dog.class).count()).isEqualTo(1);           // untouched collections read the snapshot
            assertThat(db.query(Account.class).count()).isEqualTo(3);       // outside: unchanged
            tx.commit();
        }
        assertThat(db.query(Account.class).orderBy(Account::getOwner).asc().list())
                .extracting(Account::getOwner).containsExactly("ann", "dee");
    }

    @Test
    void writesInsideQueriesAndOqlLandInTheTransaction() {
        for (int i = 0; i < 6; i++) {
            account("o" + i, i * 10L);
        }
        try (Transaction tx = db.begin()) {
            assertThat(tx.oql("UPDATE Account a SET a.balance = a.balance + 1000 WHERE a.balance >= 30").execute()).isEqualTo(3);
            assertThat(tx.from(Account.class).filter(where(Account::getBalance).lt(10L)).deleteAll()).isEqualTo(1);
            assertThat(tx.from(Account.class).filter(where(Account::getOwner).eq("o1")).updateEach(a -> a.note("x"))).isEqualTo(1);
            DynamicCollection dyn = tx.dynamic("Account");
            DynamicObject d = dyn.newObject();
            d.set("owner", "dyn");
            d.set("balance", 9L);
            long oid = dyn.insert(d);
            d.set("balance", 10L);
            dyn.update(d);
            assertThat(dyn.delete(oid)).isTrue();
            assertThat(dyn.delete(oid)).isFalse();
            assertThat(db.oql("SELECT COUNT(*) FROM Account a WHERE a.balance > 1000").list()).containsExactly(0L);
            assertThat(tx.oql("SELECT COUNT(*) FROM Account a WHERE a.balance > 1000").list()).containsExactly(3L);
            tx.commit();
        }
        assertThat(db.oql("SELECT COUNT(*) FROM Account a WHERE a.balance > 1000").list()).containsExactly(3L);
        assertThat(db.query(Account.class).count()).isEqualTo(5);
        assertThat(db.query(Account.class).where(Account::getNote).eq("x").count()).isEqualTo(1);
    }

    /** One account in the reference model. */
    private record Acc(String owner, long balance, int priority, int visits) {
    }

    @Test
    void readYourWritesMatchesAModelOnRandomWorkloads(@TempDir Path dir) {
        String[] owners = {"ann", "bob", "cid", "dee", "eve"};
        Random r = new Random(11);
        try (ObjectDB disk = ObjectDB.open(dir, Config.defaults().withTransactions(true).withMemSegmentBytes(4096))) {
            Map<Long, Acc> committed = new HashMap<>();
            for (int i = 0; i < 120; i++) {
                Account a = new Account(owners[r.nextInt(owners.length)], r.nextInt(1000)).priority(r.nextInt(20));
                committed.put(disk.insert(a), new Acc(a.getOwner(), a.getBalance(), a.getPriority(), 0));
                if (i == 60) {
                    disk.flush();                                          // half in segments, half in MemSegments
                }
            }
            for (int round = 0; round < 6; round++) {
                Map<Long, Acc> model = new HashMap<>(committed);
                try (Transaction tx = disk.begin()) {
                    for (int op = 0; op < 80; op++) {
                        List<Long> oids = new ArrayList<>(model.keySet());
                        int kind = r.nextInt(5);
                        if (kind == 0 || oids.isEmpty()) {
                            Account a = new Account(owners[r.nextInt(owners.length)], r.nextInt(1000)).priority(r.nextInt(20));
                            model.put(tx.insert(a), new Acc(a.getOwner(), a.getBalance(), a.getPriority(), 0));
                            continue;
                        }
                        long oid = oids.get(r.nextInt(oids.size()));
                        Acc cur = model.get(oid);
                        switch (kind) {
                            case 1 -> {
                                Account a = tx.get(Account.class, oid);
                                String owner = owners[r.nextInt(owners.length)];
                                tx.update(a.owner(owner).balance(a.getBalance() + 1));
                                model.put(oid, new Acc(owner, cur.balance() + 1, cur.priority(), cur.visits()));
                            }
                            case 2 -> {
                                tx.delete(Account.class, oid);
                                model.remove(oid);
                            }
                            case 3 -> {
                                tx.increment(Account.class, oid, "visits", 2);
                                model.put(oid, new Acc(cur.owner(), cur.balance(), cur.priority(), cur.visits() + 2));
                            }
                            default -> {
                                tx.increment(Account.class, oid, "balance", -3);
                                model.put(oid, new Acc(cur.owner(), cur.balance() - 3, cur.priority(), cur.visits()));
                            }
                        }
                        if (op % 20 == 19) {
                            verify(tx, model, owners, r);
                        }
                    }
                    verify(tx, model, owners, r);
                    if (round % 3 == 2) {
                        tx.rollback();
                        continue;
                    }
                    tx.commit();
                }
                committed = model;
                try (Transaction check = disk.begin(TxOptions.readOnly())) {
                    verify(check, committed, owners, r);
                }
            }
        }
    }

    private static void verify(Transaction tx, Map<Long, Acc> model, String[] owners, Random r) {
        assertThat(tx.query(Account.class).count()).isEqualTo(model.size());
        String owner = owners[r.nextInt(owners.length)];
        assertThat(tx.query(Account.class).where(Account::getOwner).eq(owner).list().stream().map(Account::getOid).sorted().toList())
                .isEqualTo(model.entrySet().stream().filter(e -> e.getValue().owner().equals(owner)).map(Map.Entry::getKey).sorted().toList());
        long lo = r.nextInt(500);
        assertThat(tx.query(Account.class).where(Account::getBalance).between(lo, lo + 300).count())
                .isEqualTo(model.values().stream().filter(a -> a.balance() >= lo && a.balance() <= lo + 300).count());
        assertThat(tx.query(Account.class).orderBy(Account::getPriority).desc().limit(5).list().stream().map(Account::getPriority).toList())
                .isEqualTo(model.values().stream().map(Acc::priority).sorted(Comparator.reverseOrder()).limit(5).toList());
        assertThat(tx.from(Account.class).sum(where(Account::getBalance))).isEqualTo(model.values().stream().mapToLong(Acc::balance).sum());
        assertThat(tx.from(Account.class).sum(where(Account::getVisits))).isEqualTo(model.values().stream().mapToLong(Acc::visits).sum());
        Map<Object, Long> groups = new HashMap<>();
        for (Row row : tx.from(Account.class).groupBy(where(Account::getOwner)).agg(Q.count().as("n")).toList()) {
            groups.put(row.get("owner"), row.getLong("n"));
        }
        assertThat(groups).isEqualTo(model.values().stream().collect(Collectors.groupingBy(Acc::owner,
                Collectors.counting())));
        assertThat(tx.oql("SELECT COUNT(*) FROM Account a WHERE a.owner = :o").param("o", owner).list())
                .containsExactly(model.values().stream().filter(a -> a.owner().equals(owner)).count());
        assertThat(tx.dynamic("Account").from().where("visits").gt(0).count())
                .isEqualTo(model.values().stream().filter(a -> a.visits() > 0).count());
        for (int i = 0; i < 5 && !model.isEmpty(); i++) {
            long oid = new ArrayList<>(model.keySet()).get(r.nextInt(model.size()));
            Account a = tx.get(Account.class, oid);
            Acc m = model.get(oid);
            assertThat(new Acc(a.getOwner(), a.getBalance(), a.getPriority(), a.getVisits())).isEqualTo(m);
        }
    }

    // ---------------------------------------------------------------- write-set rules, atomicity

    @Test
    void collapseRulesAndFailureAtomicity() {
        long ann = account("ann", 100);
        try (Transaction tx = db.begin()) {
            Account fresh = new Account("new", 1);
            long oid = tx.insert(fresh);
            tx.update(fresh.balance(2));                                   // insert + update = insert
            tx.increment(Account.class, oid, "visits", 3);                 // on an inserted object: plain write
            assertThat(tx.get(Account.class, oid).getVisits()).isEqualTo(3);
            assertThat(tx.delete(Account.class, oid)).isTrue();            // insert + delete = nothing
            assertThat(tx.delete(Account.class, oid)).isFalse();
            assertThatThrownBy(() -> tx.delete(fresh)).isInstanceOf(OptimisticLockException.class).hasMessageContaining("does not exist");
            assertThatThrownBy(() -> tx.update(fresh)).isInstanceOf(OptimisticLockException.class)
                    .hasMessageContaining("deleted in this transaction");
            assertThatThrownBy(() -> tx.increment(Account.class, oid, "visits", 1)).hasMessageContaining("deleted in this transaction");
            assertThatThrownBy(() -> tx.update(new Account("ghost", 0))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tx.insert(tx.get(Account.class, ann))).hasMessageContaining("already has OID");
            assertThatThrownBy(() -> tx.increment(Account.class, ann + 999, "visits", 1)).hasMessageContaining("does not exist");

            // a multi-object write that fails part-way leaves the write set untouched
            TransactionImpl impl = (TransactionImpl) tx;
            Account second = new Account("second", 1);
            List<WriteOp> ops = List.of(((Database) db).insertOp(second), new WriteOp.Increment(oid, Map.of("visits", 1)));
            assertThatThrownBy(() -> impl.applyAll(ops)).hasMessageContaining("deleted in this transaction");
            assertThat(second.getOid()).isZero();                          // the insert did not happen
            assertThat(tx.query(Account.class).count()).isEqualTo(1);
            tx.commit();
        }
        assertThat(db.query(Account.class).list()).extracting(Account::getOwner).containsExactly("ann");
    }

    @Test
    void readOnlyAndLimits() {
        long ann = account("ann", 100);
        try (Transaction ro = db.begin(TxOptions.readOnly())) {
            assertThat(ro.isReadOnly()).isTrue();
            assertThat(ro.get(Account.class, ann)).isNotNull();
            assertThatThrownBy(() -> ro.insert(new Account("x", 1))).hasMessageContaining("read-only");
            assertThatThrownBy(() -> ro.oql("DELETE FROM Account a").execute()).hasMessageContaining("read-only");
            ro.commit();
        }
        try (ObjectDB small = TestDbs.create(Config.defaults().withTransactions(true).withTxMaxBytes(300))) {
            try (Transaction tx = small.begin()) {
                tx.insert(new Account("a", 1));
                assertThatThrownBy(() -> tx.insert(new Account("b".repeat(400), 1)))
                        .hasMessageContaining("kookaburra.tx-max-bytes");
                assertThat(tx.query(Account.class).count()).isEqualTo(1);
                tx.commit();
            }
            assertThat(small.query(Account.class).count()).isEqualTo(1);
        }
        assertThatThrownBy(() -> TxOptions.defaults().timeout(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TxOptions.defaults().isolation(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(TxOptions.readOnly().timeout(Duration.ofSeconds(5)).toString()).contains("READ ONLY", "PT5S");
        assertThat(TxOptions.defaults().timeout()).isNull();
    }

    @Test
    void timeoutRollsBack() throws InterruptedException {
        long ann = account("ann", 100);
        Transaction tx = db.begin(TxOptions.defaults().timeout(Duration.ofMillis(80)));
        tx.update(tx.get(Account.class, ann).balance(1));
        Thread.sleep(150);
        assertThatThrownBy(() -> tx.get(Account.class, ann)).isInstanceOf(TransactionTimeoutException.class)
                .hasMessageContaining("longer than its timeout");
        assertThat(tx.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        assertThat(db.get(Account.class, ann).getBalance()).isEqualTo(100);
        assertThat(db.activeTransactions()).isZero();

        // the sequencer notices the expiry first (while pruning); commit then reports the timeout
        Transaction slow = db.begin(TxOptions.defaults().timeout(Duration.ofMillis(80)));
        slow.update(slow.get(Account.class, ann).balance(2));
        Thread.sleep(150);
        db.update(db.get(Account.class, ann).note("tick"));               // a commit group → prune → expiry noticed
        assertThatThrownBy(slow::commit).isInstanceOf(TransactionTimeoutException.class);

        try (Transaction unlimited = db.begin(TxOptions.defaults().timeout(Duration.ZERO))) {
            Thread.sleep(20);
            unlimited.update(unlimited.get(Account.class, ann).balance(3));
            unlimited.commit();
        }
        assertThat(db.get(Account.class, ann).getBalance()).isEqualTo(3);
    }

    @Test
    void conflictTableIsBoundedAndPruned() {
        try (ObjectDB tiny = TestDbs.create(Config.defaults().withTransactions(true).withTxConflictTableMax(8))) {
            Database d = (Database) tiny;
            long oid = tiny.insert(new Account("ann", 1));
            Transaction old = tiny.begin();
            Account mine = old.get(Account.class, oid);
            for (int i = 0; i < 12; i++) {
                tiny.update(tiny.get(Account.class, oid).balance(i));
            }
            old.update(mine.balance(-1));
            assertThatThrownBy(old::commit).isInstanceOf(ConflictException.class).hasMessageContaining("evicted");
            assertThat(d.conflictEntries()).isZero();                      // no open transaction: nothing kept
        }
        // without transactions the table is never filled
        try (ObjectDB plain = TestDbs.create()) {
            long oid = plain.insert(new Account("x", 1));
            plain.update(plain.get(Account.class, oid).balance(2));
            assertThat(((Database) plain).conflictEntries()).isZero();
        }
    }

    // ---------------------------------------------------------------- increments

    @Test
    void incrementsNeverConflictAndAreAtomic() throws Exception {
        long page = db.insert(new Account("page", 0).stats(new Stats(0, 0)));
        int threads = 8;
        int each = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger attempts = new AtomicInteger();
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                for (int i = 0; i < each; i++) {
                    db.inTransaction(tx -> {
                        attempts.incrementAndGet();
                        tx.increment(Account.class, page, "visits", 1);
                        tx.increment(Account.class, page, "stats.views", 2L);
                        tx.increment(Account.class, page, "rating", 0.5);
                    }, 0);                                                  // no retries needed: increments never conflict
                }
            }));
        }
        for (Future<?> f : done) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        Account p = db.get(Account.class, page);
        assertThat(p.getVisits()).isEqualTo(threads * each);
        assertThat(p.getStats().getViews()).isEqualTo(2L * threads * each);
        assertThat(p.getRating()).isEqualTo(0.5 * threads * each);
        assertThat(attempts.get()).isEqualTo(threads * each);
        assertThat(p.getVersion()).isEqualTo(1 + threads * each);
    }

    @Test
    void incrementSemantics() {
        long oid = db.insert(new Account("ann", 10).stats(new Stats(1, 1)));
        try (Transaction tx = db.begin()) {
            tx.increment(Account.class, oid, "balance", 5);
            tx.increment(Account.class, oid, "balance", 5);
            assertThat(tx.get(Account.class, oid).getBalance()).isEqualTo(20);   // snapshot value + own deltas
            db.increment(Account.class, oid, "balance", 100);              // concurrent increment: no conflict
            tx.commit();
        }
        assertThat(db.get(Account.class, oid).getBalance()).isEqualTo(120);

        // an increment followed by a full update of an entity read inside the transaction becomes an update
        try (Transaction tx = db.begin()) {
            tx.increment(Account.class, oid, "visits", 4);
            Account a = tx.get(Account.class, oid);
            tx.update(a.note("seen"));
            tx.commit();
        }
        assertThat(db.get(Account.class, oid).getVisits()).isEqualTo(4);

        // a full update after someone else's increment conflicts (the object changed after the snapshot)
        Transaction t = db.begin();
        Account mine = t.get(Account.class, oid);
        db.increment(Account.class, oid, "visits", 1);
        t.update(mine.note("late"));
        assertThatThrownBy(t::commit).isInstanceOf(ConflictException.class);

        // invalid increments fail the call only
        try (Transaction tx = db.begin()) {
            assertThatThrownBy(() -> tx.increment(Account.class, oid, "owner", 1)).hasMessageContaining("not numeric");
            assertThatThrownBy(() -> tx.increment(Account.class, oid, "nope", 1)).hasMessageContaining("no field 'nope'");
            assertThatThrownBy(() -> tx.increment(Account.class, oid, "visits", 1.5)).hasMessageContaining("is not");
            assertThatThrownBy(() -> tx.increment(Account.class, oid, "visits", Integer.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
            assertThatThrownBy(() -> tx.increment(Account.class, oid, "owner.x", 1)).hasMessageContaining("not embedded");
            assertThatThrownBy(() -> tx.increment(Account.class, oid, null, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> tx.increment(Dog.class, oid, "visits", 1)).isInstanceOf(IllegalArgumentException.class);
            tx.increment(Account.class, oid, "visits", 2.0);               // integral double is fine
            tx.commit();
        }
        assertThat(db.get(Account.class, oid).getVisits()).isEqualTo(7);
        long nostats = db.insert(new Account("bare", 0));
        assertThatThrownBy(() -> db.increment(Account.class, nostats, "stats.views", 1)).hasMessageContaining("is null");
        assertThatThrownBy(() -> db.increment(Account.class, nostats, "balance", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> db.increment(Dog.class, nostats, "balance", 1)).isInstanceOf(IllegalArgumentException.class);

        // object deleted before the increment commits
        Transaction late = db.begin();
        late.increment(Account.class, nostats, "visits", 1);
        db.delete(Account.class, nostats);
        assertThatThrownBy(late::commit).isInstanceOf(ConflictException.class).hasMessageContaining("deleted before");
        assertThatThrownBy(() -> db.increment(Account.class, nostats, "visits", 1)).isInstanceOf(ConflictException.class);
    }

    // ---------------------------------------------------------------- retry helper, invariants

    @Test
    void inTransactionRetriesConflicts() {
        long ann = account("ann", 100);
        AtomicInteger runs = new AtomicInteger();
        long result = db.inTransactionResult(tx -> {
            Account a = tx.get(Account.class, ann);
            if (runs.incrementAndGet() == 1) {
                db.update(db.get(Account.class, ann).note("interference"));    // forces a conflict on the first run
            }
            tx.update(a.balance(a.getBalance() + 1));
            return a.getBalance();
        });
        assertThat(runs.get()).isEqualTo(2);
        assertThat(result).isEqualTo(101);
        assertThat(db.get(Account.class, ann).getBalance()).isEqualTo(101);

        AtomicInteger tries = new AtomicInteger();
        assertThatThrownBy(() -> db.inTransaction(tx -> {
            tries.incrementAndGet();
            Account a = tx.get(Account.class, ann);
            db.update(db.get(Account.class, ann).note("again"));
            tx.update(a.balance(0));
        }, 2)).isInstanceOf(ConflictException.class);
        assertThat(tries.get()).isEqualTo(3);

        assertThatThrownBy(() -> db.inTransaction(tx -> {
            tx.insert(new Account("x", 1));
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(db.query(Account.class).where(Account::getOwner).eq("x").count()).isZero();
        assertThatThrownBy(() -> db.inTransaction(tx -> { }, -1)).isInstanceOf(IllegalArgumentException.class);

        // the body may commit or roll back itself
        db.inTransaction(tx -> {
            tx.insert(new Account("self", 1));
            tx.commit();
        });
        db.inTransaction(Transaction::rollback);
        long count = db.inTransactionResult(tx -> tx.query(Account.class).count(), 0);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void bankTransfersKeepTheTotal() throws Exception {
        int accounts = 6;
        long[] oids = new long[accounts];
        for (int i = 0; i < accounts; i++) {
            oids[i] = account("acct" + i, 1_000);
        }
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int seed = t;
            done.add(pool.submit(() -> {
                Random r = new Random(seed);
                for (int i = 0; i < 60; i++) {
                    long from = oids[r.nextInt(accounts)];
                    long to = oids[r.nextInt(accounts)];
                    long amount = 1 + r.nextInt(50);
                    db.inTransaction(tx -> {
                        Account a = tx.get(Account.class, from);
                        Account b = tx.get(Account.class, to);
                        if (from == to || a.getBalance() < amount) {
                            return;
                        }
                        tx.update(a.balance(a.getBalance() - amount));
                        tx.update(b.balance(b.getBalance() + amount));
                    }, 200);
                }
            }));
        }
        for (Future<?> f : done) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(db.from(Account.class).sum(where(Account::getBalance))).isEqualTo(accounts * 1_000L);
        assertThat(db.query(Account.class).where(Account::getBalance).lt(0L).count()).isZero();
        assertThat(((Database) db).conflictEntries()).isZero();
    }

    // ---------------------------------------------------------------- durability, facade

    @Test
    void committedTransactionsSurviveRestartUncommittedDoNot(@TempDir Path dir) {
        Config cfg = Config.defaults().withTransactions(true);
        long kept;
        long lost;
        try (ObjectDB disk = ObjectDB.open(dir, cfg)) {
            try (Transaction tx = disk.begin()) {
                kept = tx.insert(new Account("kept", 1));
                tx.commit();
            }
            Transaction open = disk.begin();
            lost = open.insert(new Account("lost", 2));
        }                                                                  // closed with a transaction still open
        try (ObjectDB disk = ObjectDB.open(dir, cfg)) {
            assertThat(disk.get(Account.class, kept)).isNotNull();
            assertThat(disk.get(Account.class, lost)).isNull();
            long next = disk.insert(new Account("next", 3));
            assertThat(Oids.seq(next)).isGreaterThan(Oids.seq(kept));     // OIDs continue after committed ones
        }
    }

    @Test
    void facadeSeenByQueriesInsideTheTransaction() {
        long ann = account("ann", 100);
        try (Transaction tx = db.begin()) {
            ObjectDB inside = ((TransactionImpl) tx).database();
            assertThat(inside.get(Account.class, ann)).isNotNull();
            assertThat(inside.get(ann)).isNotNull();
            assertThat(inside.stats().objects()).isEqualTo(1);
            assertThat(inside.getAll(Account.class, List.of(ann))).hasSize(1);
            long b = inside.insert(new Account("b", 1));
            Account bob = inside.get(Account.class, b);
            inside.update(bob.balance(2));
            inside.increment(Account.class, b, "visits", 1);
            assertThat(inside.from(Account.class).count()).isEqualTo(2);
            assertThat(inside.oql("SELECT COUNT(*) FROM Account a").list()).containsExactly(2L);
            assertThat(inside.oql("SELECT a FROM Account a", Account.class).list()).hasSize(2);
            assertThat(inside.dynamic("Account").from().count()).isEqualTo(2);
            try (WriteBatch batch = inside.batch()) {
                batch.insert(new Account("c", 1)).update(inside.get(Account.class, b)).delete(Account.class, ann);
                assertThat(batch.size()).isEqualTo(3);
                batch.delete(b).commit();
            }
            Account c = inside.query(Account.class).where(Account::getOwner).eq("c").list().get(0);
            inside.delete(c);
            assertThat(inside.delete(Account.class, ann)).isFalse();
            assertThat(inside.schema()).isSameAs(db.schema());
            assertThat(inside.path()).isEqualTo(db.path());
            assertThat(inside.activeTransactions()).isEqualTo(1);
            assertThatThrownBy(inside::snapshot).isInstanceOf(UnsupportedOperationException.class);
            inside.flush();
            inside.compact();
            inside.close();                                                 // does not close the database
            try (Transaction nested = inside.begin(Isolation.SNAPSHOT)) {   // nested begin = independent transaction
                assertThat(nested.id()).isNotEqualTo(tx.id());
            }
            try (Transaction nested = inside.begin(); Transaction nested2 = inside.begin(TxOptions.readOnly())) {
                assertThat(nested2.isReadOnly()).isTrue();
                assertThat(nested.state()).isEqualTo(Transaction.State.ACTIVE);
            }
            inside.inTransaction(t -> t.insert(new Account("n1", 1)));
            inside.inTransaction(t -> t.insert(new Account("n2", 1)), 0);
            int one = inside.inTransactionResult(t -> 1);
            int two = inside.inTransactionResult(t -> 2, 0);
            assertThat(one + two).isEqualTo(3);
            WriteBatch discarded = inside.batch().insert(new Account("never", 1));
            discarded.close();
            tx.commit();
        }
        assertThat(db.query(Account.class).list()).extracting(Account::getOwner).containsExactlyInAnyOrder("n1", "n2");
    }
}
