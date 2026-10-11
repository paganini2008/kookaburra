package com.github.kookaburra.engine;

import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.Snapshot;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.query.QueryStream;

import java.util.Collection;
import java.util.Map;

/**
 * Explicit snapshot: one {@link ReadView} shared by all reads until closed.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class SnapshotImpl implements Snapshot {

    private final Database db;
    private final ReadView view;
    private boolean closed;

    SnapshotImpl(Database db, ReadView view) {
        this.db = db;
        this.view = view;
    }

    @Override
    public long seq() {
        return view.seq();
    }

    @Override
    public <T> T get(Class<T> type, long oid) {
        check();
        return db.get(view, type, oid);
    }

    @Override
    public <T> Map<Long, T> getAll(Class<T> type, Collection<Long> oids) {
        check();
        return db.getAll(view, type, oids);
    }

    @Override
    public <T> QueryStream<T> from(Class<T> type) {
        check();
        return new QueryStream<>(db, type, view);
    }

    private void check() {
        if (closed) {
            throw new KookaburraException("snapshot is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            view.close();
        }
    }
}
