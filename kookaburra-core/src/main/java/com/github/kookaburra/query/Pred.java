package com.github.kookaburra.query;

import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.field.Field;
import com.github.kookaburra.field.RefField;
import com.github.kookaburra.mapping.ValueType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A query predicate the planner can inspect. Every {@code Pred} is also an executable
 * {@link Predicate}; index execution must agree with {@link #test} (design §11).
 *
 * @param <T> entity type
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public sealed interface Pred<T> extends Predicate<T> {

    @Override
    default Pred<T> and(Predicate<? super T> other) {
        return And.of(List.of(this, wrap(other)));
    }

    @Override
    default Pred<T> or(Predicate<? super T> other) {
        return Or.of(List.of(this, wrap(other)));
    }

    @Override
    default Pred<T> negate() {
        return this instanceof Not<T> n ? n.pred() : new Not<>(this);
    }

    /** Returns {@code p} itself if it is a {@code Pred}, otherwise wraps it as an opaque residual. */
    @SuppressWarnings("unchecked")
    static <T> Pred<T> wrap(Predicate<? super T> p) {
        Objects.requireNonNull(p, "predicate");
        return p instanceof Pred<?> pred ? (Pred<T>) pred : new Residual<>(p);
    }

    // ---------------------------------------------------------------- leaves

    /** {@code field = value}; false when the field is null. */
    record Eq<T, V>(Field<T, V> field, V value) implements Pred<T> {
        @SuppressWarnings("unchecked")
        public Eq {
            Objects.requireNonNull(field, "field");
            value = (V) checkValue(value);
        }

        @Override
        public boolean test(T t) {
            Object v = field.get(t);
            return v != null && ValueType.compare(v, value) == 0;
        }

        @Override
        public String toString() {
            return field.path() + " = " + literal(value);
        }
    }

    /** {@code field IN (values)}; false when the field is null. */
    record In<T, V>(Field<T, V> field, Set<V> values) implements Pred<T> {
        public In {
            Objects.requireNonNull(field, "field");
            values = normalizedSet(values);
        }

        @Override
        public boolean test(T t) {
            Object v = field.get(t);
            return v != null && values.contains(v);
        }

        @Override
        public String toString() {
            return field.path() + " IN " + values.stream().map(Pred::literal)
                    .collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /** Range with optional bounds; false when the field is null. */
    record Range<T, V>(Field<T, V> field, V lo, boolean loInclusive, V hi, boolean hiInclusive)
            implements Pred<T> {
        @SuppressWarnings("unchecked")
        public Range {
            Objects.requireNonNull(field, "field");
            if (lo == null && hi == null) {
                throw new IllegalArgumentException("Range needs at least one bound");
            }
            lo = lo == null ? null : (V) checkValue(lo);
            hi = hi == null ? null : (V) checkValue(hi);
        }

        @Override
        public boolean test(T t) {
            Object v = field.get(t);
            if (v == null) {
                return false;
            }
            if (lo != null) {
                int c = ValueType.compare(v, lo);
                if (c < 0 || (c == 0 && !loInclusive)) {
                    return false;
                }
            }
            if (hi != null) {
                int c = ValueType.compare(v, hi);
                return c < 0 || (c == 0 && hiInclusive);
            }
            return true;
        }

        @Override
        public String toString() {
            if (hi == null) {
                return field.path() + (loInclusive ? " >= " : " > ") + literal(lo);
            }
            if (lo == null) {
                return field.path() + (hiInclusive ? " <= " : " < ") + literal(hi);
            }
            return field.path() + " IN " + (loInclusive ? "[" : "(") + literal(lo) + ", " + literal(hi)
                    + (hiInclusive ? "]" : ")");
        }
    }

    /** {@code field IS NULL}; for multi-valued fields: the collection itself is null. */
    record IsNull<T>(Field<T, ?> field) implements Pred<T> {
        public IsNull {
            Objects.requireNonNull(field, "field");
        }

        @Override
        public boolean test(T t) {
            return field.get(t) == null;
        }

        @Override
        public String toString() {
            return field.path() + " IS NULL";
        }
    }

    /** String prefix match; false when the field is null. */
    record StartsWith<T>(Field<T, String> field, String prefix) implements Pred<T> {
        public StartsWith {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(prefix, "prefix");
        }

        @Override
        public boolean test(T t) {
            String v = field.get(t);
            return v != null && v.startsWith(prefix);
        }

        @Override
        public String toString() {
            return field.path() + " STARTS WITH " + literal(prefix);
        }
    }

    /** Multi-valued membership: any ({@code all == false}) or all of {@code values} are elements. */
    record Contains<T, E>(Field<T, ? extends Collection<E>> field, Set<E> values, boolean all) implements Pred<T> {
        public Contains {
            Objects.requireNonNull(field, "field");
            values = normalizedSet(values);
        }

        @Override
        public boolean test(T t) {
            Collection<E> c = field.get(t);
            if (c == null) {
                return false;
            }
            Set<Object> present = new HashSet<>();
            for (E e : c) {
                if (e != null) {
                    present.add(ValueType.normalize(e));
                }
            }
            return all ? present.containsAll(values) : values.stream().anyMatch(present::contains);
        }

        @Override
        public String toString() {
            return field.path() + (all ? " CONTAINS ALL " : " CONTAINS ANY ")
                    + values.stream().map(Pred::literal).collect(Collectors.joining(", ", "(", ")"));
        }
    }

    /** Exact-type filter by stored class name (OQL {@code TYPE(a) IN (...)}); subclasses are not included. */
    record TypeIn<T>(Set<String> classNames) implements Pred<T> {
        public TypeIn {
            classNames = Set.copyOf(classNames);
        }

        @Override
        public boolean test(T t) {
            String name = t instanceof DynamicObject d ? d.className() : t.getClass().getName();
            return classNames.contains(name);
        }

        @Override
        public String toString() {
            return "TYPE IN " + classNames.stream().map(n -> n.substring(n.lastIndexOf('.') + 1)).sorted().toList();
        }
    }

    /** Reference match without entity classes: the target of {@code field} (a stored class) matches {@code target}. */
    record DynRefMatch<T>(Field<T, ?> field, String targetClass, Pred<DynamicObject> target)
            implements Pred<T> {
        public DynRefMatch {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(targetClass, "targetClass");
            Objects.requireNonNull(target, "target");
        }

        @Override
        public boolean test(T t) {
            throw new UnsupportedOperationException("DynRefMatch is evaluated by the query engine");
        }

        @Override
        public String toString() {
            return field.path() + " -> (" + target + ")";
        }
    }

    /** Polymorphic type filter: the object is an instance of {@code type}. */
    record InstanceOf<T>(Class<?> type) implements Pred<T> {
        public InstanceOf {
            Objects.requireNonNull(type, "type");
        }

        @Override
        public boolean test(T t) {
            return type.isInstance(t);
        }

        @Override
        public String toString() {
            return "INSTANCE OF " + type.getSimpleName();
        }
    }

    /** The referenced object matches {@code target}. Needs the database to evaluate. */
    record RefMatch<T, R>(RefField<T, R> field, Pred<R> target) implements Pred<T> {
        public RefMatch {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(target, "target");
        }

        @Override
        public boolean test(T t) {
            throw new UnsupportedOperationException("RefMatch is evaluated by the query engine");
        }

        @Override
        public String toString() {
            return field.path() + " -> (" + target + ")";
        }
    }

    /** The reference points at one of the objects selected by {@code source} (semi-join sub-query). */
    record RefIn<T, R>(RefField<T, R> field, QueryStream<R> source) implements Pred<T> {
        public RefIn {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public boolean test(T t) {
            throw new UnsupportedOperationException("RefIn is evaluated by the query engine");
        }

        @Override
        public String toString() {
            return field.path() + " IN (sub-query)";
        }
    }

    /** Opaque Java predicate: evaluated on decoded objects. */
    record Residual<T>(Predicate<? super T> predicate) implements Pred<T> {
        public Residual {
            Objects.requireNonNull(predicate, "predicate");
        }

        @Override
        public boolean test(T t) {
            return predicate.test(t);
        }

        @Override
        public String toString() {
            return "RESIDUAL " + predicate;
        }
    }

    // ---------------------------------------------------------------- combinators

    record And<T>(List<Pred<T>> preds) implements Pred<T> {
        public And {
            preds = List.copyOf(preds);
        }

        /** Builds a flattened conjunction. */
        public static <T> Pred<T> of(List<Pred<T>> preds) {
            List<Pred<T>> flat = new ArrayList<>();
            for (Pred<T> p : preds) {
                if (p instanceof And<T> a) {
                    flat.addAll(a.preds);
                } else {
                    flat.add(Objects.requireNonNull(p));
                }
            }
            return flat.size() == 1 ? flat.get(0) : new And<>(flat);
        }

        @Override
        public boolean test(T t) {
            for (Pred<T> p : preds) {
                if (!p.test(t)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return preds.stream().map(p -> p instanceof Or<?> ? "(" + p + ")" : p.toString())
                    .collect(Collectors.joining(" AND "));
        }
    }

    record Or<T>(List<Pred<T>> preds) implements Pred<T> {
        public Or {
            preds = List.copyOf(preds);
        }

        /** Builds a flattened disjunction. */
        public static <T> Pred<T> of(List<Pred<T>> preds) {
            List<Pred<T>> flat = new ArrayList<>();
            for (Pred<T> p : preds) {
                if (p instanceof Or<T> o) {
                    flat.addAll(o.preds);
                } else {
                    flat.add(Objects.requireNonNull(p));
                }
            }
            return flat.size() == 1 ? flat.get(0) : new Or<>(flat);
        }

        @Override
        public boolean test(T t) {
            for (Pred<T> p : preds) {
                if (p.test(t)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return preds.stream().map(p -> p instanceof And<?> ? "(" + p + ")" : p.toString())
                    .collect(Collectors.joining(" OR "));
        }
    }

    /** Two-valued negation: {@code not(age > 30)} matches rows where age is null. */
    record Not<T>(Pred<T> pred) implements Pred<T> {
        public Not {
            Objects.requireNonNull(pred, "pred");
        }

        @Override
        public boolean test(T t) {
            return !pred.test(t);
        }

        @Override
        public String toString() {
            return "NOT (" + pred + ")";
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Object checkValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("null value; use isNull() / isNotNull()");
        }
        return ValueType.normalize(value);
    }

    @SuppressWarnings("unchecked")
    private static <V> Set<V> normalizedSet(Collection<? extends V> values) {
        Objects.requireNonNull(values, "values");
        Set<V> set = new LinkedHashSet<>();
        for (V v : values) {
            set.add((V) checkValue(v));
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("empty value set");
        }
        return Collections.unmodifiableSet(set);
    }

    private static String literal(Object v) {
        return v instanceof String || v instanceof Enum<?> ? "'" + v + "'" : String.valueOf(v);
    }
}
