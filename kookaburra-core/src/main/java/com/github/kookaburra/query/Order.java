package com.github.kookaburra.query;

import com.github.kookaburra.field.Field;
import com.github.kookaburra.mapping.ValueType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A sort order over fields, usable as a plain {@link Comparator}. Default null placement:
 * {@code asc()} puts nulls last, {@code desc()} puts nulls first (exact reverse of each other).
 *
 * @param <T> entity type
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public final class Order<T> implements Comparator<T> {

    /** One sort key. */
    public record Key<T>(Field<T, ?> field, boolean descending, boolean nullsFirst) {
        public Key {
            Objects.requireNonNull(field, "field");
            if (!field.type().isScalar()) {
                throw new IllegalArgumentException("Cannot sort by " + field.type() + " field " + field.path());
            }
        }

        Key<T> reversed() {
            return new Key<>(field, !descending, !nullsFirst);
        }

        @Override
        public String toString() {
            return field.path() + (descending ? " DESC" : " ASC") + (nullsFirst ? " NULLS FIRST" : " NULLS LAST");
        }
    }

    private final List<Key<T>> keys;

    private Order(List<Key<T>> keys) {
        this.keys = List.copyOf(keys);
    }

    public static <T> Order<T> asc(Field<T, ?> field) {
        return new Order<>(List.of(new Key<>(field, false, false)));
    }

    public static <T> Order<T> desc(Field<T, ?> field) {
        return new Order<>(List.of(new Key<>(field, true, true)));
    }

    public List<Key<T>> keys() {
        return keys;
    }

    /** Places nulls of the last key first. */
    public Order<T> nullsFirst() {
        return withLastNulls(true);
    }

    /** Places nulls of the last key last. */
    public Order<T> nullsLast() {
        return withLastNulls(false);
    }

    private Order<T> withLastNulls(boolean first) {
        List<Key<T>> k = new ArrayList<>(keys);
        Key<T> last = k.remove(k.size() - 1);
        k.add(new Key<>(last.field(), last.descending(), first));
        return new Order<>(k);
    }

    /** Appends the keys of {@code next}. */
    @SuppressWarnings("unchecked")
    public Order<T> thenComparing(Order<? super T> next) {
        List<Key<T>> k = new ArrayList<>(keys);
        for (Key<? super T> key : next.keys) {
            k.add((Key<T>) key);
        }
        return new Order<>(k);
    }

    @Override
    public Order<T> reversed() {
        return new Order<>(keys.stream().map(Key::reversed).toList());
    }

    @Override
    public int compare(T a, T b) {
        for (Key<T> key : keys) {
            Object va = key.field().get(a);
            Object vb = key.field().get(b);
            int c;
            if (va == null || vb == null) {
                if (va == vb) {
                    continue;
                }
                c = (va == null) == key.nullsFirst() ? -1 : 1;
            } else {
                c = ValueType.compare(va, vb);
                if (key.descending()) {
                    c = -c;
                }
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Order<?> other && other.keys.equals(keys);
    }

    @Override
    public int hashCode() {
        return keys.hashCode();
    }

    @Override
    public String toString() {
        return keys.stream().map(Key::toString).collect(Collectors.joining(", "));
    }
}
