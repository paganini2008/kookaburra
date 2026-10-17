package com.github.kookaburra.annotation;

/**
 * What happens to objects that reference a deleted object through a {@link ForeignKey} field (SQL
 * {@code ON DELETE}).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
public enum OnDelete {
    /** The delete fails while referencing objects exist (SQL {@code RESTRICT} / {@code NO ACTION}). */
    RESTRICT,
    /** Referencing objects are deleted too, in the same atomic commit (recursively). */
    CASCADE,
    /** The reference is cleared: a {@code Ref} field becomes null, the element is removed from a collection. */
    SET_NULL
}
