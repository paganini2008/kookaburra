package com.github.kookaburra.dynamic;

import com.github.kookaburra.Ref;
import com.github.kookaburra.field.Field;
import com.github.kookaburra.mapping.DynamicCodec;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.query.Order;
import com.github.kookaburra.query.Pred;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A field of a stored class addressed by path, without the Java class (design §9.8). Produces the same
 * {@link Pred} / {@link Order} objects as typed fields, so planning and execution are shared.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class DynField implements Field<DynamicObject, Object> {

    /** System field kinds. */
    enum Kind { VALUE, OID, VERSION }

    private final String path;
    private final TypeDef type;
    private final Kind kind;

    DynField(String path, TypeDef type, Kind kind) {
        this.path = path;
        this.type = type;
        this.kind = kind;
    }

    @Override
    public Class<DynamicObject> entity() {
        return DynamicObject.class;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public ValueType type() {
        return type.kind();
    }

    /** Stored type of the path. */
    public TypeDef typeDef() {
        return type;
    }

    @Override
    public Object get(DynamicObject o) {
        return switch (kind) {
            case OID -> o.oid();
            case VERSION -> o.version();
            default -> o.get(path);
        };
    }

    /** Converts a literal to this field's dynamic representation (validating it). */
    public Object coerce(Object v) {
        if (v == null) {
            throw new IllegalArgumentException("null value; use isNull() / isNotNull()");
        }
        TypeDef scalar = type.scalarType();
        Object c = DynamicCodec.scalar(scalar, v, path);
        return scalar.kind() == ValueType.REF ? Ref.of((Long) c) : c;
    }

    private Set<Object> coerceAll(Collection<?> values) {
        Set<Object> out = new LinkedHashSet<>();
        for (Object v : values) {
            out.add(coerce(v));
        }
        return out;
    }

    public Pred<DynamicObject> eq(Object v) {
        return new Pred.Eq<>(this, coerce(v));
    }

    public Pred<DynamicObject> notEq(Object v) {
        return new Pred.Not<>(eq(v));
    }

    public Pred<DynamicObject> in(Object... values) {
        return in(Arrays.asList(values));
    }

    public Pred<DynamicObject> in(Collection<?> values) {
        return new Pred.In<>(this, coerceAll(values));
    }

    public Pred<DynamicObject> gt(Object v) {
        return new Pred.Range<>(this, coerce(v), false, null, false);
    }

    public Pred<DynamicObject> ge(Object v) {
        return new Pred.Range<>(this, coerce(v), true, null, false);
    }

    public Pred<DynamicObject> lt(Object v) {
        return new Pred.Range<>(this, null, false, coerce(v), false);
    }

    public Pred<DynamicObject> le(Object v) {
        return new Pred.Range<>(this, null, false, coerce(v), true);
    }

    public Pred<DynamicObject> between(Object lo, Object hi) {
        return between(lo, true, hi, true);
    }

    public Pred<DynamicObject> between(Object lo, boolean loInclusive, Object hi, boolean hiInclusive) {
        return new Pred.Range<>(this, lo == null ? null : coerce(lo), loInclusive, hi == null ? null : coerce(hi), hiInclusive);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Pred<DynamicObject> startsWith(String prefix) {
        if (type.kind() != ValueType.STRING) {
            throw new IllegalArgumentException(path + " is not a string field");
        }
        return new Pred.StartsWith<>((Field) this, prefix);
    }

    public Pred<DynamicObject> contains(Object v) {
        return contains(List.of(v), false);
    }

    public Pred<DynamicObject> containsAny(Object... values) {
        return contains(Arrays.asList(values), false);
    }

    public Pred<DynamicObject> containsAll(Object... values) {
        return contains(Arrays.asList(values), true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Pred<DynamicObject> contains(Collection<?> values, boolean all) {
        if (type.kind() != ValueType.MULTI) {
            throw new IllegalArgumentException(path + " is not a multi-valued field");
        }
        return new Pred.Contains<>((Field) this, (Set) coerceAll(values), all);
    }

    public Order<DynamicObject> asc() {
        return Order.asc(this);
    }

    public Order<DynamicObject> desc() {
        return Order.desc(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DynField f && f.path.equals(path) && f.kind == kind;
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return path;
    }
}
