package com.github.kookaburra.engine;

import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.Record;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Applies atomic increments to records (docs/transaction-design.md §12): numeric scalar fields, top-level or
 * embedded ({@code stats.views}); a null value counts as zero; integer overflow throws {@link ArithmeticException}.
 * Used for read-your-writes inside a transaction and by the sequencer on the latest version at commit.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class Increments {

    private Increments() {
    }

    /** A copy of {@code r} with every {@code path → delta} added. */
    static Record apply(Record r, Map<String, Number> deltas) {
        Object[] values = r.values().clone();
        for (Map.Entry<String, Number> e : deltas.entrySet()) {
            add(values, r.layout().fields(), e.getKey(), e.getKey(), e.getValue());
        }
        return new Record(r.classId(), r.layout(), values);
    }

    /** Checks that {@code path} names a numeric field of the record's layout and that {@code delta} fits it. */
    static void check(Record r, String path, Number delta) {
        apply(r, Map.of(path, delta));
    }

    private static void add(Object[] values, List<FieldDef> fields, String path, String rest, Number delta) {
        int dot = rest.indexOf('.');
        String head = dot < 0 ? rest : rest.substring(0, dot);
        int i = indexOf(fields, head);
        if (i < 0) {
            throw new IllegalArgumentException("no field '" + path + "'");
        }
        TypeDef t = fields.get(i).type();
        if (dot >= 0) {
            if (t.kind() != ValueType.EMBEDDED || values[i] == null) {
                throw new IllegalArgumentException("'" + path + "': " + (values[i] == null ? head + " is null" : head + " is not embedded"));
            }
            Object[] nested = ((Object[]) values[i]).clone();
            add(nested, t.fields(), path, rest.substring(dot + 1), delta);
            values[i] = nested;
            return;
        }
        values[i] = switch (t.kind()) {
            case INT32 -> Math.addExact(values[i] == null ? 0 : (Integer) values[i], Math.toIntExact(integral(path, delta)));
            case INT64 -> Math.addExact(values[i] == null ? 0L : (Long) values[i], integral(path, delta));
            case FLOAT64 -> (values[i] == null ? 0.0 : (Double) values[i]) + delta.doubleValue();
            case DECIMAL -> ValueType.decimal((values[i] == null ? BigDecimal.ZERO : (BigDecimal) values[i]).add(decimal(delta)));
            default -> throw new IllegalArgumentException("'" + path + "' is not numeric (" + t + ")");
        };
    }

    private static long integral(String path, Number delta) {
        if (delta instanceof BigDecimal d) {
            try {
                return d.longValueExact();
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("'" + path + "' is an integer field; delta " + delta + " is not");
            }
        }
        if (delta instanceof Double || delta instanceof Float) {
            double d = delta.doubleValue();
            if (d != Math.rint(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("'" + path + "' is an integer field; delta " + delta + " is not");
            }
            return (long) d;
        }
        return delta.longValue();
    }

    private static int indexOf(List<FieldDef> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static BigDecimal decimal(Number n) {
        if (n instanceof BigDecimal d) {
            return d;
        }
        return n instanceof Double || n instanceof Float ? BigDecimal.valueOf(n.doubleValue()) : BigDecimal.valueOf(n.longValue());
    }

    /** Sums deltas of the same path (for repeated increments in one transaction). */
    static Number plus(Number a, Number b) {
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            return decimal(a).add(decimal(b));
        }
        if (a instanceof Double || a instanceof Float || b instanceof Double || b instanceof Float) {
            return a.doubleValue() + b.doubleValue();
        }
        return Math.addExact(a.longValue(), b.longValue());
    }
}
