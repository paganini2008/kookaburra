package com.github.kookaburra.dynamic;

import com.github.kookaburra.mapping.Record;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A stored object without its Java class (design §9.8): class name, OID, version and field values.
 * Values: scalars as Java values (enums as constant names, references as {@link com.github.kookaburra.Ref}),
 * embedded objects as nested {@code Map}s, collections as {@code List}s. Not thread-safe.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public final class DynamicObject {

    private final String className;
    private long oid;
    private long version;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public DynamicObject(String className) {
        this.className = Objects.requireNonNull(className);
    }

    public String className() {
        return className;
    }

    /** OID, 0 before the first insert. */
    public long oid() {
        return oid;
    }

    /** Version, 0 before the first insert. */
    public long version() {
        return version;
    }

    /** Internal: set by the database after a write or when reading. */
    public void assign(long oid, long version) {
        this.oid = oid;
        this.version = version;
    }

    /** Top-level values (embedded objects are nested maps). */
    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    /** Value at a dotted path through embedded maps; null if absent. */
    public Object get(String path) {
        return get(values, path.split("\\."), 0);
    }

    /** Through a list of embedded objects the values of every element are collected ("any element"). */
    private static Object get(Object cur, String[] parts, int from) {
        for (int i = from; i < parts.length; i++) {
            if (cur instanceof List<?> elements && !elements.isEmpty() && elements.get(0) instanceof Map) {
                List<Object> out = new ArrayList<>();
                for (Object e : elements) {
                    Object v = get(e, parts, i);
                    if (v instanceof List<?> l) {
                        l.stream().filter(Objects::nonNull).forEach(out::add);
                    } else if (v != null) {
                        out.add(v);
                    }
                }
                return out;
            }
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(parts[i]);
        }
        return cur;
    }

    /** Sets a value at a dotted path, creating embedded maps on the way. */
    @SuppressWarnings("unchecked")
    public DynamicObject set(String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cur = values;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(parts[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(parts[parts.length - 1], value);
        return this;
    }

    /** Removes a top-level value. */
    public DynamicObject remove(String field) {
        values.remove(field);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DynamicObject d && d.className.equals(className) && d.oid == oid && d.version == version
                && Record.deepEquals(d.values, values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, oid);
    }

    @Override
    public String toString() {
        return className.substring(className.lastIndexOf('.') + 1) + "#" + oid + "v" + version + values;
    }
}
