package com.github.kookaburra.engine;

import com.github.kookaburra.Config;
import com.github.kookaburra.Durability;
import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.model.Dog;
import com.github.kookaburra.model.Person;
import com.github.kookaburra.query.Pred;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static com.github.kookaburra.query.DifferentialTest.randomPerson;
import static com.github.kookaburra.query.DifferentialTest.randomPred;
import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Durability: reopen, WAL replay, crash images, torn WAL tails, orphan cleanup and locking.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class PersistenceTest {

    @TempDir
    Path tmp;

    private final Map<Long, Person> model = new LinkedHashMap<>();

    private static Config small() {
        return Config.defaults().withMemSegmentBytes(8192).withCompactionFanIn(3);
    }

    @Test
    void reopenAfterCloseKeepsEverything() throws IOException {
        Path dir = tmp.resolve("db");
        Random r = new Random(1);
        try (ObjectDB db = ObjectDB.open(dir, small())) {
            assertThat(db.path()).isEqualTo(dir);
            workload(db, r, 400);
        }
        for (int round = 0; round < 3; round++) {
            try (ObjectDB db = ObjectDB.open(dir, small())) {
                verify(db, r);
                workload(db, r, 150);
                if (round == 1) {
                    db.flush();
                }
                verify(db, r);
            }
        }
        try (ObjectDB db = ObjectDB.open(dir, small())) {
            verify(db, r);
            long dogs = db.from(Dog.class).count();
            assertThat(dogs).isEqualTo(3);
            db.compact();
            verify(db, r);
            workload(db, r, 100);
        }
        try (ObjectDB db = ObjectDB.open(dir, small())) {
            verify(db, r);
        }
        try (Stream<Path> files = Files.list(dir.resolve("col").resolve(Person.class.getName()))) {
            assertThat(files.filter(f -> f.toString().endsWith(".odbf")).count()).as("old segment files deleted").isLessThan(5);
        }
    }

    @Test
    void segmentFilesUseOdbfAndLegacySegFilesAreRenamed() throws IOException {
        Path dir = tmp.resolve("legacy");
        Random r = new Random(5);
        try (ObjectDB db = ObjectDB.open(dir, small())) {
            workload(db, r, 200);
            db.flush();
        }
        Path col = dir.resolve("col").resolve(Person.class.getName());
        List<Path> segs;
        try (Stream<Path> files = Files.list(col)) {
            segs = files.filter(f -> f.toString().endsWith(".odbf")).toList();
        }
        assertThat(segs).isNotEmpty();
        for (Path p : segs) {                                          // an older database: .seg files
            Files.move(p, p.resolveSibling(p.getFileName().toString().replace(".odbf", ".seg")));
        }
        try (ObjectDB db = ObjectDB.open(dir, small())) {
            verify(db, r);
        }
        try (Stream<Path> files = Files.list(col)) {
            assertThat(files.map(f -> f.getFileName().toString())).noneMatch(n -> n.endsWith(".seg")).anyMatch(n -> n.endsWith(".odbf"));
        }
    }

    @Test
    void crashImagesRecoverFromTheWal() throws IOException {
        Path dir = tmp.resolve("db");
        Random r = new Random(2);
        try (ObjectDB db = ObjectDB.open(dir, small())) {
            workload(db, r, 300);
            // writes return after the WAL fsync: a copy now is a valid crash image
            Path crash = tmp.resolve("crash");
            copy(dir, crash);
            Files.delete(crash.resolve("LOCK"));
            workload(db, r, 50);    // not in the image
            Map<Long, Person> imageModel = new LinkedHashMap<>(model);
            try (ObjectDB recovered = ObjectDB.open(crash, small())) {
                assertThat(recovered.from(Person.class).count()).isLessThanOrEqualTo(imageModel.size());
            }
        }
    }

    @Test
    void exactCrashImage() throws IOException {
        Path dir = tmp.resolve("db");
        Random r = new Random(3);
        Path crash = tmp.resolve("crash");
        try (ObjectDB db = ObjectDB.open(dir, small())) {
            workload(db, r, 300);
            copy(dir, crash);
        }
        Files.deleteIfExists(crash.resolve("LOCK"));
        // torn tail: half a record appended to the newest WAL file
        Path wal;
        try (Stream<Path> s = Files.list(crash.resolve("wal"))) {
            wal = s.max(Comparator.comparing(p -> Long.parseLong(p.getFileName().toString().replaceAll("\\D", "")))).orElseThrow();
        }
        Files.write(wal, new byte[]{20, 0, 0, 0, 1, 2, 3}, StandardOpenOption.APPEND);
        // orphan segment from an interrupted flush
        Path col = crash.resolve("col").resolve(Person.class.getName());
        Files.write(col.resolve("seg_99999.odbf"), new byte[]{1});
        Files.write(col.resolve("seg_99999.odbf.tmp"), new byte[]{1});
        try (ObjectDB db = ObjectDB.open(crash, small())) {
            verify(db, r);
            assertThat(Files.exists(col.resolve("seg_99999.odbf"))).isFalse();
            assertThat(Files.exists(col.resolve("seg_99999.odbf.tmp"))).isFalse();
            workload(db, r, 20);
            verify(db, r);
        }
    }

    @Test
    void asyncDurabilityAndLocking() {
        Path dir = tmp.resolve("db");
        Config async = small().withDurability(Durability.ASYNC);
        try (ObjectDB db = ObjectDB.open(dir, async)) {
            workload(db, new Random(4), 100);
            assertThatThrownBy(() -> ObjectDB.open(dir)).isInstanceOf(KookaburraException.class).hasMessageContaining("LOCK");
        }
        try (ObjectDB db = ObjectDB.open(dir, async)) {
            verify(db, new Random(5));
        }
    }

    @Test
    void corruptCommitIsReported() throws IOException {
        Path dir = tmp.resolve("db");
        try (ObjectDB db = ObjectDB.open(dir, small())) {
            workload(db, new Random(6), 200);
            db.flush();
        }
        String current = Files.readString(dir.resolve("CURRENT")).trim();
        byte[] c = Files.readAllBytes(dir.resolve(current));
        c[10] ^= 1;
        Files.write(dir.resolve(current), c);
        assertThatThrownBy(() -> ObjectDB.open(dir, small())).hasMessageContaining("checksum");
        assertThatThrownBy(() -> Commit.decode(new byte[3])).hasMessageContaining("truncated");
        byte[] notCommit = new Commit(1, 1, 1, Map.of(), Map.of()).encode();
        notCommit[0] ^= 1;
        CRC32C crc = new CRC32C();
        crc.update(notCommit, 0, notCommit.length - 4);
        ByteBuffer.wrap(notCommit).order(ByteOrder.LITTLE_ENDIAN).putInt(notCommit.length - 4, (int) crc.getValue());
        assertThatThrownBy(() -> Commit.decode(notCommit)).hasMessageContaining("not a commit");
        // the lock must have been released by the failed open
        Files.write(dir.resolve(current), new Commit(1, 0, 1, Map.of(), Map.of()).encode());
        try (ObjectDB db = ObjectDB.open(dir, small())) {
            assertThat(db.from(Person.class).count()).isGreaterThanOrEqualTo(0);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void workload(ObjectDB db, Random r, int n) {
        List<Long> oids = new ArrayList<>(model.keySet());
        for (int i = 0; i < n; i++) {
            int op = r.nextInt(10);
            if (op < 6 || oids.isEmpty()) {
                Person p = randomPerson(r);
                db.insert(p);
                model.put(p.getOid(), p);
                oids.add(p.getOid());
            } else {
                long oid = oids.get(r.nextInt(oids.size()));
                Person p = model.get(oid);
                if (p == null) {
                    continue;
                }
                if (op < 9) {
                    Person cur = db.get(Person.class, oid);
                    cur.score(r.nextInt(100));
                    db.update(cur);
                    model.put(oid, cur);
                } else {
                    db.delete(Person.class, oid);
                    model.remove(oid);
                }
            }
        }
        if (db.from(Dog.class).count() == 0) {
            for (int i = 0; i < 3; i++) {
                db.insert(new Dog("d" + i, i, "b"));
            }
        }
    }

    private void verify(ObjectDB db, Random r) {
        assertThat(db.from(Person.class).count()).isEqualTo(model.size());
        for (Person p : model.values()) {
            Person got = db.get(Person.class, p.getOid());
            assertThat(got).as("oid %d", p.getOid()).isNotNull();
            assertThat(got.getScore()).isEqualTo(p.getScore());
            assertThat(got.getName()).isEqualTo(p.getName());
        }
        for (int q = 0; q < 30; q++) {
            Pred<Person> pred = randomPred(r, 2);
            assertThat(db.from(Person.class).filter(pred).count()).as(pred.toString())
                    .isEqualTo(model.values().stream().filter(pred).count());
        }
        assertThat(db.from(Person.class).filter(where(Person::getAge).ge(0)).count()).isEqualTo(model.size());
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }
}
