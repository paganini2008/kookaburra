package com.github.kookaburra.exec;

import java.util.List;

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

    @Override
    void close();
}
