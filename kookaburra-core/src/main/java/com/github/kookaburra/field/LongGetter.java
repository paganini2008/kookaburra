package com.github.kookaburra.field;

import java.io.Serializable;

/**
 * Getter of a {@code long} property. Must be a method reference to a property getter, e.g. {@code Person::getAge}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@FunctionalInterface
public interface LongGetter<T> extends Serializable {
    long get(T entity);
}
