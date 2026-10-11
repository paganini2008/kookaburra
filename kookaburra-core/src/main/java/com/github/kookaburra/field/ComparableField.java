package com.github.kookaburra.field;

import com.github.kookaburra.query.Order;
import com.github.kookaburra.query.Pred;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A field with a total order: equality, ranges and sorting.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class ComparableField<T, V extends Comparable<? super V>> extends AbstractField<T, V> {

    ComparableField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }

    public Pred<T> eq(V value) {
        return new Pred.Eq<>(this, value);
    }

    /** Negation of {@link #eq}: also matches null. */
    public Pred<T> notEq(V value) {
        return new Pred.Not<>(eq(value));
    }

    @SafeVarargs
    public final Pred<T> in(V... values) {
        return in(Arrays.asList(values));
    }

    public Pred<T> in(Collection<? extends V> values) {
        return new Pred.In<>(this, new LinkedHashSet<>(values));
    }

    /** Negation of {@link #in}: also matches null. */
    @SafeVarargs
    public final Pred<T> notIn(V... values) {
        return new Pred.Not<>(in(values));
    }

    public Pred<T> gt(V value) {
        return new Pred.Range<>(this, value, false, null, false);
    }

    public Pred<T> ge(V value) {
        return new Pred.Range<>(this, value, true, null, false);
    }

    public Pred<T> lt(V value) {
        return new Pred.Range<>(this, null, false, value, false);
    }

    public Pred<T> le(V value) {
        return new Pred.Range<>(this, null, false, value, true);
    }

    /** Inclusive on both ends, like SQL {@code BETWEEN}. */
    public Pred<T> between(V lo, V hi) {
        return between(lo, true, hi, true);
    }

    public Pred<T> between(V lo, boolean loInclusive, V hi, boolean hiInclusive) {
        return new Pred.Range<>(this, lo, loInclusive, hi, hiInclusive);
    }

    public Order<T> asc() {
        return Order.asc(this);
    }

    public Order<T> desc() {
        return Order.desc(this);
    }
}
