package com.github.kookaburra.mapping;

import com.github.kookaburra.annotation.IndexKind;

import java.util.Objects;

/**
 * A stored field.
 *
 * @param name  Java field name
 * @param type  stored type
 * @param index index kind, or {@code null} when the field has no column
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record FieldDef(String name, TypeDef type, IndexKind index) {

    public FieldDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    @Override
    public String toString() {
        return name + " " + type + (index == null ? "" : " @" + index);
    }
}
