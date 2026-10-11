package com.github.kookaburra.face.model;

import com.github.kookaburra.mapping.ValueType;

/**
 * A data-grid column: a stored path (embedded paths flattened), or the {@code oid} / {@code version} columns.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record Column(String path, ValueType type, boolean sortable, boolean system) {
}
