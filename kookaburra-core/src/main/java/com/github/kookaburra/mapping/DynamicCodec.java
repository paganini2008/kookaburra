package com.github.kookaburra.mapping;

import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link DynamicObject} ⇄ {@link Record} using only the stored schema (no entity classes).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class DynamicCodec {

    private DynamicCodec() {
    }

    /** Record with the class's current layout; unknown keys are rejected. */
    public static Record toRecord(ClassDef def, DynamicObject o) {
        Layout l = def.current();
        for (String k : o.values().keySet()) {
            if (l.indexOf(k) < 0) {
                throw new IllegalArgumentException(def.simpleName() + " has no stored field '" + k + "'");
            }
        }
        Object[] values = new Object[l.fields().size()];
        for (int i = 0; i < values.length; i++) {
            FieldDef f = l.fields().get(i);
            values[i] = toCanonical(f.type(), o.values().get(f.name()), def.simpleName() + "." + f.name());
        }
        return new Record(def.id(), l, values);
    }

    public static DynamicObject fromRecord(ClassDef def, Record r, long oid, long version) {
        DynamicObject o = new DynamicObject(def.name());
        List<FieldDef> fields = r.layout().fields();
        for (int i = 0; i < fields.size(); i++) {
            Object v = toDynamic(fields.get(i).type(), r.value(i));
            if (v != null) {
                o.set(fields.get(i).name(), v);
            }
        }
        o.assign(oid, version);
        return o;
    }

    /** Dynamic / Java value → canonical value of {@code t} (with conversions and validation). */
    public static Object toCanonical(TypeDef t, Object v, String where) {
        if (v == null) {
            return null;
        }
        switch (t.kind()) {
            case MULTI: {
                List<Object> out = new ArrayList<>();
                for (Object e : collection(v, where)) {
                    if (e == null) {
                        throw new IllegalArgumentException(where + ": null elements are not supported");
                    }
                    out.add(toCanonical(t.element(), e, where));
                }
                return out;
            }
            case EMBEDDED: {
                Map<?, ?> m = map(v, where);
                Object[] out = new Object[t.fields().size()];
                for (Object k : m.keySet()) {
                    if (t.field(String.valueOf(k)) == null) {
                        throw new IllegalArgumentException(where + " has no field '" + k + "'");
                    }
                }
                for (int i = 0; i < out.length; i++) {
                    FieldDef f = t.fields().get(i);
                    out[i] = toCanonical(f.type(), m.get(f.name()), where + "." + f.name());
                }
                return out;
            }
            case EMBEDDED_LIST: {
                List<Object> out = new ArrayList<>();
                for (Object e : collection(v, where)) {
                    out.add(toCanonical(t.element(), e, where));
                }
                return out;
            }
            case MAP: {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map(v, where).entrySet()) {
                    out.put(toCanonical(t.key(), e.getKey(), where), toCanonical(t.element(), e.getValue(), where));
                }
                return out;
            }
            case BYTES:
                if (v instanceof byte[]) {
                    return v;
                }
                throw new IllegalArgumentException(where + ": expected byte[]");
            case OBJECT:
                return v instanceof byte[] ? v : Convert.serialize(v, where);
            default:
                return scalar(t, v, where);
        }
    }

    /** Coerces a scalar to the canonical type of {@code t}; throws if impossible. */
    public static Object scalar(TypeDef t, Object v, String where) {
        Object c = Canonical.scalar(v);
        Object out = switch (t.kind()) {
            case BOOL -> c instanceof Boolean ? c : null;
            case INT32 -> c instanceof Number n && n.doubleValue() == n.intValue() && !(c instanceof Double d && d.isNaN())
                    ? (Object) n.intValue() : null;
            case INT64 -> c instanceof Number n && (c instanceof Long || c instanceof Integer || n.doubleValue() == n.longValue())
                    ? (Object) n.longValue() : null;
            case FLOAT64 -> c instanceof Number n ? (Object) n.doubleValue() : null;
            case DECIMAL -> decimal(c);
            case STRING -> c instanceof String ? c : null;
            case ENUM -> c instanceof String s && t.constantIndex(s) >= 0 ? s : null;
            case DATE -> c instanceof LocalDate ? c : c instanceof String s ? temporal(() -> LocalDate.parse(s)) : null;
            case DATETIME -> c instanceof LocalDateTime ? c
                    : c instanceof String s ? temporal(() -> LocalDateTime.parse(s.replace(' ', 'T'))) : null;
            case INSTANT -> c instanceof Instant ? c : c instanceof String s ? temporal(() -> Instant.parse(s)) : null;
            case REF -> c instanceof Number n ? (Object) n.longValue() : null;
            default -> null;
        };
        if (out == null) {
            throw new IllegalArgumentException(where + ": " + v + " (" + v.getClass().getSimpleName()
                    + ") is not a valid " + t);
        }
        return out;
    }

    /** A number or numeric text as a canonical decimal; null when it is not one (NaN, infinity, other text). */
    private static Object decimal(Object c) {
        try {
            if (c instanceof BigDecimal d) {
                return d;
            }
            if (c instanceof Double d) {
                return Double.isFinite(d) ? ValueType.decimal(BigDecimal.valueOf(d)) : null;
            }
            if (c instanceof Number n) {
                return ValueType.decimal(BigDecimal.valueOf(n.longValue()));
            }
            return c instanceof String s ? ValueType.decimal(new BigDecimal(s.strip())) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses ISO text; null (reported as "not a valid …" with the field) when malformed. */
    private static Object temporal(Supplier<Object> parse) {
        try {
            return parse.get();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Canonical value → dynamic representation. */
    public static Object toDynamic(TypeDef t, Object c) {
        if (c == null) {
            return null;
        }
        switch (t.kind()) {
            case REF:
                return Ref.of((Long) c);
            case MULTI: {
                List<Object> out = new ArrayList<>();
                for (Object e : (List<?>) c) {
                    out.add(toDynamic(t.element(), e));
                }
                return out;
            }
            case EMBEDDED: {
                Object[] a = (Object[]) c;
                Map<String, Object> out = new LinkedHashMap<>();
                for (int i = 0; i < a.length; i++) {
                    Object v = toDynamic(t.fields().get(i).type(), a[i]);
                    if (v != null) {
                        out.put(t.fields().get(i).name(), v);
                    }
                }
                return out;
            }
            case EMBEDDED_LIST: {
                List<Object> out = new ArrayList<>();
                for (Object e : (List<?>) c) {
                    out.add(toDynamic(t.element(), e));
                }
                return out;
            }
            case MAP: {
                if (t.element().kind() != ValueType.EMBEDDED) {
                    return c;
                }
                Map<Object, Object> out = new LinkedHashMap<>();
                ((Map<?, ?>) c).forEach((k, v) -> out.put(k, toDynamic(t.element(), v)));
                return out;
            }
            default:
                return c;
        }
    }

    private static Collection<?> collection(Object v, String where) {
        if (v instanceof Collection<?> c) {
            return c;
        }
        throw new IllegalArgumentException(where + ": expected a collection");
    }

    private static Map<?, ?> map(Object v, String where) {
        if (v instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(where + ": expected a map");
    }
}
