package com.github.kookaburra.engine;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.model.Person;
import com.github.kookaburra.segment.Segment;
import com.github.kookaburra.exec.SegmentView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.github.kookaburra.query.Q.where;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Space of deleted rows is reclaimed: fully deleted segments are dropped, segments past
 * {@code compaction-garbage-ratio} are rewritten on their own, {@code compact()} expunges every delete.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class ReclaimTest {

    @TempDir
    Path tmp;

    private static Config config() {
        return Config.defaults().withMemSegmentBytes(64 * 1024).withSegmentMaxBytes(256 * 1024).withCompactionFanIn(4);
    }

    private static void load(ObjectDB db, int n) {
        for (int from = 0; from < n; from += 500) {
            try (WriteBatch b = db.batch()) {
                for (int i = from; i < Math.min(n, from + 500); i++) {
                    b.insert(new Person("p" + i, i % 2 == 0 ? "Sydney" : "Perth", i % 90));
                }
                b.commit();
            }
        }
        db.flush();
        db.compact();
    }

    private static List<Segment> segments(ObjectDB db) {
        return ((Database) db).views(Person.class.getName()).stream()
                .filter(v -> v instanceof Segment).map(v -> (Segment) v).toList();
    }

    private static long odbfBytes(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".odbf")).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    @Test
    void deletingEverythingDropsTheSegmentFiles() throws IOException {
        Path dir = tmp.resolve("db");
        try (ObjectDB db = ObjectDB.open(dir, config())) {
            load(db, 6000);
            assertThat(segments(db).size()).isGreaterThan(1);       // bigger than segment-max-bytes: never merged by tier
            assertThat(odbfBytes(dir)).isGreaterThan(256 * 1024);
            assertThat(db.from(Person.class).deleteAll()).isEqualTo(6000);
            db.compact();
            assertThat(segments(db)).isEmpty();
            assertThat(odbfBytes(dir)).isZero();
            assertThat(db.from(Person.class).count()).isZero();
            db.insert(new Person("after", "Hobart", 1));
        }
        try (ObjectDB db = ObjectDB.open(dir, config())) {
            assertThat(db.from(Person.class).toList()).extracting(Person::getName).containsExactly("after");
        }
    }

    @Test
    void garbageAboveTheRatioIsRewrittenInTheBackground() throws IOException {
        Path dir = tmp.resolve("db");
        try (ObjectDB db = ObjectDB.open(dir, config().withCompactionGarbageRatio(0.3))) {
            load(db, 6000);
            int before = segments(db).stream().mapToInt(Segment::size).sum();
            db.from(Person.class).filter(where(Person::getCity).eq("Sydney")).deleteAll();   // 50 % of every segment
            db.insert(new Person("trigger", "Darwin", 5));
            db.flush();                                                                      // a flush runs the policy
            long deadline = System.currentTimeMillis() + 10_000;
            while (segments(db).stream().mapToInt(Segment::size).sum() > before / 2 + 1 && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(segments(db).stream().mapToInt(Segment::size).sum()).isLessThanOrEqualTo(before / 2 + 1);
            assertThat(db.from(Person.class).count()).isEqualTo(3001);
            assertThat(db.from(Person.class).filter(where(Person::getCity).eq("Sydney")).count()).isZero();
        }
        try (ObjectDB db = ObjectDB.open(dir, config())) {
            assertThat(db.from(Person.class).count()).isEqualTo(3001);
        }
    }

    @Test
    void garbageBelowTheRatioWaitsForCompact() {
        try (ObjectDB db = ObjectDB.open(tmp.resolve("db"), config().withCompactionGarbageRatio(0.9))) {
            load(db, 3000);
            int before = segments(db).stream().mapToInt(Segment::size).sum();
            db.from(Person.class).filter(where(Person::getAge).lt(9)).deleteAll();          // 10 %
            db.insert(new Person("trigger", "Darwin", 50));
            db.flush();
            assertThat(segments(db).stream().mapToInt(Segment::size).sum()).isEqualTo(before + 1);   // + the trigger
            db.compact();
            assertThat(segments(db).stream().mapToInt(Segment::size).sum()).isEqualTo(db.from(Person.class).count());
            for (SegmentView v : ((Database) db).views(Person.class.getName())) {
                assertThat(v.live(Long.MAX_VALUE).getCardinality()).isEqualTo(v.size());
            }
        }
    }
}
