package com.github.kookaburra.element;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Every {@link ElementMatchTest} case on disk: flushed, closed and reopened before the queries.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class ElementMatchDiskTest extends ElementMatchTest {

    @TempDir
    Path dir;

    @Override
    protected ObjectDB create() {
        return ObjectDB.open(dir.resolve("db"), Config.defaults());
    }

    @Override
    protected void checkpoint() {
        db.flush();
        db.close();
        db = ObjectDB.open(dir.resolve("db"), Config.defaults());
    }
}
