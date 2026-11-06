package com.github.kookaburra.engine;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.model.Person;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cleanup after a commit must not delete the temporary file of a segment that a flush or merge thread is writing
 * at that moment (CI failure "cannot rename …/seg_10.odbf.tmp", the database then failed). A temporary file is
 * judged by the file it becomes: kept while its segment is being built, deleted when stale (at open, or once the
 * segment is not in use). Plus a stress run: many flushes and merges while committing.
 *
 * @Author: Fred Feng
 * @Date: 06/11/2026
 * @Version 1.0.0
 */
class CleanupRaceTest {

    @TempDir
    Path dir;

    @Test
    void temporaryFileOfASegmentBeingBuiltSurvives() throws Exception {
        Path db = dir.resolve("db");
        Path col = db.resolve("col").resolve("Person");
        try (DiskStorage storage = new DiskStorage(db)) {
            Files.createDirectories(col);
            Path building = Files.write(col.resolve("seg_10.odbf.tmp"), new byte[]{1});      // being written
            Path stale = Files.write(col.resolve("seg_7.odbf.tmp"), new byte[]{1});         // left by a crash
            Path staleDel = Files.write(col.resolve("seg_3_2.del.tmp"), new byte[]{1});
            Path other = Files.write(col.resolve("junk.tmp"), new byte[]{1});
            Path unused = Files.write(col.resolve("seg_5.odbf"), new byte[]{1});
            Path live = Files.write(col.resolve("seg_9.odbf"), new byte[]{1});
            Path liveDel = Files.write(col.resolve("seg_9_1.del"), new byte[]{1});
            Commit c = new Commit(1, 0, 11, Map.of("Person", new Commit.CollectionState(0,
                    List.of(new Commit.SegmentRef(9, 1)))), Map.of());

            storage.cleanup(c, Set.of(9, 10));                       // seg 10 is building
            assertThat(building).exists();
            assertThat(live).exists();
            assertThat(liveDel).exists();
            assertThat(stale).doesNotExist();
            assertThat(staleDel).doesNotExist();
            assertThat(other).doesNotExist();
            assertThat(unused).doesNotExist();

            storage.cleanup(c, Set.of(9));                           // no longer in use: removed
            assertThat(building).doesNotExist();
            assertThat(live).exists();
        }
    }

    @Test
    void segmentWrittenFromTheFirstBytesOfABuffer() throws Exception {
        byte[] buf = {1, 2, 3, 4, 5, 0, 0, 0};                    // a writer's buffer: valid up to 5
        try (DiskStorage disk = new DiskStorage(dir.resolve("disk"))) {
            disk.writeSegment("C", 3, buf, 5).close();
            assertThat(Files.readAllBytes(dir.resolve("disk/col/C/seg_3.odbf"))).containsExactly(1, 2, 3, 4, 5);
            assertThat(dir.resolve("disk/col/C/seg_3.odbf.tmp")).doesNotExist();
        }
        try (MemoryStorage mem = new MemoryStorage()) {
            assertThat(mem.writeSegment("C", 4, buf, 5).size()).isEqualTo(5);
            assertThat(mem.writeSegment("C", 5, buf, buf.length).size()).isEqualTo(8);
        }
    }

    @Test
    void flushesAndMergesWhileCommitting() {
        Config config = Config.defaults().withMemSegmentBytes(32 * 1024).withCompactionFanIn(2).withMaxSegments(3);
        try (ObjectDB db = ObjectDB.open(dir.resolve("stress"), config)) {
            for (int round = 0; round < 12; round++) {
                for (int i = 0; i < 200; i++) {
                    db.insert(new Person("p" + round + "-" + i, "C" + (i % 7), i % 90));
                }
                if (round % 3 == 0) {
                    db.flush();
                }
            }
            db.flush();
            db.compact();
            assertThat(db.from(Person.class).count()).isEqualTo(2400);
        }
        try (ObjectDB db = ObjectDB.open(dir.resolve("stress"), config)) {
            assertThat(db.from(Person.class).count()).isEqualTo(2400);
        }
    }
}
