package com.github.kookaburra.vector;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Every {@link DiverseRerankTest} case on disk: candidates and their vectors and texts come from segment files.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
class DiverseRerankDiskTest extends DiverseRerankTest {

    @TempDir
    Path dir;

    @Override
    protected ObjectDB create(Config config) {
        return ObjectDB.open(dir.resolve("db"), config);
    }

    @Override
    protected void checkpoint() {
        db.flush();
    }
}
