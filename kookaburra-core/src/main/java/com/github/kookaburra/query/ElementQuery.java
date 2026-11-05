package com.github.kookaburra.query;

import com.github.kookaburra.field.Getter;
import com.github.kookaburra.field.Fields;
import com.github.kookaburra.mapping.Canonical;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Conditions on one element of a list of embedded objects, all met by the same element (MongoDB's
 * {@code $elemMatch}): {@code whereElement(Order::getLines, e -> e.where(Line::getSku).eq("A").where(Line::getQty).gt(5))}.
 *
 * @param <E> element type
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public final class ElementQuery<E> {

    /** Comparison of one element property. */
    public enum Op { EQ, NE, IN, GT, GE, LT, LE, BETWEEN, IS_NULL, NOT_NULL, STARTS_WITH }

    /**
     * One condition: {@code property} read with {@code getter} from an element, compared by {@code op} with
     * {@code values} (canonical values: numbers compare by value, decimals exactly).
     */
    public record Condition(String property, Getter<Object, Object> getter, Op op, List<Object> values) {

        @SuppressWarnings("unchecked")
        boolean test(Object element) {
            Object v = element == null ? null : getter.get(element);
            Object c = v == null ? null : Canonical.scalar(v);
            return switch (op) {
                case IS_NULL -> c == null;
                case NOT_NULL -> c != null;
                case EQ -> c != null && compare(c, values.get(0)) == 0;
                case NE -> c != null && compare(c, values.get(0)) != 0;
                case IN -> c != null && values.stream().anyMatch(x -> compare(c, x) == 0);
                case GT -> c != null && compare(c, values.get(0)) > 0;
                case GE -> c != null && compare(c, values.get(0)) >= 0;
                case LT -> c != null && compare(c, values.get(0)) < 0;
                case LE -> c != null && compare(c, values.get(0)) <= 0;
                case BETWEEN -> c != null && compare(c, values.get(0)) >= 0 && compare(c, values.get(1)) <= 0;
                case STARTS_WITH -> c instanceof String s && s.startsWith((String) values.get(0));
            };
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static int compare(Object a, Object b) {
            if (a instanceof Number x && b instanceof Number y && a.getClass() != b.getClass()) {
                return Double.compare(x.doubleValue(), y.doubleValue());
            }
            return ((Comparable) a).compareTo(b);
        }

        @Override
        public String toString() {
            return switch (op) {
                case IS_NULL -> property + " IS NULL";
                case NOT_NULL -> property + " IS NOT NULL";
                case IN -> property + " IN " + values;
                case BETWEEN -> property + " BETWEEN " + values.get(0) + " AND " + values.get(1);
                case STARTS_WITH -> property + " STARTS WITH '" + values.get(0) + "'";
                default -> property + " " + switch (op) {
                    case EQ -> "=";
                    case NE -> "<>";
                    case GT -> ">";
                    case GE -> ">=";
                    case LT -> "<";
                    default -> "<=";
                } + " " + (values.get(0) instanceof String s ? "'" + s + "'" : values.get(0));
            };
        }
    }

    private final List<Condition> conditions = new ArrayList<>();

    ElementQuery() {
    }

    List<Condition> conditions() {
        return conditions;
    }

    /** A property of the element. */
    public <V> Step<V> where(Getter<E, V> getter) {
        String property = Fields.propertyOf(getter);
        return new Step<>(property, getter);
    }

    /** Comparisons of one property; each returns the query for more conditions. */
    public final class Step<V> {
        private final String property;
        private final Getter<E, V> getter;

        Step(String property, Getter<E, V> getter) {
            this.property = property;
            this.getter = getter;
        }

        @SuppressWarnings("unchecked")
        private ElementQuery<E> add(Op op, Object... values) {
            List<Object> canonical = new ArrayList<>();
            for (Object v : values) {
                canonical.add(Canonical.scalar(Objects.requireNonNull(v, "value")));
            }
            conditions.add(new Condition(property, (Getter<Object, Object>) (Getter<?, ?>) getter, op, canonical));
            return ElementQuery.this;
        }

        public ElementQuery<E> eq(V v) {
            return add(Op.EQ, v);
        }

        public ElementQuery<E> ne(V v) {
            return add(Op.NE, v);
        }

        @SafeVarargs
        public final ElementQuery<E> in(V... v) {
            if (v.length == 0) {
                throw new IllegalArgumentException("in() needs at least one value");
            }
            return add(Op.IN, Arrays.copyOf(v, v.length, Object[].class));
        }

        public ElementQuery<E> gt(V v) {
            return add(Op.GT, v);
        }

        public ElementQuery<E> ge(V v) {
            return add(Op.GE, v);
        }

        public ElementQuery<E> lt(V v) {
            return add(Op.LT, v);
        }

        public ElementQuery<E> le(V v) {
            return add(Op.LE, v);
        }

        public ElementQuery<E> between(V lo, V hi) {
            return add(Op.BETWEEN, lo, hi);
        }

        public ElementQuery<E> isNull() {
            return add(Op.IS_NULL);
        }

        public ElementQuery<E> isNotNull() {
            return add(Op.NOT_NULL);
        }

        public ElementQuery<E> startsWith(String prefix) {
            return add(Op.STARTS_WITH, prefix);
        }
    }
}
