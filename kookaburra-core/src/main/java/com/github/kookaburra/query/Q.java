package com.github.kookaburra.query;

import com.github.kookaburra.field.BoolField;
import com.github.kookaburra.field.BoolGetter;
import com.github.kookaburra.field.CollectionGetter;
import com.github.kookaburra.field.ComparableField;
import com.github.kookaburra.field.ComparableGetter;
import com.github.kookaburra.field.DoubleField;
import com.github.kookaburra.field.DoubleGetter;
import com.github.kookaburra.field.Field;
import com.github.kookaburra.field.Fields;
import com.github.kookaburra.field.Getter;
import com.github.kookaburra.field.IntField;
import com.github.kookaburra.field.IntGetter;
import com.github.kookaburra.field.ListField;
import com.github.kookaburra.field.LongField;
import com.github.kookaburra.field.LongGetter;
import com.github.kookaburra.field.ObjectField;
import com.github.kookaburra.field.RefField;
import com.github.kookaburra.field.RefGetter;
import com.github.kookaburra.field.StringField;
import com.github.kookaburra.field.StringGetter;

import java.util.List;

/**
 * Static entry points of the query DSL: {@code import static com.github.kookaburra.query.Q.*;}
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Q {

    private Q() {
    }

    // ---------------------------------------------------------------- where(Person::getX)

    public static <T> IntField<T> where(IntGetter<T> getter) {
        return Fields.of(getter);
    }

    public static <T> LongField<T> where(LongGetter<T> getter) {
        return Fields.of(getter);
    }

    public static <T> DoubleField<T> where(DoubleGetter<T> getter) {
        return Fields.of(getter);
    }

    public static <T> BoolField<T> where(BoolGetter<T> getter) {
        return Fields.of(getter);
    }

    public static <T> StringField<T> where(StringGetter<T> getter) {
        return Fields.of(getter);
    }

    public static <T, V extends Comparable<? super V>> ComparableField<T, V> where(ComparableGetter<T, V> getter) {
        return Fields.of(getter);
    }

    public static <T, E> ListField<T, E> where(CollectionGetter<T, E> getter) {
        return Fields.of(getter);
    }

    public static <T, R> RefField<T, R> where(RefGetter<T, R> getter) {
        return Fields.of(getter);
    }

    public static <T, V> ObjectField<T, V> where(Getter<T, V> getter) {
        return Fields.of(getter);
    }

    // ---------------------------------------------------------------- predicates

    /** Polymorphic filter: rows whose concrete class is {@code type} or a subclass of it. */
    /**
     * A property of the elements of a list of embedded objects, as a multi-valued field ("any element"):
     * {@code whereAny(Shipment::getItems, Item::getSku).contains("x")}.
     */
    public static <T, E, V> ListField<T, V> whereAny(CollectionGetter<T, E> list, Getter<E, V> element) {
        return Fields.elementsOf(list, element);
    }

    public static <T> Pred<T> instanceOf(Class<? extends T> type) {
        return new Pred.InstanceOf<>(type);
    }

    @SafeVarargs
    public static <T> Pred<T> and(Pred<T>... preds) {
        return Pred.And.of(List.of(preds));
    }

    @SafeVarargs
    public static <T> Pred<T> or(Pred<T>... preds) {
        return Pred.Or.of(List.of(preds));
    }

    public static <T> Pred<T> not(Pred<T> pred) {
        return pred.negate();
    }

    // ---------------------------------------------------------------- aggregates

    /** {@code COUNT(*)}: number of rows. */
    public static Agg<Object> count() {
        return new Agg<>(Agg.Kind.COUNT, null, null);
    }

    /** {@code COUNT(field)}: number of rows where the field is not null. */
    public static <T> Agg<T> count(Field<T, ?> field) {
        return new Agg<>(Agg.Kind.COUNT, field, "count(" + field.path() + ")");
    }

    /** {@code COUNT(DISTINCT field)}. */
    public static <T> Agg<T> countDistinct(Field<T, ?> field) {
        return new Agg<>(Agg.Kind.COUNT_DISTINCT, field, null);
    }

    /** {@code SUM(field)}: long for integer fields, double for floating fields; null when there are no non-null values (SQL). */
    public static <T> Agg<T> sum(Field<T, ? extends Number> field) {
        return new Agg<>(Agg.Kind.SUM, field, null);
    }

    /** {@code AVG(field)} as double; null for no rows. */
    public static <T> Agg<T> avg(Field<T, ? extends Number> field) {
        return new Agg<>(Agg.Kind.AVG, field, null);
    }

    public static <T> Agg<T> min(Field<T, ?> field) {
        return new Agg<>(Agg.Kind.MIN, field, null);
    }

    public static <T> Agg<T> max(Field<T, ?> field) {
        return new Agg<>(Agg.Kind.MAX, field, null);
    }

    /** A group key column, for {@code having(key(f).eq(..))} and {@code sortedBy(key(f).asc())}. */
    public static <T> Agg<T> key(Field<T, ?> field) {
        return new Agg<>(Agg.Kind.KEY, field, null);
    }
}
