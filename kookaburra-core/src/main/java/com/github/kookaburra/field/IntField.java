package com.github.kookaburra.field;

import java.util.List;

/**
 * Field of an {@code int}-like (int, short, byte, char) field.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class IntField<T> extends ComparableField<T, Integer> {

    IntField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }
}
