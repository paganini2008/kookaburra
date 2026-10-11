package com.github.kookaburra.query;

import java.util.Iterator;

/**
 * Lazily materializing iterator over query results; holds its snapshot until closed or exhausted.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface Cursor<T> extends Iterator<T>, AutoCloseable {
    @Override
    void close();
}
