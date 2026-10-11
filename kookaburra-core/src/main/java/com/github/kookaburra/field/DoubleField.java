package com.github.kookaburra.field;

import java.util.List;

/**
 * Field of a {@code double} / {@code float} field.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class DoubleField<T> extends ComparableField<T, Double> {

    DoubleField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }
}
