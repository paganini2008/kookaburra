package com.github.kookaburra.field;

import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.query.Pred;

/**
 * A queryable property path of an entity. Obtain via the generated metamodel ({@code Person_.age})
 * or a getter reference ({@code where(Person::getAge)}); both yield the same instance.
 *
 * @param <T> entity type
 * @param <V> value type (canonical: narrow ints as {@code Integer}, floats as {@code Double})
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface Field<T, V> {

    Class<T> entity();

    /** Dotted path, e.g. {@code age} or {@code home.city}. */
    String path();

    ValueType type();

    /** Reads the value; {@code null} if it or any embedded object on the path is null. */
    V get(T entity);

    default Pred<T> isNull() {
        return new Pred.IsNull<>(this);
    }

    default Pred<T> isNotNull() {
        return new Pred.Not<>(isNull());
    }
}
