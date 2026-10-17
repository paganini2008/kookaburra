package com.github.kookaburra.backup;

import com.github.kookaburra.ObjectDB;

/**
 * Every {@link BackupTest} case on disk: part of the data is flushed into segment files before the backup, so the
 * backup merges segments and memtables.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class BackupDiskTest extends BackupTest {

    @Override
    protected ObjectDB create() {
        return ObjectDB.open(tmp.resolve("db"), config());
    }

    @Override
    protected void checkpoint() {
        db.flush();
    }
}
