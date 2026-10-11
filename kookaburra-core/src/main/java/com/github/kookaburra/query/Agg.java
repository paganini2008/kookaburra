package com.github.kookaburra.query;

import com.github.kookaburra.field.Field;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * An aggregate (or a group key) used in {@code groupBy(..).agg(..)}, {@code having} and {@code sortedBy}.
 * Create with {@link Q#count()}, {@link Q#sum}, {@link Q#avg}, {@link Q#min}, {@link Q#max},
 * {@link Q#countDistinct} or {@link Q#key}.
 *
 * @param <T> entity type
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public final class Agg<T> {

    /** Aggregate function, or KEY for a group key column. */
    public enum Kind { COUNT, COUNT_DISTINCT, SUM, AVG, MIN, MAX, KEY }

    private final Kind kind;
    private final Field<T, ?> field;
    private final String name;

    Agg(Kind kind, Field<T, ?> field, String name) {
        this.kind = kind;
        this.field = field;
        this.name = name != null ? name : kind == Kind.KEY ? field.path()
                : kind == Kind.COUNT && field == null ? "count(*)"
                : kind == Kind.COUNT_DISTINCT ? "count(distinct " + field.path() + ")"
                : kind.name().toLowerCase() + "(" + field.path() + ")";
        if (kind != Kind.COUNT && field == null) {
            throw new IllegalArgumentException(kind + " needs a field");
        }
        if (field != null && !field.type().isScalar()) {
            throw new IllegalArgumentException("cannot aggregate " + field.type() + " field " + field.path());
        }
    }

    public Kind kind() {
        return kind;
    }

    /** Aggregated field, or null for {@code count()}. */
    public Field<T, ?> field() {
        return field;
    }

    /** Column name in result rows. */
    public String name() {
        return name;
    }

    /** Same aggregate with another column name. */
    public Agg<T> as(String alias) {
        return new Agg<>(kind, field, Objects.requireNonNull(alias));
    }

    public Predicate<Row> eq(Object v) {
        return test(v, c -> c == 0);
    }

    public Predicate<Row> ne(Object v) {
        return test(v, c -> c != 0);
    }

    public Predicate<Row> gt(Object v) {
        return test(v, c -> c > 0);
    }

    public Predicate<Row> ge(Object v) {
        return test(v, c -> c >= 0);
    }

    public Predicate<Row> lt(Object v) {
        return test(v, c -> c < 0);
    }

    public Predicate<Row> le(Object v) {
        return test(v, c -> c <= 0);
    }

    /** SQL semantics: a NULL aggregate value never satisfies a comparison. */
    private Predicate<Row> test(Object v, IntPredicate cmp) {
        Objects.requireNonNull(v, "value");
        return r -> {
            Object x = r.get(name);
            return x != null && cmp.test(compare(x, v));
        };
    }

    public Comparator<Row> asc() {
        return (a, b) -> nullsLast(a.get(name), b.get(name));
    }

    public Comparator<Row> desc() {
        return (a, b) -> nullsLast(b.get(name), a.get(name));
    }

    private static int nullsLast(Object a, Object b) {
        if (a == null || b == null) {
            return a == b ? 0 : a == null ? 1 : -1;
        }
        return compare(a, b);
    }

    /** Compares non-null values: numbers numerically (any Number types), others by {@code compareTo}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            boolean integral = !(x instanceof Double || x instanceof Float || y instanceof Double || y instanceof Float);
            return integral ? Long.compare(x.longValue(), y.longValue()) : Double.compare(x.doubleValue(), y.doubleValue());
        }
        return Integer.signum(((Comparable) a).compareTo(b));
    }

    @Override
    public String toString() {
        return name;
    }
}
