package com.github.kookaburra.field;

import com.github.kookaburra.query.Pred;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Multi-valued field ({@code List} / {@code Set} of a scalar type).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class ListField<T, E> extends AbstractField<T, Collection<E>> {

    ListField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }

    /** Java class of the elements. */
    public Class<?> elementType() {
        return leaf().elementType();
    }

    public Pred<T> contains(E value) {
        return new Pred.Contains<>(this, new LinkedHashSet<>(Collections.singletonList(value)), false);
    }

    @SafeVarargs
    public final Pred<T> containsAny(E... values) {
        return new Pred.Contains<>(this, new LinkedHashSet<>(Arrays.asList(values)), false);
    }

    @SafeVarargs
    public final Pred<T> containsAll(E... values) {
        return new Pred.Contains<>(this, new LinkedHashSet<>(Arrays.asList(values)), true);
    }
}
