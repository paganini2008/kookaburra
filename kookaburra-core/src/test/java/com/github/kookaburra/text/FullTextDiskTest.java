package com.github.kookaburra.text;

import com.github.kookaburra.ObjectDB;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Every {@link FullTextTest} case on disk: inverted indexes read from segment files after a flush, close and reopen.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class FullTextDiskTest extends FullTextTest {

    @TempDir
    Path dir;

    @Override
    protected ObjectDB create() {
        return ObjectDB.open(dir.resolve("db"), config());
    }

    @Override
    protected void checkpoint() {
        db.flush();
        db.close();
        db = ObjectDB.open(dir.resolve("db"), config());
    }
}
