package com.github.kookaburra.engine;

import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.WriteBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WriteBatch} implementation: ops are encoded when queued and submitted as one sequencer request.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Batch implements WriteBatch {

    private final Database db;
    private final List<WriteOp> ops = new ArrayList<>();
    private boolean done;

    Batch(Database db) {
        this.db = db;
    }

    @Override
    public WriteBatch insert(Object entity) {
        check();
        ops.add(db.insertOp(entity));
        return this;
    }

    @Override
    public WriteBatch update(Object entity) {
        check();
        ops.add(db.updateOp(entity));
        return this;
    }

    @Override
    public WriteBatch delete(Object entity) {
        check();
        ops.add(db.deleteOp(entity));
        return this;
    }

    @Override
    public WriteBatch delete(Class<?> type, long oid) {
        check();
        ops.add(db.deleteOp(type, oid));
        return this;
    }

    @Override
    public WriteBatch delete(long oid) {
        check();
        ops.add(new WriteOp.Delete(oid, WriteOp.ANY, null, null));
        return this;
    }

    @Override
    public int size() {
        return ops.size();
    }

    @Override
    public void commit() {
        check();
        done = true;
        if (!ops.isEmpty()) {
            db.write(ops);
        }
    }

    private void check() {
        if (done) {
            throw new KookaburraException("batch already committed or closed");
        }
    }

    @Override
    public void close() {
        done = true;
    }
}
