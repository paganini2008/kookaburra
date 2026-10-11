package com.github.kookaburra.face.model;

import com.github.kookaburra.dynamic.DynamicObject;

import java.util.List;

/**
 * One page of a collection: columns, objects, total match count and timing.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record Page(List<Column> columns, List<DynamicObject> rows, long total, int pageIndex, int pageSize, long millis) {

    public int pageCount() {
        return (int) Math.max(1, (total + pageSize - 1) / pageSize);
    }
}
