package com.github.kookaburra.engine;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Every {@link TransactionTest} case on a disk database: WAL, group commit and small MemSegments, so snapshots and
 * overlays span flushed {@code .odbf} segment files while the transactions run.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class TransactionDiskTest extends TransactionTest {

    @TempDir
    Path dir;

    @Override
    protected ObjectDB create() {
        return ObjectDB.open(dir.resolve("db"), Config.defaults().withTransactions(true).withMemSegmentBytes(8192));
    }
}
