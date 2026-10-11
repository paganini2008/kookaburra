package com.github.kookaburra.exec;

import com.github.kookaburra.field.Field;
import com.github.kookaburra.field.RefField;
import com.github.kookaburra.mapping.Canonical;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.query.Pred;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Translates {@link Pred} trees into {@link Cond} trees for one collection: resolves paths and types
 * against the schema, canonicalizes literals, maps {@code @Id} to OID lookups, expands
 * {@code instanceOf} to class-id sets and runs reference sub-queries.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Planner {

    /** Path of the OID pseudo-column. */
    public static final String OID = "$oid";
    /** Path of the version pseudo-column. */
    public static final String VERSION = "$version";

    private final Schema schema;
    private final String collection;
    private final String idField;
    private final String versionField;
    private final BiFunction<Class<?>, Pred<?>, Set<Long>> subquery;
    private BiFunction<String, Pred<?>, Set<Long>> dynamicSubquery = (c, p) -> {
        throw new UnsupportedOperationException("dynamic sub-queries are not supported here");
    };
    private Function<Object, Set<Long>> streamOids = s -> {
        throw new UnsupportedOperationException("sub-query streams are not supported here");
    };

    /**
     * @param subquery runs {@code from(target).filter(pred)} on the same snapshot and returns the OIDs
     */
    public Planner(Schema schema, String collection, BiFunction<Class<?>, Pred<?>, Set<Long>> subquery) {
        this.schema = schema;
        this.collection = collection;
        ClassDef root = schema.byName(collection);
        this.idField = root == null ? null : root.idField();
        this.versionField = root == null ? null : root.versionField();
        this.subquery = subquery;
    }

    /** Sets how {@code DynRefMatch} sub-queries (by stored class name) are evaluated. */
    public Planner withDynamicSubquery(BiFunction<String, Pred<?>, Set<Long>> f) {
        this.dynamicSubquery = f;
        return this;
    }

    /** Sets how {@code RefIn} sub-query streams are evaluated (on the same snapshot). */
    public Planner withStreamOids(Function<Object, Set<Long>> f) {
        this.streamOids = f;
        return this;
    }

    /** Stored path for a field path: {@code $oid} / {@code $version} for the system fields. */
    public String storedPath(String path) {
        if (path.equals(idField)) {
            return OID;
        }
        if (path.equals(versionField)) {
            return VERSION;
        }
        return path;
    }

    /** Type of a path in any class of the collection (current layouts), or null. */
    public TypeDef typeOf(String path) {
        if (path.equals(OID) || path.equals(VERSION)) {
            return TypeDef.scalar(ValueType.INT64);
        }
        for (ClassDef c : schema.classesOf(collection)) {
            TypeDef t = typeIn(c.current().fields(), path);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private static TypeDef typeIn(List<FieldDef> fields, String path) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        for (FieldDef f : fields) {
            if (f.name().equals(head)) {
                if (dot < 0) {
                    return f.type();
                }
                return f.type().kind() == ValueType.EMBEDDED ? typeIn(f.type().fields(), path.substring(dot + 1)) : null;
            }
        }
        return null;
    }

    public Cond plan(Pred<?> pred) {
        return simplify(translate(pred));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Cond translate(Pred<?> pred) {
        if (pred instanceof Pred.And<?> a) {
            List<Cond> cs = new ArrayList<>();
            for (Pred<?> p : a.preds()) {
                cs.add(translate(p));
            }
            return new Cond.And(cs);
        }
        if (pred instanceof Pred.Or<?> o) {
            List<Cond> cs = new ArrayList<>();
            for (Pred<?> p : o.preds()) {
                cs.add(translate(p));
            }
            return new Cond.Or(cs);
        }
        if (pred instanceof Pred.Not<?> n) {
            return new Cond.Not(translate(n.pred()));
        }
        if (pred instanceof Pred.InstanceOf<?> i) {
            Set<Integer> ids = schema.subclassIds(i.type().getName());
            return ids.isEmpty() ? Cond.Const.FALSE : new Cond.Types(ids, i.type().getSimpleName());
        }
        if (pred instanceof Pred.Residual<?> r) {
            return residual(r);
        }
        if (pred instanceof Pred.TypeIn<?> t) {
            Set<Integer> ids = new TreeSet<>();
            for (String n : t.classNames()) {
                ClassDef c = schema.byName(n);
                if (c != null) {
                    ids.add(c.id());
                }
            }
            return ids.isEmpty() ? Cond.Const.FALSE : new Cond.Types(ids, t.toString().substring("TYPE IN ".length()));
        }
        if (pred instanceof Pred.DynRefMatch<?> m) {
            Set<Long> oids = dynamicSubquery.apply(m.targetClass(), m.target());
            if (oids.isEmpty()) {
                return Cond.Const.FALSE;
            }
            TypeDef t = typeOf(m.field().path());
            return t == null ? residual(pred) : new Cond.In(m.field().path(), t, new LinkedHashSet<>(oids));
        }
        if (pred instanceof Pred.RefMatch<?, ?> || pred instanceof Pred.RefIn<?, ?>) {
            RefField<?, ?> f = pred instanceof Pred.RefMatch<?, ?> m ? m.field() : ((Pred.RefIn<?, ?>) pred).field();
            Set<Long> oids = pred instanceof Pred.RefMatch<?, ?> m ? subquery.apply(f.target(), m.target())
                    : streamOids.apply(((Pred.RefIn<?, ?>) pred).source());
            if (oids.isEmpty()) {
                return Cond.Const.FALSE;
            }
            TypeDef t = typeOf(f.path());
            return t == null ? residual(pred) : new Cond.In(f.path(), t, new LinkedHashSet<>(oids));
        }
        Field<?, ?> field = fieldOf(pred);
        String path = storedPath(field.path());
        TypeDef type = typeOf(path);
        if (type == null || path.equals(VERSION)) {
            return residual(pred);
        }
        if (pred instanceof Pred.Eq<?, ?> e) {
            Object key = Canonical.scalar(e.value());
            return path.equals(OID) ? new Cond.Oids(Set.of((Long) key)) : new Cond.Eq(path, type.scalarType(), key);
        }
        if (pred instanceof Pred.In<?, ?> in) {
            Set<Object> keys = canonical(in.values());
            if (path.equals(OID)) {
                return new Cond.Oids((Set) keys);
            }
            return new Cond.In(path, type.scalarType(), keys);
        }
        if (pred instanceof Pred.Range<?, ?> r) {
            return new Cond.Range(path, type.scalarType(), r.lo() == null ? null : Canonical.scalar(r.lo()), r.loInclusive(),
                    r.hi() == null ? null : Canonical.scalar(r.hi()), r.hiInclusive());
        }
        if (pred instanceof Pred.IsNull<?>) {
            return path.equals(OID) ? Cond.Const.FALSE : new Cond.IsNull(path);
        }
        if (pred instanceof Pred.StartsWith<?> s) {
            return new Cond.Prefix(path, s.prefix());
        }
        Pred.Contains<?, ?> c = (Pred.Contains<?, ?>) pred;
        return new Cond.Contains(path, type.scalarType(), canonical(c.values()), c.all());
    }

    private static Field<?, ?> fieldOf(Pred<?> pred) {
        if (pred instanceof Pred.Eq<?, ?> e) return e.field();
        if (pred instanceof Pred.In<?, ?> i) return i.field();
        if (pred instanceof Pred.Range<?, ?> r) return r.field();
        if (pred instanceof Pred.IsNull<?> n) return n.field();
        if (pred instanceof Pred.StartsWith<?> s) return s.field();
        return ((Pred.Contains<?, ?>) pred).field();
    }

    private static Set<Object> canonical(Set<?> values) {
        Set<Object> out = new LinkedHashSet<>();
        for (Object v : values) {
            out.add(Canonical.scalar(v));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Cond residual(Pred<?> pred) {
        Pred<Object> p = (Pred<Object>) pred;
        return new Cond.Residual(p, pred.toString());
    }

    /** Folds constants and flattens nested And / Or. */
    static Cond simplify(Cond c) {
        if (c instanceof Cond.And a) {
            List<Cond> out = new ArrayList<>();
            for (Cond x : a.conds()) {
                Cond s = simplify(x);
                if (s instanceof Cond.Const k) {
                    if (!k.value()) {
                        return Cond.Const.FALSE;
                    }
                } else if (s instanceof Cond.And inner) {
                    out.addAll(inner.conds());
                } else {
                    out.add(s);
                }
            }
            return out.isEmpty() ? Cond.Const.TRUE : out.size() == 1 ? out.get(0) : new Cond.And(out);
        }
        if (c instanceof Cond.Or o) {
            List<Cond> out = new ArrayList<>();
            for (Cond x : o.conds()) {
                Cond s = simplify(x);
                if (s instanceof Cond.Const k) {
                    if (k.value()) {
                        return Cond.Const.TRUE;
                    }
                } else if (s instanceof Cond.Or inner) {
                    out.addAll(inner.conds());
                } else {
                    out.add(s);
                }
            }
            return out.isEmpty() ? Cond.Const.FALSE : out.size() == 1 ? out.get(0) : new Cond.Or(out);
        }
        if (c instanceof Cond.Not n) {
            Cond s = simplify(n.cond());
            if (s instanceof Cond.Const k) {
                return k.value() ? Cond.Const.FALSE : Cond.Const.TRUE;
            }
            return s instanceof Cond.Not nn ? nn.cond() : new Cond.Not(s);
        }
        return c;
    }
}
