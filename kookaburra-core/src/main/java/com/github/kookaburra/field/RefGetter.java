package com.github.kookaburra.field;

import com.github.kookaburra.Ref;

import java.io.Serializable;

/**
 * Getter of a reference property. Must be a method reference to a property getter, e.g. {@code Person::getAge}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@FunctionalInterface
public interface RefGetter<T, R> extends Serializable {
    Ref<R> get(T entity);
}
