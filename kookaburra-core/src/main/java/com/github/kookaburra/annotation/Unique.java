package com.github.kookaburra.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Unique constraint: no two live objects of the declaring class (and its subclasses) may have the same non-null
 * value. Nulls are not compared (any number of objects may have none), like SQL {@code UNIQUE}. Decimals compare
 * by numeric value. Implies {@link Index} ({@code AUTO}) when the field has none. Scalar fields only (also inside
 * {@link Embeddable}s, not inside embedded lists).
 * <p>
 * Checked when the write commits, against the latest committed state and the other writes of the same batch or
 * transaction; a violation throws {@link com.github.kookaburra.ConstraintViolationException} and nothing is
 * written. Adding {@code @Unique} to a field that already holds duplicates fails the class registration.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Unique {
}
