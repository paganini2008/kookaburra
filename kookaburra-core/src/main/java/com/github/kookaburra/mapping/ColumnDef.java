package com.github.kookaburra.mapping;

import com.github.kookaburra.annotation.IndexKind;

/**
 * An indexed path of a collection (union over all classes of the collection).
 *
 * @param path dotted path, e.g. {@code home.city}
 * @param type scalar or MULTI type
 * @param kind index kind
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record ColumnDef(String path, TypeDef type, IndexKind kind) {

    public boolean multi() {
        return type.kind() == ValueType.MULTI;
    }

    /** Type the values are compared by (element type for MULTI). */
    public TypeDef scalarType() {
        return type.scalarType();
    }
}
