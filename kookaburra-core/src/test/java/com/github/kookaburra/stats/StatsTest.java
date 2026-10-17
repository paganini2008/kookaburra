package com.github.kookaburra.stats;

import com.github.kookaburra.ConflictException;
import com.github.kookaburra.ConstraintViolationException;
import com.github.kookaburra.Config;
import com.github.kookaburra.DatabaseStats;
import com.github.kookaburra.Isolation;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.TransactionTimeoutException;
import com.github.kookaburra.model.Member;
import com.github.kookaburra.model.Membership;
import com.github.kookaburra.model.Task;
import com.github.kookaburra.model.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ObjectDB#stats()}: object counts and storage per collection, write / transaction / constraint counters and
 * background work ({@link StatsDiskTest} runs the same cases on disk).
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
class StatsTest {

    protected ObjectDB db;

    @TempDir
    Path tmp;

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

    @Test
    void emptyDatabase() {
        DatabaseStats s = db.stats();
        assertThat(s.objects()).isZero();
        assertThat(s.collections()).isEmpty();
        assertThat(s.collection("x")).isNull();
        assertThat(s.writes().batches()).isZero();
        assertThat(s.writes().batchesPerGroup()).isZero();
        assertThat(s.transactions().byIsolation()).containsOnlyKeys(Isolation.values());
        assertThat(s.transactions().total()).isEqualTo(new DatabaseStats.Outcomes(0, 0, 0, 0));
        assertThat(s.takenAt()).isAfterOrEqualTo(s.openedAt());
    }

    @Test
    void writesAndObjects() {
        Member ann = new Member("ann", "ann@x.io");
        db.insert(ann);
        db.insert(new Member("bob", "bob@x.io"));
        db.batch().insert(new Team("red")).insert(new Team("blue")).commit();
        db.update(ann.email("ann2@x.io"));
        db.delete(Team.class, db.from(Team.class).list().get(0).getOid());

        DatabaseStats s = db.stats();
        assertThat(s.writes().batches()).isEqualTo(5);
        assertThat(s.writes().inserts()).isEqualTo(4);
        assertThat(s.writes().updates()).isEqualTo(1);
        assertThat(s.writes().deletes()).isEqualTo(1);
        assertThat(s.writes().groupCommits()).isBetween(1L, 5L);
        assertThat(s.writes().batchesPerGroup()).isGreaterThanOrEqualTo(1);
        assertThat(s.objects()).isEqualTo(3);
        assertThat(s.collection(Member.class.getName()).objects()).isEqualTo(2);
        assertThat(s.collection(Member.class.getName()).memRows()).isEqualTo(3);   // two inserts + one new version
        assertThat(s.collection(Member.class.getName()).memBytes()).isPositive();
        assertThat(s.collection(Team.class.getName()).objects()).isEqualTo(1);
        assertThat(s.seq()).isEqualTo(5);

        checkpoint();
        DatabaseStats after = db.stats();
        assertThat(after.objects()).isEqualTo(3);
        assertThat(after.collection(Member.class.getName()).objects()).isEqualTo(2);
    }

    @Test
    void segmentsAndMaintenance() {
        for (int i = 0; i < 50; i++) {
            db.insert(new Member("m" + i, "m" + i + "@x.io"));
        }
        db.flush();
        for (int i = 0; i < 10; i++) {
            db.delete(Member.class, db.from(Member.class).list().get(0).getOid());
        }
        db.flush();
        DatabaseStats s = db.stats();
        DatabaseStats.CollectionStats m = s.collection(Member.class.getName());
        assertThat(m.objects()).isEqualTo(40);
        assertThat(m.segments()).isEqualTo(1);
        assertThat(m.segmentBytes()).isPositive();
        assertThat(m.deletedRows()).isEqualTo(10);
        assertThat(m.memRows()).isZero();
        assertThat(s.segmentBytes()).isEqualTo(m.segmentBytes());
        assertThat(s.maintenance().flushes()).isEqualTo(1);
        assertThat(s.maintenance().flushedRows()).isEqualTo(50);
        assertThat(s.maintenance().flushedBytes()).isPositive();

        db.compact();
        DatabaseStats c = db.stats();
        assertThat(c.collection(Member.class.getName()).deletedRows()).isZero();
        assertThat(c.collection(Member.class.getName()).objects()).isEqualTo(40);
        assertThat(c.maintenance().compactions()).isEqualTo(1);
        assertThat(c.maintenance().compactedSegments()).isEqualTo(1);
        assertThat(c.maintenance().compactedBytes()).isPositive();
        assertThat(c.maintenance().pending()).isZero();

        for (Member x : db.from(Member.class).list()) {
            db.delete(x);
        }
        db.compact();
        DatabaseStats empty = db.stats();
        assertThat(empty.objects()).isZero();
        assertThat(empty.maintenance().compactions()).isEqualTo(2);   // a fully deleted segment is dropped
        assertThat(empty.maintenance().compactedSegments()).isEqualTo(2);
    }

    @Test
    void rejectedWrites() {
        Member ann = new Member("ann", "ann@x.io");
        db.insert(ann);
        assertThatThrownBy(() -> db.insert(new Member("ann2", "ann@x.io"))).isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> db.insert(new Membership(ann.getOid(), 999, "x")))
                .isInstanceOf(ConstraintViolationException.class);
        DatabaseStats s = db.stats();
        assertThat(s.writes().batches()).isEqualTo(1);
        assertThat(s.writes().rejected()).isEqualTo(2);
        assertThat(s.constraints().uniqueViolations()).isEqualTo(1);
        assertThat(s.constraints().foreignKeyViolations()).isEqualTo(1);
    }

    @Test
    void cascadesAndSetNull() {
        Member ann = new Member("ann", "ann@x.io");
        db.insert(ann);
        Team red = new Team("red");
        db.insert(red);
        db.insert(new Membership(ann.getOid(), red.getOid(), "lead"));
        db.insert(new Membership(ann.getOid(), red.getOid(), "dev"));
        db.insert(new Task("t").assignee(ann.getOid()));
        checkpoint();
        db.delete(Member.class, ann.getOid());
        DatabaseStats s = db.stats();
        assertThat(s.constraints().cascadeDeletes()).isEqualTo(2);
        assertThat(s.constraints().setNulls()).isEqualTo(1);
        assertThat(s.writes().deletes()).isEqualTo(3);
        assertThat(s.writes().updates()).isEqualTo(1);
        assertThat(s.collection(Membership.class.getName()).objects()).isZero();
    }

    @Test
    void transactions() {
        Member ann = new Member("ann", "ann@x.io");
        db.insert(ann);
        try (Transaction tx = db.begin(Isolation.SERIALIZABLE)) {
            tx.insert(new Team("a"));
            tx.commit();
        }
        try (Transaction tx = db.begin(Isolation.READ_COMMITTED)) {
            tx.insert(new Team("b"));
            tx.rollback();
        }
        try (Transaction tx = db.begin(Isolation.SNAPSHOT)) {
            tx.commit();                                   // nothing written: committed without a batch
        }
        Transaction t1 = db.begin(Isolation.SNAPSHOT);
        Transaction t2 = db.begin(Isolation.SNAPSHOT);
        assertThat(db.stats().transactions().active()).isEqualTo(2);
        t1.update(db.get(Member.class, ann.getOid()).email("t1@x.io"));
        t2.update(t2.get(Member.class, ann.getOid()).email("t2@x.io"));
        t1.commit();
        assertThatThrownBy(t2::commit).isInstanceOf(ConflictException.class);

        DatabaseStats.Transactions s = db.stats().transactions();
        assertThat(s.active()).isZero();
        assertThat(s.byIsolation().get(Isolation.SERIALIZABLE).committed()).isEqualTo(1);
        assertThat(s.byIsolation().get(Isolation.READ_COMMITTED).rolledBack()).isEqualTo(1);
        DatabaseStats.Outcomes snap = s.byIsolation().get(Isolation.SNAPSHOT);
        assertThat(snap.committed()).isEqualTo(2);
        assertThat(snap.rolledBack()).isEqualTo(1);
        assertThat(snap.conflicts()).isEqualTo(1);
        assertThat(s.total()).isEqualTo(new DatabaseStats.Outcomes(3, 2, 1, 0));
        assertThat(db.stats().writes().rejected()).isEqualTo(1);
    }

    @Test
    void retriesAndTimeouts() {
        Member ann = new Member("ann", "ann@x.io");
        db.insert(ann);
        AtomicInteger attempts = new AtomicInteger();
        db.inTransaction(tx -> {
            Member m = tx.get(Member.class, ann.getOid());
            if (attempts.getAndIncrement() == 0) {
                db.update(db.get(Member.class, ann.getOid()).email("other@x.io"));   // concurrent commit
            }
            tx.update(m.email("mine" + attempts.get() + "@x.io"));
        });
        assertThat(db.stats().transactions().retries()).isEqualTo(1);
        assertThat(db.stats().transactions().total().conflicts()).isEqualTo(1);

        try (ObjectDB quick = ObjectDB.inMemory(config().withTxTimeoutMillis(1))) {
            Transaction tx = quick.begin();
            tx.insert(new Team("late"));
            sleep(30);
            assertThatThrownBy(tx::commit).isInstanceOf(TransactionTimeoutException.class);
            assertThat(quick.stats().transactions().total().timeouts()).isEqualTo(1);
        }
    }

    @Test
    void backups() {
        db.insert(new Member("ann", "ann@x.io"));
        db.backup(tmp.resolve("b1"));
        assertThat(db.stats().maintenance().backups()).isEqualTo(1);
    }

    @Test
    void closed() {
        db.close();
        assertThatThrownBy(db::stats).hasMessageContaining("closed");
        db = create();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
