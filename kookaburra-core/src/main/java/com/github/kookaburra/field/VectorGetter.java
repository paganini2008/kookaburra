package com.github.kookaburra.field;

import java.io.Serializable;

/**
 * Getter of a {@code float[]} vector property. Must be a method reference to a property getter, e.g.
 * {@code Doc::getEmbedding}.
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
@FunctionalInterface
public interface VectorGetter<T> extends Serializable {
    float[] get(T entity);
}
