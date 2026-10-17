package com.github.kookaburra.stats;

import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.DatabaseStats;
import com.github.kookaburra.model.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@link StatsTest} case on disk (segment files, WAL); counters start again after a reopen, object counts
 * and storage do not.
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
class StatsDiskTest extends StatsTest {

    @TempDir
    Path dir;

    @Override
    protected ObjectDB create() {
        return ObjectDB.open(dir.resolve("db"), config());
    }

    @Override
    protected void checkpoint() {
        db.flush();
        db.compact();
        db.close();
        db = ObjectDB.open(dir.resolve("db"), config());
    }

    @Test
    void walReplayedAfterReopen() {
        db.insert(new Team("red"));
        db.close();
        db = ObjectDB.open(dir.resolve("db"), config());
        DatabaseStats s = db.stats();
        assertThat(s.writes().walReplayed()).isEqualTo(1);
        assertThat(s.writes().batches()).isZero();
        assertThat(s.objects()).isEqualTo(1);
    }
}
