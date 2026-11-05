package com.github.kookaburra.vector;

import com.github.kookaburra.ObjectDB;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Every {@link HnswTest} case on disk: graphs and vector columns are read from mapped segment files after a
 * close and reopen.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class HnswDiskTest extends HnswTest {

    @TempDir
    Path dir;

    @Override
    protected ObjectDB create() {
        return ObjectDB.open(dir.resolve("db"), config());
    }

    @Override
    protected void reopen() {
        db.close();
        db = ObjectDB.open(dir.resolve("db"), config());
    }
}
