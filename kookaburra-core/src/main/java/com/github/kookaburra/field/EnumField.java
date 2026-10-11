package com.github.kookaburra.field;

import java.util.List;

/**
 * Field of an enum; ordered by declaration order.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class EnumField<T, E extends Enum<E>> extends ComparableField<T, E> {

    EnumField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }
}
