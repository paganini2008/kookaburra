package com.github.kookaburra.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The former name(s) of a renamed field or entity class, so data stored under the old name stays readable
 * (as Morphia's {@code @AlsoLoad}, ObjectBox's {@code @Uid} or a Realm migration rename).
 *
 * <ul>
 *   <li>On a field (of an entity or an {@code @Embeddable}): the field's earlier name. Rows written before keep their
 *   values; queries, indexes and constraints use the new name.</li>
 *   <li>On an entity class: its earlier fully qualified class name. The class keeps its objects, OIDs, collection and
 *   references; the old name still finds it (OQL, dynamic access, references written before).</li>
 * </ul>
 * Several former names may be given (renamed more than once). The annotation can stay; it only acts once.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface Renamed {

    /** Former names: field names, or fully qualified class names. */
    String[] value();
}
