package com.github.kookaburra.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Unique constraint, on one field or on a combination of fields.
 * <ul>
 *   <li><b>On a field</b> ({@code @Unique String email}): no two live objects of the declaring class (and its
 *       subclasses) may have the same non-null value. Implies {@link Index} ({@code AUTO}) when the field has none.
 *       Scalar fields only (also inside {@link Embeddable}s, not inside embedded lists).</li>
 *   <li><b>On the entity class</b>, repeatable ({@code @Unique(fields = {"tenant", "code"})}): no two live objects
 *       may have the same combination of values; an object with a null in any of the fields is not compared (SQL
 *       standard, PostgreSQL's default). Fields are scalar paths of the class or its superclasses, dotted for
 *       embedded values ({@code "address.city"}). The first field is indexed ({@code AUTO}) when none of them is.</li>
 * </ul>
 * Values compare like the database does: decimals by numeric value, references by OID.
 * <p>
 * Checked when the write commits, against the latest committed state and the other writes of the same batch or
 * transaction; a violation throws {@link com.github.kookaburra.ConstraintViolationException} and nothing is
 * written. Adding a constraint to data that already holds duplicates fails the class registration.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
@Repeatable(Unique.List.class)
public @interface Unique {

    /** Class level: the field paths whose combination must be unique (at least one). Empty on a field. */
    String[] fields() default {};

    /** Optional constraint name shown in errors and tools (default: {@code Class(field, field)}). */
    String name() default "";

    /** Container of repeated class-level {@link Unique}s. */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        Unique[] value();
    }
}
