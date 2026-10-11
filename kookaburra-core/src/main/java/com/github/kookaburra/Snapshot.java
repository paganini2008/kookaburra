package com.github.kookaburra;

import com.github.kookaburra.query.QueryStream;

import java.util.Collection;
import java.util.Map;

/**
 * A consistent read view: every read sees the database as of {@link #seq()}. Holds resources until
 * closed. Not thread-safe.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface Snapshot extends AutoCloseable {

    /** Sequence number of the last write visible in this snapshot. */
    long seq();

    <T> T get(Class<T> type, long oid);

    <T> Map<Long, T> getAll(Class<T> type, Collection<Long> oids);

    <T> QueryStream<T> from(Class<T> type);

    @Override
    void close();
}
