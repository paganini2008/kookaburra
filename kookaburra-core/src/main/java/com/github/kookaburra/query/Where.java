package com.github.kookaburra.query;

import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynField;
import com.github.kookaburra.field.BoolField;
import com.github.kookaburra.field.ComparableField;
import com.github.kookaburra.field.Field;
import com.github.kookaburra.field.ListField;
import com.github.kookaburra.field.RefField;
import com.github.kookaburra.field.StringField;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Fluent condition on one field of a {@link QueryStream}: {@code db.query(Order.class).where(Order::getOwner).eq(oid).list()}.
 * Each method adds the condition as a filter and returns the stream, so conditions chain:
 * {@code .where(A::getX).gt(1).where(A::getY).eq("a")}. Same predicates as {@code filter(Q.where(...)...)}.
 *
 * @param <T> entity type
 * @param <V> value type of the field (for reference fields: the target's OID as {@link Long})
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public class Where<T, V> {

    private final QueryStream<T> stream;
    private final Field<T, ?> field;

    Where(QueryStream<T> stream, Field<T, ?> field) {
        this.stream = stream;
        this.field = field;
    }

    /** Equal to {@code value}; for reference fields {@code value} is the target's OID. */
    public QueryStream<T> eq(V value) {
        return stream.filter(eqPred(value));
    }

    public QueryStream<T> notEq(V value) {
        if (dyn() != null) {
            return stream.filter(cast(dyn().notEq(value)));
        }
        if (field instanceof ComparableField<T, ?> c) {
            return stream.filter(raw(c).notEq(value));
        }
        return stream.filter(Q.not(eqPred(value)));
    }

    @SafeVarargs
    public final QueryStream<T> in(V... values) {
        return in(Arrays.asList(values));
    }

    public QueryStream<T> in(Collection<? extends V> values) {
        if (dyn() != null) {
            return stream.filter(cast(dyn().in(values)));
        }
        if (field instanceof ComparableField<T, ?> c) {
            return stream.filter(raw(c).in(values));
        }
        List<Pred<T>> any = new ArrayList<>();
        for (V v : values) {
            any.add(eqPred(v));
        }
        return stream.filter(Pred.Or.of(any));
    }

    public QueryStream<T> gt(V value) {
        return stream.filter(dyn() != null ? cast(dyn().gt(value)) : raw(ordered("gt")).gt(value));
    }

    public QueryStream<T> ge(V value) {
        return stream.filter(dyn() != null ? cast(dyn().ge(value)) : raw(ordered("ge")).ge(value));
    }

    public QueryStream<T> lt(V value) {
        return stream.filter(dyn() != null ? cast(dyn().lt(value)) : raw(ordered("lt")).lt(value));
    }

    public QueryStream<T> le(V value) {
        return stream.filter(dyn() != null ? cast(dyn().le(value)) : raw(ordered("le")).le(value));
    }

    /** {@code lo <= value <= hi}. */
    public QueryStream<T> between(V lo, V hi) {
        return stream.filter(dyn() != null ? cast(dyn().between(lo, hi)) : raw(ordered("between")).between(lo, hi));
    }

    public QueryStream<T> isNull() {
        return stream.filter(field.isNull());
    }

    public QueryStream<T> isNotNull() {
        return stream.filter(field.isNotNull());
    }

    public QueryStream<T> isTrue() {
        return dyn() != null ? stream.filter(cast(dyn().eq(true))) : stream.filter(bool().isTrue());
    }

    public QueryStream<T> isFalse() {
        return dyn() != null ? stream.filter(cast(dyn().eq(false))) : stream.filter(bool().isFalse());
    }

    @SuppressWarnings("unchecked")
    public QueryStream<T> startsWith(String prefix) {
        if (dyn() != null) {
            return stream.filter(cast(dyn().startsWith(prefix)));
        }
        if (field instanceof StringField<?> s) {
            return stream.filter(((StringField<T>) s).startsWith(prefix));
        }
        throw unsupported("startsWith", "string");
    }

    /** For collection fields: any element equals {@code element}. */
    @SuppressWarnings("unchecked")
    public QueryStream<T> contains(V element) {
        if (dyn() != null) {
            return stream.filter(cast(dyn().contains(element)));
        }
        if (field instanceof ListField<?, ?> l) {
            return stream.filter(((ListField<T, Object>) l).contains(element));
        }
        throw unsupported("contains", "collection");
    }

    // ---------------------------------------------------------------- dispatch

    /** The field as a dynamic (path-based) field, or null for typed fields. */
    private DynField dyn() {
        return field instanceof DynField d ? d : null;
    }

    @SuppressWarnings("unchecked")
    private Pred<T> cast(Pred<?> p) {
        return (Pred<T>) p;
    }

    @SuppressWarnings("unchecked")
    private Pred<T> eqPred(Object value) {
        if (dyn() != null) {
            return cast(dyn().eq(value));
        }
        if (field instanceof ComparableField<T, ?> c) {
            return raw(c).eq(value);
        }
        if (field instanceof RefField<?, ?> ref) {
            @SuppressWarnings("unchecked")
            RefField<T, ?> r = (RefField<T, ?>) ref;
            if (value instanceof Ref<?> target) {
                return r.eq(target.oid());
            }
            if (value instanceof Number n) {
                return r.eq(n.longValue());
            }
            throw new IllegalArgumentException("'" + field.path() + "' is a reference: compare with an OID or a Ref, got " + value);
        }
        if (field instanceof ListField<?, ?>) {
            throw new IllegalArgumentException("'" + field.path() + "' is a collection: use contains(...)");
        }
        throw unsupported("eq", "comparable or reference");
    }

    /** The field without its value type: values were type-checked by {@code V} at the call. */
    private static <T> RawComparable<T> raw(ComparableField<T, ?> c) {
        return new RawComparable<>(c);
    }

    private ComparableField<T, ?> ordered(String op) {
        if (field instanceof ComparableField<T, ?> c) {
            return c;
        }
        throw unsupported(op, "comparable");
    }

    @SuppressWarnings("unchecked")
    private BoolField<T> bool() {
        if (field instanceof BoolField<?> b) {
            return (BoolField<T>) b;
        }
        throw unsupported("isTrue / isFalse", "boolean");
    }

    private IllegalArgumentException unsupported(String op, String kind) {
        return new IllegalArgumentException(op + "(...) needs a " + kind + " field; '" + field.path() + "' is "
                + field.getClass().getSimpleName());
    }

    /** Unchecked bridge to {@link ComparableField}'s typed predicate methods. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static class RawComparable<T> {
        private final ComparableField f;

        RawComparable(ComparableField<T, ?> f) {
            this.f = f;
        }

        Pred<T> eq(Object v) {
            return f.eq((Comparable) v);
        }

        Pred<T> notEq(Object v) {
            return f.notEq((Comparable) v);
        }

        Pred<T> in(Collection<?> v) {
            return f.in((Collection) v);
        }

        Pred<T> gt(Object v) {
            return f.gt((Comparable) v);
        }

        Pred<T> ge(Object v) {
            return f.ge((Comparable) v);
        }

        Pred<T> lt(Object v) {
            return f.lt((Comparable) v);
        }

        Pred<T> le(Object v) {
            return f.le((Comparable) v);
        }

        Pred<T> between(Object lo, Object hi) {
            return f.between((Comparable) lo, (Comparable) hi);
        }
    }
}
