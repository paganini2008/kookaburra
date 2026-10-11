package com.github.kookaburra.query;

import com.github.kookaburra.field.Field;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A result row of a grouped / aggregated query: named columns (group keys by path, aggregates by name).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public final class Row {

    private final List<String> names;
    private final Object[] values;

    public Row(List<String> names, Object[] values) {
        if (names.size() != values.length) {
            throw new IllegalArgumentException("names and values differ in length");
        }
        this.names = List.copyOf(names);
        this.values = values.clone();
    }

    public int size() {
        return values.length;
    }

    public List<String> names() {
        return names;
    }

    public Object get(int i) {
        return values[i];
    }

    public Object get(String name) {
        int i = names.indexOf(name);
        if (i < 0) {
            throw new NoSuchElementException("no column '" + name + "' in " + names);
        }
        return values[i];
    }

    public Object get(Agg<?> agg) {
        return get(agg.name());
    }

    @SuppressWarnings("unchecked")
    public <V> V get(Field<?, V> field) {
        return (V) get(field.path());
    }

    /** Numeric column as long (counts, integer sums). */
    public long getLong(String name) {
        return ((Number) get(name)).longValue();
    }

    public double getDouble(String name) {
        return ((Number) get(name)).doubleValue();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Row r && r.names.equals(names) && Arrays.equals(r.values, values);
    }

    @Override
    public int hashCode() {
        return names.hashCode() * 31 + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(names.get(i)).append('=').append(values[i]);
        }
        return sb.append('}').toString();
    }
}
