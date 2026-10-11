package com.github.kookaburra.field;

import java.io.Serializable;
import java.util.Collection;

/**
 * Getter of a multi-valued property ({@code List} / {@code Set}). Must be a method reference to a property getter, e.g. {@code Person::getAge}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@FunctionalInterface
public interface CollectionGetter<T, E> extends Serializable {
    Collection<E> get(T entity);
}
