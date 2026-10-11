package com.github.kookaburra.field;

import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.query.Pred;

/**
 * Base of generated metamodel classes for embedded paths, e.g. {@code Person_.home.city}.
 *
 * @param <T> entity type
 * @param <E> embedded value type
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public abstract class EmbeddedPath<T, E> {

    private final ObjectField<T, E> field;

    protected EmbeddedPath(Class<T> entity, String path) {
        this.field = Fields.objectField(entity, path);
        if (field.type() != ValueType.EMBEDDED) {
            throw new IllegalArgumentException(field + " is not an @Embeddable value");
        }
    }

    /** The embedded value itself as a field. */
    public ObjectField<T, E> field() {
        return field;
    }

    public Pred<T> isNull() {
        return field.isNull();
    }

    public Pred<T> isNotNull() {
        return field.isNotNull();
    }

    @Override
    public String toString() {
        return field.toString();
    }
}
