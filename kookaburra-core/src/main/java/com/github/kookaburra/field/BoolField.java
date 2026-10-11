package com.github.kookaburra.field;

import com.github.kookaburra.query.Pred;

import java.util.List;

/**
 * Field of a {@code boolean}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class BoolField<T> extends ComparableField<T, Boolean> {

    BoolField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }

    public Pred<T> isTrue() {
        return eq(Boolean.TRUE);
    }

    public Pred<T> isFalse() {
        return eq(Boolean.FALSE);
    }
}
