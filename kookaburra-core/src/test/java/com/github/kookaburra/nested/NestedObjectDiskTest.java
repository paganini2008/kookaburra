package com.github.kookaburra.nested;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Every {@link NestedObjectTest} case on disk: at each checkpoint the data is flushed into {@code .odbf} segments,
 * compacted, and the database is closed and reopened (WAL replay + segment reads).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class NestedObjectDiskTest extends NestedObjectTest {

    @TempDir
    Path dir;

    private Config config() {
        return Config.defaults().withTransactions(true).withMemSegmentBytes(4096);
    }

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
}
