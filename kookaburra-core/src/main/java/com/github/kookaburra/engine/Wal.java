package com.github.kookaburra.engine;

import java.util.List;

/**
 * Write-ahead log used by the sequencer. The in-memory database uses {@link #NONE}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
interface Wal extends AutoCloseable {

    /** Appends one atomic batch with its sequence number. */
    void append(long seq, List<WriteOp> ops, long[] results);

    /** Makes appended records durable. */
    void sync();

    @Override
    void close();

    Wal NONE = new Wal() {
        @Override
        public void append(long seq, List<WriteOp> ops, long[] results) {
        }

        @Override
        public void sync() {
        }

        @Override
        public void close() {
        }
    };
}
