package com.github.kookaburra.mapping;

import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.OnDelete;

import java.util.Objects;

/**
 * A stored field.
 *
 * @param name     Java field name
 * @param type     stored type
 * @param index    index kind, or {@code null} when the field has no column
 * @param unique   {@code @Unique}: no two live objects share a non-null value
 * @param onDelete {@code @ForeignKey} action, or null when the reference is not checked
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record FieldDef(String name, TypeDef type, IndexKind index, boolean unique, OnDelete onDelete) {

    public FieldDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    /** A field without constraints. */
    public FieldDef(String name, TypeDef type, IndexKind index) {
        this(name, type, index, false, null);
    }

    @Override
    public String toString() {
        return name + " " + type + (index == null ? "" : " @" + index) + (unique ? " UNIQUE" : "")
                + (onDelete == null ? "" : " FOREIGN KEY ON DELETE " + onDelete.name().replace('_', ' '));
    }
}
