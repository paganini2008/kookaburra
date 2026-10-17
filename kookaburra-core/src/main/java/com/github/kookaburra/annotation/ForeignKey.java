package com.github.kookaburra.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Referential integrity for a {@code Ref<T>} field or a {@code List / Set<Ref<T>>} field: every non-null reference
 * must point to an existing object of {@code T} (or a subclass), and deleting a referenced object applies
 * {@link #onDelete()}. Implies {@link Index} ({@code AUTO}), so referencing objects are found through the index
 * ({@link com.github.kookaburra.ObjectDB#referrers}). Also allowed inside {@link Embeddable}s, not inside embedded
 * lists.
 * <p>
 * Checked when the write commits (deferred inside transactions, like {@code DEFERRABLE INITIALLY DEFERRED});
 * cascaded deletes and cleared references become part of the same atomic commit. A violation throws
 * {@link com.github.kookaburra.ConstraintViolationException}. Cascades are storage-level, like SQL: they do not run
 * lifecycle callbacks.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ForeignKey {

    OnDelete onDelete() default OnDelete.RESTRICT;
}
