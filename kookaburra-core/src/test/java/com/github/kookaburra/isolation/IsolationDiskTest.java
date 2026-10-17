package com.github.kookaburra.isolation;

import com.github.kookaburra.ObjectDB;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Every {@link IsolationTest} case on disk: each checkpoint flushes into {@code .odbf} segments,
 * compacts, closes and reopens the database.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class IsolationDiskTest extends IsolationTest {

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
}
