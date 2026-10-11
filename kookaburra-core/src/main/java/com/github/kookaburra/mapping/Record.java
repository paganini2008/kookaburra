package com.github.kookaburra.mapping;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical, class-independent form of one stored object's fields (design §3.3): values follow the
 * fields of {@code layout}; embedded values are {@code Object[]}, collections {@code List}, maps
 * {@code LinkedHashMap}, enums their constant name, refs the target OID, OBJECT the serialized bytes.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public final class Record {

    private final int classId;
    private final Layout layout;
    private final Object[] values;

    public Record(int classId, Layout layout, Object[] values) {
        if (values.length != layout.fields().size()) {
            throw new IllegalArgumentException("expected " + layout.fields().size() + " values, got " + values.length);
        }
        this.classId = classId;
        this.layout = layout;
        this.values = values;
    }

    public int classId() {
        return classId;
    }

    public Layout layout() {
        return layout;
    }

    /** Value of top-level field {@code i}. */
    public Object value(int i) {
        return values[i];
    }

    /** Backing array; callers must not modify it. */
    public Object[] values() {
        return values;
    }

    /**
     * Value at a dotted path through embedded values, or null if any step is null or the path does not
     * exist in this record's layout.
     */
    public Object get(String path) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        int i = layout.indexOf(head);
        if (i < 0) {
            return null;
        }
        Object v = values[i];
        if (dot < 0) {
            return v;
        }
        TypeDef t = layout.fields().get(i).type();
        String rest = path.substring(dot + 1);
        while (v != null) {
            if (t.kind() != ValueType.EMBEDDED) {
                return null;
            }
            dot = rest.indexOf('.');
            head = dot < 0 ? rest : rest.substring(0, dot);
            int j = indexOf(t, head);
            if (j < 0) {
                return null;
            }
            v = ((Object[]) v)[j];
            if (dot < 0) {
                return v;
            }
            t = t.fields().get(j).type();
            rest = rest.substring(dot + 1);
        }
        return null;
    }

    private static int indexOf(TypeDef t, String name) {
        for (int i = 0; i < t.fields().size(); i++) {
            if (t.fields().get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Record r && r.classId == classId && r.layout.equals(layout) && deepEquals(r.values, values);
    }

    @Override
    public int hashCode() {
        return classId * 31 + deepHash(values);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Record(class ").append(classId);
        for (int i = 0; i < values.length; i++) {
            sb.append(", ").append(layout.fields().get(i).name()).append('=');
            append(sb, values[i]);
        }
        return sb.append(')').toString();
    }

    /** Structural equality over canonical values (arrays, lists and maps compared element-wise). */
    public static boolean deepEquals(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Object[] x && b instanceof Object[] y) {
            if (x.length != y.length) {
                return false;
            }
            for (int i = 0; i < x.length; i++) {
                if (!deepEquals(x[i], y[i])) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof byte[] x && b instanceof byte[] y) {
            return Arrays.equals(x, y);
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!deepEquals(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !deepEquals(e.getValue(), y.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /** Hash consistent with {@link #deepEquals}. */
    public static int deepHash(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Object[] a) {
            int h = 1;
            for (Object e : a) {
                h = h * 31 + deepHash(e);
            }
            return h;
        }
        if (v instanceof byte[] b) {
            return Arrays.hashCode(b);
        }
        if (v instanceof List<?> l) {
            int h = 1;
            for (Object e : l) {
                h = h * 31 + deepHash(e);
            }
            return h;
        }
        if (v instanceof Map<?, ?> m) {
            int h = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                h += Objects.hashCode(e.getKey()) ^ deepHash(e.getValue());
            }
            return h;
        }
        return v.hashCode();
    }

    /** Appends a readable form of a canonical value. */
    public static void append(StringBuilder sb, Object v) {
        if (v instanceof Object[] a) {
            sb.append('{');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                append(sb, a[i]);
            }
            sb.append('}');
        } else if (v instanceof byte[] b) {
            sb.append("bytes[").append(b.length).append(']');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                append(sb, l.get(i));
            }
            sb.append(']');
        } else {
            sb.append(v);
        }
    }
}
