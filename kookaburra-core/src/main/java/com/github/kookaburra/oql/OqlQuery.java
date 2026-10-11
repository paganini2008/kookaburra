package com.github.kookaburra.oql;

import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.engine.Oids;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.query.Row;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An OQL statement with parameters (design §9.9). Parsed once; every execution runs on its own snapshot.
 *
 * <pre>{@code
 * List<Person> ps = db.oql("SELECT p FROM Person p WHERE p.city = :city ORDER BY p.age DESC", Person.class)
 *                     .param("city", "Sydney").limit(10).list();
 * List<Row> rows  = db.oql("SELECT p.city, COUNT(*) AS n FROM Person p GROUP BY p.city HAVING COUNT(*) > 1").rows();
 * long n          = db.oql("UPDATE Person p SET p.score = p.score + 1 WHERE p.age > 30").execute();
 * }</pre>
 *
 * @param <T> result type of {@link #list()}
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class OqlQuery<T> {

    private final OqlHost host;
    private final String text;
    private final Ast.Statement ast;
    private final Class<T> type;
    private final Map<String, Object> named = new LinkedHashMap<>();
    private final Map<Integer, Object> positional = new HashMap<>();
    private long limit = -1;
    private long offset = -1;

    /** Internal: created by {@code ObjectDB.oql(..)}. */
    public OqlQuery(OqlHost host, String text, Class<T> type) {
        this.host = host;
        this.text = text;
        this.ast = AstBuilder.parse(text);
        this.type = type;
    }

    private OqlQuery(OqlQuery<?> from, Class<T> type) {
        this.host = from.host;
        this.text = from.text;
        this.ast = from.ast;
        this.type = type;
        this.named.putAll(from.named);
        this.positional.putAll(from.positional);
        this.limit = from.limit;
        this.offset = from.offset;
    }

    public String text() {
        return text;
    }

    /** Binds {@code :name}. Enum values bind as their constant name, {@link Ref}s as references. */
    public OqlQuery<T> param(String name, Object value) {
        named.put(name, value);
        return this;
    }

    /** Binds {@code ?position} (1-based). */
    public OqlQuery<T> param(int position, Object value) {
        positional.put(position, value);
        return this;
    }

    /** Overrides the statement's LIMIT. */
    public OqlQuery<T> limit(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative limit");
        }
        limit = n;
        return this;
    }

    /** Overrides the statement's OFFSET. */
    public OqlQuery<T> offset(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("negative offset");
        }
        offset = n;
        return this;
    }

    /** The same statement returning {@link DynamicObject}s (no entity classes needed). */
    public OqlQuery<DynamicObject> dynamic() {
        return new OqlQuery<>(this, DynamicObject.class);
    }

    /** True for SELECT statements. */
    public boolean isSelect() {
        return ast instanceof Ast.Select;
    }

    /** True for {@code EXPLAIN …} statements. */
    public boolean isExplain() {
        return ast.explain();
    }

    /** True if the SELECT has its own LIMIT or OFFSET. */
    public boolean isSliced() {
        return ast instanceof Ast.Select q && (q.limit() != null || q.offset() != null);
    }

    // ---------------------------------------------------------------- execution

    /**
     * Results: whole objects for {@code SELECT p} / {@code SELECT *} (entities, or {@link DynamicObject}s
     * when the class is not on the classpath or {@code T} is {@code DynamicObject}); constructed objects for
     * {@code SELECT NEW}; single values for one select item; {@link Row}s for several.
     */
    @SuppressWarnings("unchecked")
    public List<T> list() {
        Ast.Select q = select("list()");
        try (ReadView view = host.openRead()) {
            Engine.Result r = new Engine(host, view, named, positional).select(q, limit, offset, q.explain());
            List<Object> out = new ArrayList<>();
            if (q.explain()) {
                r.plan().lines().forEach(out::add);
                return (List<T>) out;
            }
            if (r.singleObject()) {
                for (DynamicObject d : r.objects()) {
                    out.add(object(view, d));
                }
            } else if (r.shape() == Ast.Shape.NEW) {
                for (Object[] row : r.rows()) {
                    out.add(construct(q.newClass(), row));
                }
            } else if (r.names().size() == 1 && type != Row.class) {
                for (Object[] row : r.rows()) {
                    out.add(convert(row[0], type));
                }
            } else {
                if (type != Row.class && type != Object.class) {
                    throw new OqlException("query returns " + r.names().size() + " columns; use rows() or list() with Row.class");
                }
                for (Object[] row : r.rows()) {
                    out.add(new Row(r.names(), row));
                }
            }
            return (List<T>) out;
        }
    }

    public Optional<T> first() {
        long saved = limit;
        limit = limit < 0 ? 1 : Math.min(limit, 1);
        try {
            List<T> l = list();
            return l.isEmpty() ? Optional.empty() : Optional.ofNullable(l.get(0));
        } finally {
            limit = saved;
        }
    }

    /** Results as rows (whole objects appear as one {@link DynamicObject} column). */
    public List<Row> rows() {
        Ast.Select q = select("rows()");
        try (ReadView view = host.openRead()) {
            Engine.Result r = new Engine(host, view, named, positional).select(q, limit, offset, q.explain());
            List<Row> out = new ArrayList<>();
            if (q.explain()) {
                r.plan().lines().forEach(l -> out.add(new Row(List.of("plan"), new Object[]{l})));
                return out;
            }
            for (Object[] row : r.rows()) {
                out.add(new Row(r.names(), row));
            }
            return out;
        }
    }

    /** Runs UPDATE / DELETE in one atomic batch; returns the number of affected objects. */
    public long execute() {
        if (ast instanceof Ast.Select) {
            throw new OqlException("execute() is for UPDATE / DELETE; use list() or rows() for SELECT");
        }
        if (ast.explain()) {
            throw new OqlException("EXPLAIN only describes the statement; use explain() — nothing was changed");
        }
        try (ReadView view = host.openRead()) {
            Engine e = new Engine(host, view, named, positional);
            return ast instanceof Ast.Update u ? e.update(u) : e.delete((Ast.Delete) ast);
        }
    }

    /** Execution plan (pushed-down filters, indexes, sort / group strategy). */
    public String explain() {
        try (ReadView view = host.openRead()) {
            Engine e = new Engine(host, view, named, positional);
            if (ast instanceof Ast.Select q) {
                return e.select(q, limit, offset, true).plan();
            }
            return e.explainWrite(ast);
        }
    }

    private Ast.Select select(String what) {
        if (!(ast instanceof Ast.Select q)) {
            throw new OqlException(what + " needs a SELECT; use execute() for UPDATE / DELETE");
        }
        return q;
    }

    // ---------------------------------------------------------------- conversions

    private Object object(ReadView view, DynamicObject d) {
        if (type == DynamicObject.class) {
            return d;
        }
        boolean javaClass = host.registry().entity(Oids.classId(d.oid())) != null;
        if (type == Object.class || type == Row.class) {
            return javaClass ? host.getTyped(view, d.oid()) : d;
        }
        if (!javaClass) {
            throw new OqlException("class " + d.className() + " is not on the classpath; use dynamic()");
        }
        Object o = host.getTyped(view, d.oid());
        if (!type.isInstance(o)) {
            throw new OqlException(o.getClass().getName() + " is not a " + type.getName());
        }
        return o;
    }

    private static Object construct(String className, Object[] args) {
        Class<?> cls;
        try {
            cls = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException | RuntimeException e) {
            throw new OqlException("SELECT NEW: class " + className + " not found");
        }
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (c.getParameterCount() != args.length) {
                continue;
            }
            Object[] converted = new Object[args.length];
            boolean ok = true;
            for (int i = 0; i < args.length && ok; i++) {
                try {
                    converted[i] = convert(args[i], c.getParameterTypes()[i]);
                } catch (OqlException e) {
                    ok = false;
                }
            }
            if (ok) {
                try {
                    if (!Modifier.isPublic(c.getModifiers()) || !Modifier.isPublic(cls.getModifiers())) {
                        c.setAccessible(true);
                    }
                    return c.newInstance(converted);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new OqlException("SELECT NEW " + className + " failed: " + e);
                }
            }
        }
        throw new OqlException("SELECT NEW: no constructor of " + className + " accepts " + args.length + " matching arguments");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object convert(Object v, Class<?> t) {
        if (v == null) {
            if (t.isPrimitive()) {
                throw new OqlException("null cannot be converted to " + t);
            }
            return null;
        }
        Class<?> w = wrap(t);
        if (w.isInstance(v)) {
            return v;
        }
        if (v instanceof Number n) {
            if (w == Integer.class) return n.intValue();
            if (w == Long.class) return n.longValue();
            if (w == Double.class) return n.doubleValue();
            if (w == Float.class) return n.floatValue();
            if (w == Short.class) return n.shortValue();
            if (w == Byte.class) return n.byteValue();
            if (w == Ref.class) return Ref.of(n.longValue());
        }
        if (v instanceof String s && w.isEnum()) {
            return Enum.valueOf((Class) w, s);
        }
        if (w == String.class) {
            return String.valueOf(v);
        }
        throw new OqlException(v.getClass().getSimpleName() + " " + v + " cannot be converted to " + t.getSimpleName());
    }

    private static Class<?> wrap(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        if (t == boolean.class) return Boolean.class;
        return Character.class;
    }
}
