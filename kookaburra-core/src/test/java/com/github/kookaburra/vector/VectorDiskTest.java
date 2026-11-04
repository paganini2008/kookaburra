package com.github.kookaburra.vector;

import com.github.kookaburra.ObjectDB;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Every {@link VectorTest} case on disk: each checkpoint flushes into {@code .odbf} segments (vectors read back from
 * rows, fields after them skipped in place), compacts, closes and reopens the database.
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
class VectorDiskTest extends VectorTest {

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
