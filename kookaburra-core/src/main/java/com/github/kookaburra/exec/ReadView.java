package com.github.kookaburra.exec;

import java.util.List;
import java.util.Set;

/**
 * A snapshot as seen by the executor: segment views per collection at a sequence number.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface ReadView extends AutoCloseable {

    long seq();

    /** Segment views of a collection, newest first; empty if the collection has no data. */
    List<SegmentView> views(String collection);

    /**
     * Views for a query (the executor's {@code types} and condition); a SERIALIZABLE transaction records them as
     * its read set.
     */
    default List<SegmentView> queryViews(String collection, Set<Integer> types, Cond cond, Executor.Materializer materializer) {
        return views(collection);
    }

    /** Views for a lookup of one object by OID (recorded by SERIALIZABLE transactions). */
    default List<SegmentView> pointViews(String collection, long oid) {
        return views(collection);
    }

    @Override
    void close();
}
