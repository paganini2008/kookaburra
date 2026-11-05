package com.github.kookaburra.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A description of an entity class, an embeddable or a field, kept in the database's schema (as Hibernate 6's
 * {@code @Comment}, SQL's {@code COMMENT ON TABLE / COLUMN} or MySQL's {@code COMMENT}): shown in Studio, readable
 * through the dynamic API and written into exported Java code. Changing it does not change the stored layout.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Comment {

    /** The description. */
    String value();
}
