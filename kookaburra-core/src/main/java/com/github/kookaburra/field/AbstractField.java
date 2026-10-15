package com.github.kookaburra.field;

import com.github.kookaburra.mapping.ValueType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

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
        return viaList() ? ValueType.MULTI : leaf().type();
    }

    /** True if the path runs through a list of embedded objects (values of every element). */
    final boolean viaList() {
        for (int i = 0; i < chain.size() - 1; i++) {
            if (chain.get(i).type() == ValueType.EMBEDDED_LIST) {
                return true;
            }
        }
        return false;
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
        return (V) ValueType.normalize(read(entity, 0));
    }

    /** Follows the chain from {@code from}; through an embedded list it collects the values of every element. */
    private Object read(Object cur, int from) {
        for (int i = from; i < chain.size(); i++) {
            if (cur == null) {
                return null;
            }
            if (i > 0 && chain.get(i - 1).type() == ValueType.EMBEDDED_LIST && cur instanceof Collection<?> elements) {
                List<Object> out = new ArrayList<>();
                for (Object e : elements) {
                    Object v = read(e, i);
                    if (v instanceof Collection<?> c) {
                        c.stream().filter(Objects::nonNull).forEach(out::add);
                    } else if (v != null) {
                        out.add(v);
                    }
                }
                return out;
            }
            cur = chain.get(i).read(cur);
        }
        return cur;
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
