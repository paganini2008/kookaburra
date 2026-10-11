package com.github.kookaburra.field;

import com.github.kookaburra.mapping.ValueType;

import java.util.List;

/**
 * Base of all field implementations; instances are interned per (entity, path) by {@link Fields}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public abstract class AbstractField<T, V> implements Field<T, V> {

    private final Class<T> entity;
    private final String path;
    private final List<Property> chain;

    AbstractField(Class<T> entity, String path, List<Property> chain) {
        this.entity = entity;
        this.path = path;
        this.chain = List.copyOf(chain);
    }

    @Override
    public final Class<T> entity() {
        return entity;
    }

    @Override
    public final String path() {
        return path;
    }

    @Override
    public final ValueType type() {
        return leaf().type();
    }

    /** Declared Java type of the value at the end of the path. */
    public final Class<?> javaType() {
        return leaf().javaType();
    }

    /** Properties along the path, outermost first. */
    public final List<Property> chain() {
        return chain;
    }

    final Property leaf() {
        return chain.get(chain.size() - 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public final V get(T entity) {
        Object cur = entity;
        for (Property p : chain) {
            if (cur == null) {
                return null;
            }
            cur = p.read(cur);
        }
        return (V) ValueType.normalize(cur);
    }

    @Override
    public final boolean equals(Object o) {
        return o instanceof AbstractField<?, ?> f && f.entity == entity && f.path.equals(path);
    }

    @Override
    public final int hashCode() {
        return entity.hashCode() * 31 + path.hashCode();
    }

    @Override
    public final String toString() {
        return entity.getSimpleName() + "." + path;
    }
}
