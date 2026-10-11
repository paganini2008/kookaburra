package com.github.kookaburra.oql;

import com.github.kookaburra.Ref;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.dynamic.DynField;
import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.exec.Aggregator;
import com.github.kookaburra.exec.Planner;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.DynamicCodec;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.query.Order;
import com.github.kookaburra.query.Pred;
import com.github.kookaburra.query.QueryStream;
import com.github.kookaburra.query.Row;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Compiles and runs one OQL statement on one read view (design §9.9): WHERE is pushed down as far as
 * possible ({@link Pred}), the rest is evaluated on {@link DynamicObject}s; GROUP BY / aggregates use the
 * canonical fast path when keys and arguments are plain stored paths.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Engine {

    /** Result of a SELECT: column names, rows, and the objects for object-shaped results. */
    record Result(List<String> names, List<Object[]> rows, List<DynamicObject> objects, Ast.Shape shape,
                  boolean singleObject, String plan) {
    }

    /** Resolved path: local stored path, its type, and the rest of the path after a reference hop. */
    record Resolved(String local, TypeDef type, String refTarget, List<String> rest) {
    }

    /** Query scope: a stored class, its alias and join aliases. */
    class Scope {
        final ClassDef cls;
        final DynamicCollection coll;
        final String alias;
        final Map<String, List<String>> joins;
        final Planner planner;

        Scope(ClassDef cls, String alias, Map<String, List<String>> joins) {
            this.cls = cls;
            this.coll = host.database().dynamic(cls.name());
            this.alias = alias;
            this.joins = joins;
            this.planner = new Planner(schema, cls.collection(), (a, b) -> Set.of());
        }

        /** Path parts relative to the class; empty = the object itself. */
        List<String> local(Ast.Path p) {
            List<String> parts = p.parts();
            if (alias != null && parts.get(0).equals(alias)) {
                return parts.subList(1, parts.size());
            }
            List<String> join = joins.get(parts.get(0));
            if (join != null) {
                List<String> out = new ArrayList<>(join);
                out.addAll(parts.subList(1, parts.size()));
                return out;
            }
            return parts;
        }

        Resolved resolve(List<String> parts, Ast.Path at) {
            ClassDef root = schema.byName(cls.collection());
            String first = parts.get(0);
            if (parts.size() == 1 && (first.equals(root.idField()) || first.equals(root.versionField()))) {
                return new Resolved(first, TypeDef.scalar(ValueType.INT64), null, List.of());
            }
            for (int i = 1; i <= parts.size(); i++) {
                String prefix = String.join(".", parts.subList(0, i));
                TypeDef t = planner.typeOf(prefix);
                if (t == null) {
                    throw new OqlException("unknown field '" + prefix + "' in " + cls.simpleName(), at.line(), at.column());
                }
                if (t.kind() == ValueType.REF && i < parts.size()) {
                    return new Resolved(prefix, t, t.name(), parts.subList(i, parts.size()));
                }
                if (i == parts.size()) {
                    return new Resolved(prefix, t, null, List.of());
                }
            }
            throw new IllegalStateException();
        }

        Scope target(String className) {
            ClassDef c = schema.byName(className);
            if (c == null) {
                throw new OqlException("referenced class " + className + " is not stored");
            }
            return new Scope(c, null, Map.of());
        }
    }

    private final OqlHost host;
    private final ReadView view;
    private final Schema schema;
    private final Map<String, Object> named;
    private final Map<Integer, Object> positional;
    private final Map<Long, DynamicObject> derefCache = new HashMap<>();

    Engine(OqlHost host, ReadView view, Map<String, Object> named, Map<Integer, Object> positional) {
        this.host = host;
        this.view = view;
        this.schema = host.registry().schema();
        this.named = named;
        this.positional = positional;
    }

    // ---------------------------------------------------------------- scope

    Scope scope(Ast.From from, List<Ast.Join> joins) {
        ClassDef cls;
        try {
            cls = host.storedClass(from.entity());
        } catch (IllegalArgumentException e) {
            throw new OqlException(e.getMessage(), from.line(), from.column());
        }
        Map<String, List<String>> joinMap = new LinkedHashMap<>();
        Scope s = new Scope(cls, from.alias(), joinMap);
        for (Ast.Join j : joins) {
            List<String> local = s.local(j.path());
            Resolved r = s.resolve(local, j.path());
            if (r.type().kind() != ValueType.REF || r.refTarget() != null) {
                throw new OqlException("JOIN path must end at a reference field", j.path().line(), j.path().column());
            }
            joinMap.put(j.alias(), local);
        }
        return s;
    }

    // ---------------------------------------------------------------- parameters and constants

    Object param(Ast.Param p) {
        boolean byName = p.name() != null;
        if (byName ? !named.containsKey(p.name()) : !positional.containsKey(p.position())) {
            throw new OqlException("missing parameter " + (byName ? ":" + p.name() : "?" + p.position()), p.line(), p.column());
        }
        Object v = byName ? named.get(p.name()) : positional.get(p.position());
        return v instanceof Enum<?> e ? e.name() : v;
    }

    private static boolean constant(Ast.Expr e) {
        if (e instanceof Ast.Lit || e instanceof Ast.Param) {
            return true;
        }
        if (e instanceof Ast.Unary u) {
            return constant(u.expr());
        }
        if (e instanceof Ast.Binary b) {
            return constant(b.left()) && constant(b.right());
        }
        if (e instanceof Ast.Func f) {
            return f.args().stream().allMatch(Engine::constant);
        }
        return false;
    }

    Object constValue(Ast.Expr e) {
        return Eval.eval(e, new Eval.Env() {
            @Override
            public Object path(Ast.Path p) {
                throw new OqlException("expected a constant", p.line(), p.column());
            }

            @Override
            public Object param(Ast.Param p) {
                return Engine.this.param(p);
            }

            @Override
            public Object agg(Ast.Agg a) {
                throw new OqlException("aggregate not allowed here");
            }

            @Override
            public String className(String alias) {
                throw new OqlException("TYPE() not allowed here");
            }
        });
    }

    long longValue(Ast.Expr e, String what) {
        Object v = constValue(e);
        if (!(v instanceof Number n) || n.longValue() < 0) {
            throw new OqlException(what + " must be a non-negative integer");
        }
        return n.longValue();
    }

    // ---------------------------------------------------------------- row evaluation

    Eval.Env rowEnv(DynamicObject o, Scope s) {
        return new Eval.Env() {
            @Override
            public Object path(Ast.Path p) {
                return value(o, s, s.local(p));
            }

            @Override
            public Object param(Ast.Param p) {
                return Engine.this.param(p);
            }

            @Override
            public Object agg(Ast.Agg a) {
                throw new OqlException("aggregate functions need GROUP BY or a grouped select");
            }

            @Override
            public String className(String alias) {
                return o.className();
            }
        };
    }

    /** Value of a local path on an object, following references. */
    Object value(DynamicObject o, Scope s, List<String> parts) {
        if (parts.isEmpty()) {
            return o;
        }
        ClassDef root = schema.byName(s.cls.collection());
        Object cur = o;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (cur instanceof Ref<?> r) {
                cur = deref(r.oid());
                root = cur == null ? null : schema.byName(schema.byName(((DynamicObject) cur).className()).collection());
            }
            if (cur instanceof DynamicObject d) {
                if (root != null && part.equals(root.idField())) {
                    cur = d.oid();
                } else if (root != null && part.equals(root.versionField())) {
                    cur = d.version();
                } else {
                    cur = d.get(part);
                }
            } else if (cur instanceof Map<?, ?> m) {
                cur = m.get(part);
            } else {
                return null;
            }
        }
        return cur;
    }

    private DynamicObject deref(long oid) {
        return derefCache.computeIfAbsent(oid, k -> host.getDynamic(view, k));
    }

    // ---------------------------------------------------------------- WHERE → Pred

    Pred<DynamicObject> pred(Ast.Expr e, Scope s) {
        if (e instanceof Ast.Binary b && (b.op().equals("AND") || b.op().equals("OR"))) {
            Pred<DynamicObject> l = pred(b.left(), s);
            Pred<DynamicObject> r = pred(b.right(), s);
            return b.op().equals("AND") ? l.and(r) : l.or(r);
        }
        if (e instanceof Ast.Unary u && u.op().equals("NOT")) {
            return pred(u.expr(), s).negate();
        }
        Pred<DynamicObject> pushed = null;
        try {
            pushed = pushdown(e, s);
        } catch (IllegalArgumentException ex) {
            pushed = null;   // literal not representable in the field type: evaluate instead
        }
        return pushed != null ? pushed : residual(e, s);
    }

    private Pred<DynamicObject> residual(Ast.Expr e, Scope s) {
        Predicate<DynamicObject> p = o -> Eval.truth(Eval.eval(e, rowEnv(o, s)));
        return new Pred.Residual<>(new Predicate<DynamicObject>() {
            @Override
            public boolean test(DynamicObject o) {
                return p.test(o);
            }

            @Override
            public String toString() {
                return Render.render(e);
            }
        });
    }

    /** A pushed-down predicate for {@code e}, or null when it must be evaluated per object. */
    private Pred<DynamicObject> pushdown(Ast.Expr e, Scope s) {
        if (e instanceof Ast.Binary b && isCmp(b.op())) {
            if (b.left() instanceof Ast.Path p && constant(b.right())) {
                return onPath(s, p, (sc, f) -> cmp(f, b.op(), constValue(b.right())));
            }
            if (b.right() instanceof Ast.Path p && constant(b.left())) {
                return onPath(s, p, (sc, f) -> cmp(f, flip(b.op()), constValue(b.left())));
            }
            return null;
        }
        if (e instanceof Ast.Between b && b.expr() instanceof Ast.Path p && constant(b.lo()) && constant(b.hi())) {
            Object lo = constValue(b.lo());
            Object hi = constValue(b.hi());
            if (lo == null || hi == null) {
                return null;
            }
            Pred<DynamicObject> r = onPath(s, p, (sc, f) -> f.between(lo, hi));
            return r == null ? null : b.not() ? r.negate() : r;
        }
        if (e instanceof Ast.InList in && in.expr() instanceof Ast.Path p && in.items().stream().allMatch(Engine::constant)) {
            List<Object> values = new ArrayList<>();
            for (Ast.Expr x : in.items()) {
                Object v = constValue(x);
                if (v == null) {
                    return null;
                }
                values.add(v);
            }
            Pred<DynamicObject> r = onPath(s, p, (sc, f) -> f.in(values));
            return r == null ? null : in.not() ? r.negate() : r;
        }
        if (e instanceof Ast.Like l && l.expr() instanceof Ast.Path p && l.escape() == null && constant(l.pattern())
                && constValue(l.pattern()) instanceof String pattern && Eval.prefixOf(pattern) != null) {
            Pred<DynamicObject> r = onPath(s, p, (sc, f) -> f.typeDef().kind() == ValueType.STRING ? f.startsWith(Eval.prefixOf(pattern)) : null);
            return r == null ? null : l.not() ? r.negate() : r;
        }
        if (e instanceof Ast.IsNull n && n.expr() instanceof Ast.Path p) {
            List<String> local = s.local(p);
            if (local.isEmpty()) {
                return null;
            }
            Resolved r = s.resolve(local, p);
            if (r.refTarget() != null) {
                return null;
            }
            Pred<DynamicObject> isNull = s.coll.field(r.local()).isNull();
            return n.not() ? isNull.negate() : isNull;
        }
        if (e instanceof Ast.Member m && m.collection() instanceof Ast.Path p && constant(m.value())) {
            Object v = constValue(m.value());
            List<String> local = s.local(p);
            Resolved r = s.resolve(local, p);
            if (v == null || r.refTarget() != null || r.type().kind() != ValueType.MULTI) {
                return null;
            }
            Pred<DynamicObject> c = s.coll.field(r.local()).contains(v);
            return m.not() ? c.negate() : c;
        }
        if (e instanceof Ast.TypeIn t) {
            if (s.alias == null || !t.alias().equals(s.alias)) {
                throw new OqlException("TYPE() needs the FROM alias", t.line(), t.column());
            }
            Set<String> names = new HashSet<>();
            for (String c : t.classes()) {
                try {
                    names.add(host.storedClass(c).name());
                } catch (IllegalArgumentException ex) {
                    throw new OqlException(ex.getMessage(), t.line(), t.column());
                }
            }
            Pred<DynamicObject> r = new Pred.TypeIn<>(names);
            return t.not() ? r.negate() : r;
        }
        return null;
    }

    /** Applies {@code make} to the field of a scalar path, crossing references with sub-queries. */
    private Pred<DynamicObject> onPath(Scope s, Ast.Path p, BiFunction<Scope, DynField, Pred<DynamicObject>> make) {
        List<String> local = s.local(p);
        if (local.isEmpty()) {
            return null;
        }
        Resolved r = s.resolve(local, p);
        if (r.refTarget() != null) {
            Scope target = s.target(r.refTarget());
            Pred<DynamicObject> inner = onPath(target, new Ast.Path(r.rest(), p.line(), p.column()), make);
            return inner == null ? null : new Pred.DynRefMatch<>(s.coll.field(r.local()), target.cls.name(), inner);
        }
        if (!r.type().kind().isScalar()) {
            return null;
        }
        return make.apply(s, s.coll.field(r.local()));
    }

    private static boolean isCmp(String op) {
        return switch (op) {
            case "=", "<>", "<", "<=", ">", ">=" -> true;
            default -> false;
        };
    }

    private static String flip(String op) {
        return switch (op) {
            case "<" -> ">";
            case "<=" -> ">=";
            case ">" -> "<";
            case ">=" -> "<=";
            default -> op;
        };
    }

    private static Pred<DynamicObject> cmp(DynField f, String op, Object v) {
        if (v == null) {
            return null;
        }
        return switch (op) {
            case "=" -> f.eq(v);
            case "<>" -> f.notEq(v).and(f.isNotNull());
            case "<" -> f.lt(v);
            case "<=" -> f.le(v);
            case ">" -> f.gt(v);
            default -> f.ge(v);
        };
    }

    // ---------------------------------------------------------------- SELECT

    Result select(Ast.Select q, long limitOverride, long offsetOverride, boolean explainOnly) {
        Scope s = scope(q.from(), q.joins());
        StringBuilder plan = new StringBuilder();
        QueryStream<DynamicObject> stream = new QueryStream<>(host, QueryStream.Target.dynamic(s.cls.name()), view);
        for (Ast.Join j : q.joins()) {
            if (!j.left()) {
                stream = stream.filter(s.coll.field(String.join(".", s.local(j.path()))).isNotNull());
            }
        }
        if (q.where() != null) {
            stream = stream.filter(pred(q.where(), s));
        }
        long limit = limitOverride >= 0 ? limitOverride : q.limit() == null ? -1 : longValue(q.limit(), "LIMIT");
        long offset = offsetOverride >= 0 ? offsetOverride : q.offset() == null ? 0 : longValue(q.offset(), "OFFSET");

        boolean objects = q.shape() == Ast.Shape.STAR || (q.shape() == Ast.Shape.ITEMS && q.items().size() == 1
                && q.items().get(0).expr() instanceof Ast.Path p && s.local(p).isEmpty());
        List<Ast.Expr> itemExprs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (!objects) {
            for (Ast.SelectItem it : q.items()) {
                itemExprs.add(it.expr());
                names.add(it.alias() != null ? it.alias() : it.text());
            }
        } else {
            names.add(s.alias != null ? s.alias : s.cls.simpleName());
        }
        boolean grouped = !q.groupBy().isEmpty() || hasAgg(itemExprs) || hasAgg(q.having())
                || q.orderBy().stream().anyMatch(o -> hasAgg(o.expr()));
        if (grouped && objects) {
            throw new OqlException("cannot select whole objects together with GROUP BY / aggregates");
        }
        if (!grouped && q.having() != null) {
            throw new OqlException("HAVING needs GROUP BY or aggregates");
        }
        List<Ast.Expr> orderExprs = new ArrayList<>();
        for (Ast.OrderItem o : q.orderBy()) {
            orderExprs.add(orderTarget(o.expr(), q, itemExprs, objects));
        }
        for (Ast.Expr e : itemExprs) {
            validate(e, s);
        }
        validate(q.where(), s);
        q.groupBy().forEach(g -> validate(g, s));
        validate(q.having(), s);
        orderExprs.forEach(o -> validate(o, s));
        List<TypeDef> enumOrder = new ArrayList<>();
        orderExprs.forEach(o -> enumOrder.add(enumType(o, s)));

        if (grouped) {
            return grouped(q, s, stream, itemExprs, names, orderExprs, enumOrder, limit, offset, plan, explainOnly);
        }

        // ---- plain select: push ORDER BY (plain stored paths) and slicing down when possible
        List<Order<DynamicObject>> pushedOrder = new ArrayList<>();
        for (int i = 0; i < orderExprs.size(); i++) {
            Order<DynamicObject> o = pushOrder(orderExprs.get(i), q.orderBy().get(i), s);
            if (o == null) {
                pushedOrder = null;
                break;
            }
            pushedOrder.add(o);
        }
        boolean sortPushed = pushedOrder != null && !pushedOrder.isEmpty();
        if (sortPushed) {
            stream = stream.sorted(pushedOrder.toArray(Order[]::new));
        }
        boolean slicePushed = (sortPushed || orderExprs.isEmpty()) && !(q.distinct() && !objects);
        if (slicePushed) {
            if (offset > 0) {
                stream = stream.skip(offset);
            }
            if (limit >= 0) {
                stream = stream.limit(limit);
            }
        }
        plan.append(stream.explain());
        plan.append("OQL ").append(objects ? "objects" : "projection " + names)
                .append(orderExprs.isEmpty() ? "" : sortPushed ? "  [ORDER BY pushed down]" : "  [ORDER BY in memory]")
                .append(q.distinct() ? "  [DISTINCT]" : "").append('\n');
        if (explainOnly) {
            return new Result(List.of("plan"), List.of(), List.of(), q.shape(), false, plan.toString());
        }

        List<DynamicObject> found = stream.toList();
        List<Object[]> rows = new ArrayList<>();
        List<Object[]> sortKeys = new ArrayList<>();
        for (DynamicObject o : found) {
            Eval.Env env = rowEnv(o, s);
            Object[] row = new Object[objects ? 1 : itemExprs.size()];
            if (objects) {
                row[0] = o;
            } else {
                for (int i = 0; i < row.length; i++) {
                    row[i] = Eval.eval(itemExprs.get(i), env);
                }
            }
            rows.add(row);
            if (!sortPushed && !orderExprs.isEmpty()) {
                Object[] k = new Object[orderExprs.size()];
                for (int i = 0; i < k.length; i++) {
                    k[i] = enumRank(enumOrder.get(i), Eval.eval(orderExprs.get(i), env));
                }
                sortKeys.add(k);
            }
        }
        if (!sortPushed && !orderExprs.isEmpty()) {
            sortRows(rows, sortKeys, q.orderBy());
        }
        if (q.distinct() && !objects) {
            rows = distinct(rows);
        }
        if (!slicePushed) {
            rows = slice(rows, offset, limit);
        }
        List<DynamicObject> objs = new ArrayList<>();
        if (objects) {
            rows.forEach(r -> objs.add((DynamicObject) r[0]));
        }
        return new Result(names, rows, objs, q.shape(), objects, plan.toString());
    }

    /** Checks that every path in {@code e} names a stored field (through references). */
    private void validate(Ast.Expr e, Scope s) {
        if (e == null) {
            return;
        }
        if (e instanceof Ast.Path p) {
            checkPath(s.local(p), s, p);
            return;
        }
        children(e).forEach(c -> validate(c, s));
    }

    private void checkPath(List<String> local, Scope s, Ast.Path at) {
        if (local.isEmpty()) {
            return;
        }
        Resolved r = s.resolve(local, at);
        if (r.refTarget() != null) {
            checkPath(r.rest(), s.target(r.refTarget()), at);
        }
    }

    /** Enum type of a path expression (sorted by declaration order), else null. */
    private TypeDef enumType(Ast.Expr e, Scope s) {
        if (!(e instanceof Ast.Path p) || s.local(p).isEmpty()) {
            return null;
        }
        Scope cur = s;
        List<String> parts = s.local(p);
        while (true) {
            Resolved r = cur.resolve(parts, p);
            if (r.refTarget() == null) {
                return r.type().kind() == ValueType.ENUM ? r.type() : null;
            }
            cur = cur.target(r.refTarget());
            parts = r.rest();
        }
    }

    private static Object enumRank(TypeDef enumType, Object v) {
        return enumType != null && v instanceof String name ? (Object) (long) enumType.constantIndex(name) : v;
    }

    /** ORDER BY item → expression (select aliases and 1-based positions resolve to select items). */
    private Ast.Expr orderTarget(Ast.Expr e, Ast.Select q, List<Ast.Expr> items, boolean objects) {
        if (e instanceof Ast.Lit l && l.value() instanceof Long pos && !objects) {
            if (pos < 1 || pos > items.size()) {
                throw new OqlException("ORDER BY position " + pos + " is out of range");
            }
            return items.get((int) (pos - 1));
        }
        if (e instanceof Ast.Path p && p.parts().size() == 1 && !objects) {
            for (Ast.SelectItem it : q.items()) {
                if (p.parts().get(0).equals(it.alias())) {
                    return it.expr();
                }
            }
        }
        return e;
    }

    private Order<DynamicObject> pushOrder(Ast.Expr e, Ast.OrderItem item, Scope s) {
        if (!(e instanceof Ast.Path p)) {
            return null;
        }
        List<String> local = s.local(p);
        if (local.isEmpty()) {
            return null;
        }
        Resolved r = s.resolve(local, p);
        if (r.refTarget() != null || !r.type().kind().isScalar() || r.type().kind() == ValueType.REF) {
            return null;
        }
        DynField f = s.coll.field(r.local());
        Order<DynamicObject> o = item.desc() ? f.desc() : f.asc();
        if (item.nullsFirst() != null) {
            o = item.nullsFirst() ? o.nullsFirst() : o.nullsLast();
        }
        return o;
    }

    private static void sortRows(List<Object[]> rows, List<Object[]> keys, List<Ast.OrderItem> items) {
        Integer[] idx = new Integer[rows.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Comparator<Integer> c = (a, b) -> {
            for (int i = 0; i < items.size(); i++) {
                Ast.OrderItem it = items.get(i);
                Object x = keys.get(a)[i];
                Object y = keys.get(b)[i];
                boolean nullsFirst = it.nullsFirst() != null ? it.nullsFirst() : it.desc();
                int r;
                if (x == null || y == null) {
                    if (x == y) {
                        continue;
                    }
                    r = (x == null) == nullsFirst ? -1 : 1;
                } else {
                    r = Eval.compare(x, y);
                    if (it.desc()) {
                        r = -r;
                    }
                }
                if (r != 0) {
                    return r;
                }
            }
            return 0;
        };
        Arrays.sort(idx, c);
        List<Object[]> sorted = new ArrayList<>(rows.size());
        for (int i : idx) {
            sorted.add(rows.get(i));
        }
        rows.clear();
        rows.addAll(sorted);
    }

    private static List<Object[]> distinct(List<Object[]> rows) {
        LinkedHashMap<List<Object>, Object[]> seen = new LinkedHashMap<>();
        for (Object[] r : rows) {
            seen.putIfAbsent(Arrays.asList(r), r);
        }
        return new ArrayList<>(seen.values());
    }

    private static List<Object[]> slice(List<Object[]> rows, long offset, long limit) {
        int from = (int) Math.min(offset, rows.size());
        int to = limit < 0 ? rows.size() : (int) Math.min(rows.size(), from + limit);
        return new ArrayList<>(rows.subList(from, to));
    }

    // ---------------------------------------------------------------- grouping

    private static boolean hasAgg(Ast.Expr e) {
        if (e == null) {
            return false;
        }
        if (e instanceof Ast.Agg) {
            return true;
        }
        return children(e).stream().anyMatch(Engine::hasAgg);
    }

    private static boolean hasAgg(List<Ast.Expr> es) {
        return es.stream().anyMatch(Engine::hasAgg);
    }

    private static List<Ast.Expr> children(Ast.Expr e) {
        List<Ast.Expr> c = new ArrayList<>();
        if (e instanceof Ast.Unary u) {
            c.add(u.expr());
        } else if (e instanceof Ast.Binary b) {
            c.add(b.left());
            c.add(b.right());
        } else if (e instanceof Ast.Between b) {
            c.addAll(List.of(b.expr(), b.lo(), b.hi()));
        } else if (e instanceof Ast.InList in) {
            c.add(in.expr());
            c.addAll(in.items());
        } else if (e instanceof Ast.Like l) {
            c.add(l.expr());
            c.add(l.pattern());
            if (l.escape() != null) {
                c.add(l.escape());
            }
        } else if (e instanceof Ast.IsNull n) {
            c.add(n.expr());
        } else if (e instanceof Ast.IsEmpty n) {
            c.add(n.expr());
        } else if (e instanceof Ast.Member m) {
            c.add(m.value());
            c.add(m.collection());
        } else if (e instanceof Ast.Case k) {
            c.addAll(k.whens());
            c.addAll(k.thens());
            if (k.otherwise() != null) {
                c.add(k.otherwise());
            }
        } else if (e instanceof Ast.Func f) {
            c.addAll(f.args());
        } else if (e instanceof Ast.Agg a && a.arg() != null) {
            c.add(a.arg());
        }
        return c;
    }

    private void collectAggs(Ast.Expr e, Map<String, Ast.Agg> out, Scope s) {
        if (e == null) {
            return;
        }
        if (e instanceof Ast.Agg a) {
            if (a.arg() != null && hasAgg(a.arg())) {
                throw new OqlException("aggregates cannot be nested");
            }
            out.putIfAbsent(Render.render(normalize(a, s)), a);
            return;
        }
        children(e).forEach(c -> collectAggs(c, out, s));
    }

    private Result grouped(Ast.Select q, Scope s, QueryStream<DynamicObject> stream, List<Ast.Expr> items,
                           List<String> names, List<Ast.Expr> orderExprs, List<TypeDef> enumOrder, long limit, long offset,
                           StringBuilder plan, boolean explainOnly) {
        Map<String, Ast.Agg> aggs = new LinkedHashMap<>();
        items.forEach(e -> collectAggs(e, aggs, s));
        collectAggs(q.having(), aggs, s);
        orderExprs.forEach(e -> collectAggs(e, aggs, s));
        List<String> keyRender = new ArrayList<>();
        for (Ast.Expr g : q.groupBy()) {
            if (hasAgg(g)) {
                throw new OqlException("GROUP BY cannot contain aggregates");
            }
            keyRender.add(Render.render(normalize(g, s)));
        }

        // fast path: keys and aggregate arguments are plain stored scalar paths of this class
        List<String> keyPaths = new ArrayList<>();
        boolean fast = true;
        for (Ast.Expr g : q.groupBy()) {
            String p = plainPath(g, s);
            fast &= p != null;
            keyPaths.add(p);
        }
        List<Aggregator.Fn> fns = new ArrayList<>();
        List<String> argPaths = new ArrayList<>();
        for (Ast.Agg a : aggs.values()) {
            Aggregator.Fn fn = fn(a);
            String p = a.arg() == null ? null : plainPath(a.arg(), s);
            fast &= a.arg() == null || p != null;
            fast &= !a.distinct() || a.fn().equals("COUNT");
            fns.add(fn);
            argPaths.add(p);
        }
        plan.append(stream.explain()).append("OQL GROUP BY ").append(keyRender.isEmpty() ? "(all rows)" : keyRender)
                .append(fast ? "  [canonical fast path]" : "  [evaluated per object]").append('\n');
        if (explainOnly) {
            return new Result(List.of("plan"), List.of(), List.of(), q.shape(), false, plan.toString());
        }

        List<Object[]> groupKeys = new ArrayList<>();
        List<Object[]> groupAggs = new ArrayList<>();
        if (fast) {
            for (Aggregator.Group g : stream.canonicalGroups(keyPaths, fns, argPaths)) {
                Object[] k = g.keys();
                for (int i = 0; i < k.length; i++) {
                    k[i] = dynamicValue(s, keyPaths.get(i), k[i]);
                }
                Object[] v = g.values();
                int i = 0;
                for (Ast.Agg a : aggs.values()) {
                    if ((a.fn().equals("MIN") || a.fn().equals("MAX")) && argPaths.get(i) != null) {
                        v[i] = dynamicValue(s, argPaths.get(i), v[i]);
                    }
                    i++;
                }
                groupKeys.add(k);
                groupAggs.add(v);
            }
        } else {
            evaluateGroups(q, s, stream, aggs, groupKeys, groupAggs);
        }

        List<String> aggKeys = new ArrayList<>(aggs.keySet());
        List<Object[]> rows = new ArrayList<>();
        List<Object[]> sortKeys = new ArrayList<>();
        for (int g = 0; g < groupKeys.size(); g++) {
            Map<String, Object> bound = new HashMap<>();
            for (int i = 0; i < keyRender.size(); i++) {
                bound.put(keyRender.get(i), groupKeys.get(g)[i]);
            }
            for (int i = 0; i < aggKeys.size(); i++) {
                bound.put(aggKeys.get(i), groupAggs.get(g)[i]);
            }
            Eval.Env env = groupEnv(s, bound);
            if (q.having() != null && !Eval.truth(Eval.eval(bind(q.having(), s, bound), env))) {
                continue;
            }
            Object[] row = new Object[items.size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = Eval.eval(bind(items.get(i), s, bound), env);
            }
            Object[] k = new Object[orderExprs.size()];
            for (int i = 0; i < k.length; i++) {
                k[i] = enumRank(enumOrder.get(i), Eval.eval(bind(orderExprs.get(i), s, bound), env));
            }
            rows.add(row);
            sortKeys.add(k);
        }
        if (!orderExprs.isEmpty()) {
            sortRows(rows, sortKeys, q.orderBy());
        }
        if (q.distinct()) {
            rows = distinct(rows);
        }
        rows = slice(rows, offset, limit);
        return new Result(names, rows, List.of(), q.shape(), false, plan.toString());
    }

    /** Stored path of {@code e} if it is a plain local scalar path, else null. */
    private String plainPath(Ast.Expr e, Scope s) {
        if (!(e instanceof Ast.Path p)) {
            return null;
        }
        List<String> local = s.local(p);
        if (local.isEmpty()) {
            return null;
        }
        Resolved r = s.resolve(local, p);
        return r.refTarget() == null && r.type().kind().isScalar() ? r.local() : null;
    }

    private Object dynamicValue(Scope s, String path, Object canonical) {
        TypeDef t = s.resolve(List.of(path.split("\\.")), new Ast.Path(List.of(path), -1, -1)).type();
        return DynamicCodec.toDynamic(t, canonical);
    }

    private static Aggregator.Fn fn(Ast.Agg a) {
        return switch (a.fn()) {
            case "COUNT" -> a.distinct() ? Aggregator.Fn.COUNT_DISTINCT : Aggregator.Fn.COUNT;
            case "SUM" -> Aggregator.Fn.SUM;
            case "AVG" -> Aggregator.Fn.AVG;
            case "MIN" -> Aggregator.Fn.MIN;
            default -> Aggregator.Fn.MAX;
        };
    }

    /** Groups by evaluating expressions on every matching object (general path). */
    private void evaluateGroups(Ast.Select q, Scope s, QueryStream<DynamicObject> stream, Map<String, Ast.Agg> aggs,
                                List<Object[]> groupKeys, List<Object[]> groupAggs) {
        Map<List<Object>, Acc[]> groups = new LinkedHashMap<>();
        for (DynamicObject o : stream.toList()) {
            Eval.Env env = rowEnv(o, s);
            List<Object> key = new ArrayList<>();
            for (Ast.Expr g : q.groupBy()) {
                key.add(Eval.eval(g, env));
            }
            Acc[] acc = groups.computeIfAbsent(key, k -> {
                Acc[] a = new Acc[aggs.size()];
                int i = 0;
                for (Ast.Agg x : aggs.values()) {
                    a[i++] = new Acc(x);
                }
                return a;
            });
            int i = 0;
            for (Ast.Agg a : aggs.values()) {
                acc[i++].add(a.arg() == null ? Boolean.TRUE : Eval.eval(a.arg(), env));
            }
        }
        if (q.groupBy().isEmpty() && groups.isEmpty()) {
            Acc[] a = new Acc[aggs.size()];
            int i = 0;
            for (Ast.Agg x : aggs.values()) {
                a[i++] = new Acc(x);
            }
            groups.put(List.of(), a);
        }
        for (Map.Entry<List<Object>, Acc[]> e : groups.entrySet()) {
            groupKeys.add(e.getKey().toArray());
            Object[] v = new Object[e.getValue().length];
            for (int i = 0; i < v.length; i++) {
                v[i] = e.getValue()[i].result();
            }
            groupAggs.add(v);
        }
    }

    /** Aggregate accumulator for the evaluated path (SQL semantics: NULL inputs ignored). */
    private static class Acc {
        private final Ast.Agg agg;
        private long count;
        private long longSum;
        private double doubleSum;
        private boolean floating;
        private Object best;
        private final Set<Object> distinct = new LinkedHashSet<>();

        Acc(Ast.Agg agg) {
            this.agg = agg;
        }

        void add(Object v) {
            if (v == null) {
                return;
            }
            if (agg.distinct() && !distinct.add(v instanceof Number n && !(v instanceof Double) ? (Object) n.longValue() : v)) {
                return;
            }
            count++;
            switch (agg.fn()) {
                case "SUM", "AVG" -> {
                    if (!(v instanceof Number n)) {
                        throw new OqlException(agg.fn() + " needs numbers, got " + v);
                    }
                    if (v instanceof Double || v instanceof Float) {
                        floating = true;
                    }
                    longSum += n.longValue();
                    doubleSum += n.doubleValue();
                }
                case "MIN" -> best = best == null || Eval.compare(v, best) < 0 ? v : best;
                case "MAX" -> best = best == null || Eval.compare(v, best) > 0 ? v : best;
                default -> {
                }
            }
        }

        Object result() {
            return switch (agg.fn()) {
                case "COUNT" -> count;
                case "SUM" -> count == 0 ? null : floating ? (Object) doubleSum : (Object) longSum;
                case "AVG" -> count == 0 ? null : doubleSum / count;
                default -> best;
            };
        }
    }

    /** Expression with every path normalized to its local path (for matching GROUP BY items and aggregates). */
    private Ast.Expr normalize(Ast.Expr e, Scope s) {
        if (e instanceof Ast.Path p) {
            List<String> local = s.local(p);
            return new Ast.Path(local.isEmpty() ? p.parts() : local, -1, -1);
        }
        if (e instanceof Ast.Agg a) {
            return a.arg() == null ? a : new Ast.Agg(a.fn(), normalize(a.arg(), s), a.distinct());
        }
        return Render.map(e, c -> normalize(c, s));
    }

    /** Replaces group keys and aggregates in {@code e} by their values for one group. */
    private Ast.Expr bind(Ast.Expr e, Scope s, Map<String, Object> bound) {
        String key = Render.render(normalize(e, s));
        if (bound.containsKey(key)) {
            return new Ast.Lit(bound.get(key));
        }
        return Render.map(e, c -> bind(c, s, bound));
    }

    private Eval.Env groupEnv(Scope s, Map<String, Object> bound) {
        return new Eval.Env() {
            @Override
            public Object path(Ast.Path p) {
                throw new OqlException("'" + p.text() + "' must appear in GROUP BY or inside an aggregate", p.line(), p.column());
            }

            @Override
            public Object param(Ast.Param p) {
                return Engine.this.param(p);
            }

            @Override
            public Object agg(Ast.Agg a) {
                return bound.get(Render.render(normalize(a, s)));
            }

            @Override
            public String className(String alias) {
                throw new OqlException("TYPE() is not allowed in grouped expressions");
            }
        };
    }

    // ---------------------------------------------------------------- UPDATE / DELETE

    long update(Ast.Update u) {
        Scope s = scope(u.from(), List.of());
        QueryStream<DynamicObject> stream = new QueryStream<>(host, QueryStream.Target.dynamic(s.cls.name()), view);
        if (u.where() != null) {
            stream = stream.filter(pred(u.where(), s));
        }
        List<String> paths = new ArrayList<>();
        for (Ast.SetItem it : u.sets()) {
            List<String> local = s.local(it.path());
            Resolved r = s.resolve(local, it.path());
            ClassDef root = schema.byName(s.cls.collection());
            if (r.refTarget() != null || r.local().equals(root.idField()) || r.local().equals(root.versionField())) {
                throw new OqlException("cannot SET " + it.path().text(), it.path().line(), it.path().column());
            }
            paths.add(r.local());
        }
        List<DynamicObject> objects = stream.toList();
        for (DynamicObject o : objects) {
            Eval.Env env = rowEnv(o, s);
            Object[] values = new Object[paths.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = Eval.eval(u.sets().get(i).value(), env);
            }
            for (int i = 0; i < values.length; i++) {
                o.set(paths.get(i), values[i]);
            }
        }
        try (WriteBatch b = host.database().batch()) {
            objects.forEach(b::update);
            b.commit();
        } catch (IllegalArgumentException e) {
            throw new OqlException("UPDATE failed: " + e.getMessage());
        }
        return objects.size();
    }

    long delete(Ast.Delete d) {
        Scope s = scope(d.from(), List.of());
        QueryStream<DynamicObject> stream = new QueryStream<>(host, QueryStream.Target.dynamic(s.cls.name()), view);
        if (d.where() != null) {
            stream = stream.filter(pred(d.where(), s));
        }
        return stream.deleteAll();
    }

    String explainWrite(Ast.Statement st) {
        Ast.From from = st instanceof Ast.Update u ? u.from() : ((Ast.Delete) st).from();
        Ast.Expr where = st instanceof Ast.Update u ? u.where() : ((Ast.Delete) st).where();
        Scope s = scope(from, List.of());
        QueryStream<DynamicObject> stream = new QueryStream<>(host, QueryStream.Target.dynamic(s.cls.name()), view);
        if (where != null) {
            stream = stream.filter(pred(where, s));
        }
        return stream.explain() + "OQL " + (st instanceof Ast.Update ? "UPDATE" : "DELETE") + " in one batch\n";
    }

    /** Values of a row in the dynamic representation, for the {@code Row} API. */
    static Row row(List<String> names, Object[] values) {
        return new Row(names, values);
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
