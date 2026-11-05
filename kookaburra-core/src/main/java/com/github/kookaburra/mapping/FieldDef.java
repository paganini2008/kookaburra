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
 * @param comment  {@code @Comment} text, or null
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record FieldDef(String name, TypeDef type, IndexKind index, boolean unique, OnDelete onDelete, String comment) {

    public FieldDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }

    /** A field without a comment. */
    public FieldDef(String name, TypeDef type, IndexKind index, boolean unique, OnDelete onDelete) {
        this(name, type, index, unique, onDelete, null);
    }

    /** The same field with another comment. */
    public FieldDef withComment(String c) {
        return new FieldDef(name, type, index, unique, onDelete, c);
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
