package com.github.kookaburra.backup;

import com.github.kookaburra.BackupInfo;
import com.github.kookaburra.Config;
import com.github.kookaburra.ConstraintViolationException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Ref;
import com.github.kookaburra.TestDbs;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.model.Account;
import com.github.kookaburra.model.Invoice;
import com.github.kookaburra.model.Member;
import com.github.kookaburra.model.Membership;
import com.github.kookaburra.model.Person;
import com.github.kookaburra.model.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Online backup: a consistent snapshot while transfers run concurrently (total balance preserved), updated and
 * deleted objects, memtable and segment data, every value type, constraints and indexes after restore, OIDs that
 * continue after the backup's, target checks ({@link BackupDiskTest} runs the same cases on disk).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class BackupTest {

    protected ObjectDB db;

    @TempDir
    Path tmp;

    protected ObjectDB create() {
        return TestDbs.create(config());
    }

    protected Config config() {
        return Config.defaults().withTransactions(true).withMemSegmentBytes(64 << 10);
    }

    /** Disk variant: flush part of the data into segment files first. */
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

    /** Every object of the database as dynamic objects, by OID (class-agnostic comparison). */
    private static Map<Long, String> dump(ObjectDB d) {
        Map<Long, String> out = new TreeMap<>();
        for (String c : d.schema().collections()) {
            for (DynamicObject o : d.dynamic(c).from().list()) {
                out.put(o.oid(), o.className() + " v" + o.version() + " " + o.toString());
            }
        }
        return out;
    }

    @Test
    void consistentSnapshotWhileWriting() throws Exception {
        List<Long> accounts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            accounts.add(db.insert(new Account("acc" + i, 1_000)));
        }
        for (int i = 0; i < 300; i++) {
            db.insert(new Person("p" + i, i % 2 == 0 ? "Perth" : "Hobart", 20 + i % 50));
        }
        checkpoint();
        db.from(Person.class).where(Person::getAge).lt(25).deleteAll();
        db.from(Person.class).where(Person::getAge).eq(30).updateEach(p -> p.score(99));

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong transfers = new AtomicLong();
        Thread writer = new Thread(() -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            while (!stop.get()) {
                long from = accounts.get(r.nextInt(accounts.size()));
                long to = accounts.get(r.nextInt(accounts.size()));
                if (from == to) {
                    continue;
                }
                int amount = r.nextInt(1, 50);
                db.inTransaction(tx -> {
                    Account a = tx.get(Account.class, from);
                    Account b = tx.get(Account.class, to);
                    tx.update(a.balance(a.getBalance() - amount));
                    tx.update(b.balance(b.getBalance() + amount));
                }, 50);
                transfers.incrementAndGet();
                db.insert(new Person("w" + transfers.get(), "Darwin", 1));
            }
        });
        writer.start();
        while (transfers.get() < 20) {
            Thread.sleep(1);
        }
        BackupInfo info = db.backup(tmp.resolve("b1"));
        long during = transfers.get();
        while (transfers.get() < during + 20) {
            Thread.sleep(1);
        }
        stop.set(true);
        writer.join();

        assertThat(info.path()).isEqualTo(tmp.resolve("b1").toAbsolutePath());
        assertThat(info.objects()).isPositive();
        assertThat(info.segments()).isPositive();
        assertThat(info.bytes()).isPositive();
        assertThat(Files.list(info.path().resolve("col")).count()).isEqualTo(2);
        try (ObjectDB restored = ObjectDB.open(info.path(), config())) {
            long total = restored.from(Account.class).list().stream().mapToLong(Account::getBalance).sum();
            assertThat(total).isEqualTo(50 * 1_000L);                                   // no half-applied transfer
            assertThat(restored.from(Account.class).count()).isEqualTo(50);
            assertThat(restored.from(Person.class).where(Person::getAge).between(20, 24).count()).isZero();   // deleted before
            assertThat(restored.from(Person.class).where(Person::getScore).eq(99).count()).isEqualTo(6);
            assertThat(restored.snapshot().seq()).isEqualTo(info.seq());
            assertThat(restored.from(Person.class).count() + restored.from(Account.class).count()).isEqualTo(info.objects());
            assertThat(db.from(Person.class).count()).isGreaterThan(restored.from(Person.class).count());   // later writes are not in it
            // new objects continue after the backup's OIDs; the restored database is fully writable
            long maxOid = restored.from(Person.class).list().stream().mapToLong(Person::getOid).max().orElseThrow();
            long fresh = restored.insert(new Person("new", "Perth", 40));
            assertThat(fresh).isGreaterThan(maxOid);
            restored.flush();
            restored.compact();
            assertThat(restored.from(Person.class).where(Person::getCity).eq("Darwin").count())
                    .isEqualTo(restored.from(Person.class).where(Person::getName).startsWith("w").count());
        }
    }

    @Test
    void restoresEveryValueTypeAndConstraint() {
        long ann = db.insert(new Member("ann", "ann@x.io").code("12.50"));
        long red = db.insert(new Team("red"));
        db.insert(new Membership(ann, red, "lead"));
        Invoice inv = new Invoice("ann", "19.90").rate("0.075").parts(List.of(new BigDecimal("1.5")))
                .fees(Map.of("card", new BigDecimal("0.30")));
        db.insert(inv);
        Person p = new Person("Ann", "Perth", 30);
        db.insert(p);
        checkpoint();
        db.insert(new Person("Bob", "Hobart", 31));                                       // memtable only
        Map<Long, String> before = dump(db);
        BackupInfo info = db.backup(tmp.resolve("b2"));
        assertThat(info.objects()).isEqualTo(before.size());
        try (ObjectDB restored = ObjectDB.open(info.path(), config())) {
            assertThat(dump(restored)).isEqualTo(before);
            assertThat(restored.schema().toString()).isEqualTo(db.schema().toString());
            assertThat(restored.from(Invoice.class).where(Invoice::getAmount).eq(new BigDecimal("19.9")).count()).isEqualTo(1);
            assertThatThrownBy(() -> restored.insert(new Member("dup", "ann@x.io"))).isInstanceOf(ConstraintViolationException.class);
            assertThatThrownBy(() -> restored.delete(Team.class, red)).isInstanceOf(ConstraintViolationException.class);
            assertThat(restored.referrers(ann)).hasSize(1);
            assertThat(restored.get(Membership.class, restored.referrers(ann).get(0).oid()).getTeam()).isEqualTo(Ref.of(red));
        }
    }

    @Test
    void emptyDatabasesAndTargetChecks() throws Exception {
        BackupInfo empty = db.backup(tmp.resolve("empty"));
        assertThat(empty.objects()).isZero();
        assertThat(empty.segments()).isZero();
        try (ObjectDB restored = ObjectDB.open(empty.path(), config())) {
            assertThat(restored.schema().collections()).isEmpty();
        }
        db.insert(new Team("t"));
        Path used = Files.createDirectories(tmp.resolve("used"));
        Files.writeString(used.resolve("x.txt"), "x");
        assertThatThrownBy(() -> db.backup(used)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not empty");
        assertThatThrownBy(() -> db.backup(used.resolve("x.txt"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is a file");
        if (db.path() != null) {
            assertThatThrownBy(() -> db.backup(db.path())).hasMessageContaining("its own directory");
        }
        Path emptyDir = Files.createDirectories(tmp.resolve("emptydir"));
        assertThat(db.backup(emptyDir).objects()).isEqualTo(1);
        try (ObjectDB restored = ObjectDB.open(emptyDir, config())) {
            assertThat(restored.from(Team.class).count()).isEqualTo(1);
            assertThatThrownBy(() -> db.backup(emptyDir)).isInstanceOf(IllegalArgumentException.class);
        }
        try (var tx = db.begin()) {
            assertThat(tx.from(Team.class).count()).isEqualTo(1);
            assertThat(tx.oql("SELECT COUNT(*) FROM Team t").list()).containsExactly(1L);
        }
    }
}
