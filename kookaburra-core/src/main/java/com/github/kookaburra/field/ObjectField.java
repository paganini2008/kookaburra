package com.github.kookaburra.field;

import com.github.kookaburra.mapping.ValueType;

import java.io.Serializable;
import java.util.List;

/**
 * Field without a column of its own: an embedded object (navigate with {@code then}) or an
 * arbitrary row-only value (only {@code isNull} is available).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class ObjectField<T, V> extends AbstractField<T, V> {

    ObjectField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }

    public IntField<T> then(IntGetter<V> getter) {
        return Fields.expect(child(getter), IntField.class);
    }

    public LongField<T> then(LongGetter<V> getter) {
        return Fields.expect(child(getter), LongField.class);
    }

    public DoubleField<T> then(DoubleGetter<V> getter) {
        return Fields.expect(child(getter), DoubleField.class);
    }

    public BoolField<T> then(BoolGetter<V> getter) {
        return Fields.expect(child(getter), BoolField.class);
    }

    public StringField<T> then(StringGetter<V> getter) {
        return Fields.expect(child(getter), StringField.class);
    }

    public <C extends Comparable<? super C>> ComparableField<T, C> then(ComparableGetter<V, C> getter) {
        return Fields.expect(child(getter), ComparableField.class);
    }

    public <E> ListField<T, E> then(CollectionGetter<V, E> getter) {
        return Fields.expect(child(getter), ListField.class);
    }

    public <R> RefField<T, R> then(RefGetter<V, R> getter) {
        return Fields.expect(child(getter), RefField.class);
    }

    public <C> ObjectField<T, C> then(Getter<V, C> getter) {
        return Fields.expect(child(getter), ObjectField.class);
    }

    private AbstractField<T, ?> child(Serializable getter) {
        if (type() != ValueType.EMBEDDED) {
            throw new IllegalStateException(this + " is not an @Embeddable value; cannot navigate into it");
        }
        GetterResolver.PropertyRef ref = GetterResolver.resolve(getter);
        if (!ref.owner().isAssignableFrom(javaType())) {
            throw new IllegalArgumentException(ref.owner().getName() + "::" + ref.property()
                    + " is not a property of " + javaType().getName());
        }
        return Fields.of(entity(), path() + "." + ref.property());
    }
}
