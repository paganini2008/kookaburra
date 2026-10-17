package com.github.kookaburra.query;

import com.github.kookaburra.Ref;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.dynamic.DynField;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.exec.Aggregator;
import com.github.kookaburra.exec.Cond;
import com.github.kookaburra.exec.Executor;
import com.github.kookaburra.exec.Planner;
import com.github.kookaburra.exec.QueryHost;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.field.BoolGetter;
import com.github.kookaburra.field.CollectionGetter;
import com.github.kookaburra.field.ComparableField;
import com.github.kookaburra.field.ComparableGetter;
import com.github.kookaburra.field.DoubleField;
import com.github.kookaburra.field.DoubleGetter;
import com.github.kookaburra.field.Field;
import com.github.kookaburra.field.Getter;
import com.github.kookaburra.field.IntField;
import com.github.kookaburra.field.IntGetter;
import com.github.kookaburra.field.LongField;
import com.github.kookaburra.field.LongGetter;
import com.github.kookaburra.field.RefField;
import com.github.kookaburra.field.RefGetter;
import com.github.kookaburra.field.StringGetter;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.TypeDef;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Stream-like query over one collection (design §9.3). The pipeline has a canonical order:
 * {@code filter* → sorted? → skip/limit? → map/select/groupBy? → terminal}; out-of-order calls throw
 * {@link IllegalStateException}. Without {@code sorted} results are unordered. Instances are immutable;
 * every step returns a new stream.
 *
 * @param <T> entity type (results include subclass instances)
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class QueryStream<T> {

    private static final int FILTERING = 0;
    private static final int SORTED = 1;
    private static final int SLICED = 2;

    /**
     * What a stream queries: a stored class (and its subclasses) and how documents become results.
     * {@code residual} materializes objects for opaque Java predicates.
     */
    public record Target<T>(String className, String label, Materializer<T> result, Materializer<Object> residual, boolean dynamic) {

        /** Turns a document into an object. */
        @FunctionalInterface
        public interface Materializer<R> {
            R materialize(QueryHost host, SegmentView view, int doc);
        }

        /** Java entities of {@code type}. */
        public static <T> Target<T> typed(Class<T> type) {
            return new Target<>(type.getName(), type.getSimpleName(), (h, v, d) -> type.cast(h.materialize(v, d)),
                    QueryHost::materialize, false);
        }

        /** {@link com.github.kookaburra.dynamic.DynamicObject}s of a stored class. */
        public static Target<DynamicObject> dynamic(String className) {
            return new Target<>(className, className.substring(className.lastIndexOf('.') + 1),
                    QueryHost::materializeDynamic, QueryHost::materializeDynamic, true);
        }
    }

    private final QueryHost host;
    private final Target<T> target;
    private final ReadView fixed;
    private final List<Predicate<? super T>> filters;
    private final List<Order.Key<?>> keys;
    private final long skip;
    private final long limit;
    private final int stage;
    private final List<RefField<? super T, ?>> fetches;

    /** Internal: created by the database or a snapshot ({@code fixed} = snapshot view, or null). */
    public QueryStream(QueryHost host, Class<T> type, ReadView fixed) {
        this(host, Target.typed(type), fixed, List.of(), List.of(), 0, -1, FILTERING, List.of());
        host.registry().entity(type);
    }

    /** Internal: a stream over an arbitrary target (dynamic objects, OQL). */
    public QueryStream(QueryHost host, Target<T> target, ReadView fixed) {
        this(host, target, fixed, List.of(), List.of(), 0, -1, FILTERING, List.of());
        if (host.registry().schema().byName(target.className()) == null) {
            throw new IllegalArgumentException("no stored class " + target.className());
        }
    }

    private QueryStream(QueryHost host, Target<T> target, ReadView fixed, List<Predicate<? super T>> filters,
                        List<Order.Key<?>> keys, long skip, long limit, int stage, List<RefField<? super T, ?>> fetches) {
        this.host = host;
        this.target = target;
        this.fixed = fixed;
        this.filters = filters;
        this.keys = keys;
        this.skip = skip;
        this.limit = limit;
        this.stage = stage;
        this.fetches = fetches;
    }

    /** Same query on another read view (sub-queries run on the outer query's snapshot). */
    QueryStream<T> withView(ReadView view) {
        return new QueryStream<>(host, target, view, filters, keys, skip, limit, stage, fetches);
    }

    // ---------------------------------------------------------------- pipeline

    /** Adds a filter: a {@link Pred} (pushed down) or any Java predicate (evaluated on objects). */
    // ---------------------------------------------------------------- fluent conditions: .where(A::getX).eq(v)

    public Where<T, Integer> where(IntGetter<T> getter) {
        return new Where<>(this, Q.where(getter));
    }

    public Where<T, Long> where(LongGetter<T> getter) {
        return new Where<>(this, Q.where(getter));
    }

    public Where<T, Double> where(DoubleGetter<T> getter) {
        return new Where<>(this, Q.where(getter));
    }

    public Where<T, Boolean> where(BoolGetter<T> getter) {
        return new Where<>(this, Q.where(getter));
    }

    public Where<T, String> where(StringGetter<T> getter) {
        return new Where<>(this, Q.where(getter));
    }

    public <V extends Comparable<? super V>> Where<T, V> where(ComparableGetter<T, V> getter) {
        return new Where<>(this, Q.where(getter));
    }

    /** Collection fields: {@code .where(A::getTags).contains("x")}. */
    public <E> Where<T, E> where(CollectionGetter<T, E> getter) {
        return new Where<>(this, Q.where(getter));
    }

    /** Reference fields compare by the target's OID: {@code .where(Order::getOwner).eq(ownerOid)}. */
    public <R> Where<T, Long> where(RefGetter<T, R> getter) {
        return new Where<>(this, Q.where(getter));
    }

    public <V> Where<T, V> where(Getter<T, V> getter) {
        return new Where<>(this, Q.where(getter));
    }

    public QueryStream<T> filter(Predicate<? super T> predicate) {
        require(stage == FILTERING, "filter() must come before sorted() / skip() / limit()");
        List<Predicate<? super T>> f = new ArrayList<>(filters);
        f.add(Objects.requireNonNull(predicate));
        return new QueryStream<>(host, target, fixed, List.copyOf(f), keys, skip, limit, stage, fetches);
    }

    @SafeVarargs
    public final QueryStream<T> sorted(Order<? super T>... orders) {
        require(stage == FILTERING, "sorted() must come before skip() / limit() and only once");
        List<Order.Key<?>> k = new ArrayList<>();
        for (Order<? super T> o : orders) {
            k.addAll(o.keys());
        }
        require(!k.isEmpty(), "sorted() needs at least one order");
        return new QueryStream<>(host, target, fixed, filters, List.copyOf(k), skip, limit, SORTED, fetches);
    }

    /** Appends a sort key (fluent {@link OrderBy}); allowed before skip / limit, any number of times. */
    QueryStream<T> thenSorted(Order<? super T> order) {
        require(stage == FILTERING || stage == SORTED, "orderBy() must come before skip() / limit()");
        List<Order.Key<?>> k = new ArrayList<>(keys);
        k.addAll(order.keys());
        return new QueryStream<>(host, target, fixed, filters, List.copyOf(k), skip, limit, SORTED, fetches);
    }

    public OrderBy<T> orderBy(IntGetter<T> getter) {
        return new OrderBy<>(this, Q.where(getter).asc(), Q.where(getter).desc());
    }

    public OrderBy<T> orderBy(LongGetter<T> getter) {
        return new OrderBy<>(this, Q.where(getter).asc(), Q.where(getter).desc());
    }

    public OrderBy<T> orderBy(DoubleGetter<T> getter) {
        return new OrderBy<>(this, Q.where(getter).asc(), Q.where(getter).desc());
    }

    public OrderBy<T> orderBy(BoolGetter<T> getter) {
        return new OrderBy<>(this, Q.where(getter).asc(), Q.where(getter).desc());
    }

    public OrderBy<T> orderBy(StringGetter<T> getter) {
        return new OrderBy<>(this, Q.where(getter).asc(), Q.where(getter).desc());
    }

    public <V extends Comparable<? super V>> OrderBy<T> orderBy(ComparableGetter<T, V> getter) {
        return new OrderBy<>(this, Q.where(getter).asc(), Q.where(getter).desc());
    }

    /** A property of the elements of an embedded list ("any element"): {@code .whereAny(S::getItems, Item::getSku).contains("x")}. */
    public <E, V> Where<T, V> whereAny(CollectionGetter<T, E> list, Getter<E, V> element) {
        return new Where<>(this, Q.whereAny(list, element));
    }

    /** Dynamic queries: condition on a stored path, {@code people.query().where("home.city").eq("Perth")}. */
    @SuppressWarnings("unchecked")
    public Where<T, Object> where(String path) {
        return new Where<>(this, (Field<T, ?>) (Field<?, ?>) dynField(path));
    }

    /** Dynamic queries: sort key on a stored path. */
    @SuppressWarnings("unchecked")
    public OrderBy<T> orderBy(String path) {
        DynField f = dynField(path);
        return new OrderBy<>(this, (Order<T>) (Order<?>) f.asc(), (Order<T>) (Order<?>) f.desc());
    }

    private DynField dynField(String path) {
        require(target.dynamic(), "where(\"" + path + "\") / orderBy(\"" + path + "\") are for dynamic queries; "
                + "use a method reference such as where(" + target.label() + "::get...)");
        Schema schema = host.registry().schema();
        ClassDef def = schema.byName(target.className());
        return DynField.of(schema, def, path);
    }

    public QueryStream<T> skip(long n) {
        require(n >= 0, "negative skip");
        long l = limit < 0 ? -1 : Math.max(0, limit - n);
        return new QueryStream<>(host, target, fixed, filters, keys, skip + n, l, SLICED, fetches);
    }

    public QueryStream<T> limit(long n) {
        require(n >= 0, "negative limit");
        long l = limit < 0 ? n : Math.min(limit, n);
        return new QueryStream<>(host, target, fixed, filters, keys, skip, l, SLICED, fetches);
    }

    /**
     * Loads the objects referenced by {@code ref} for all results in one batch (avoids N+1 lookups);
     * {@code result.getRef().resolve(db)} then returns without another read.
     */
    public QueryStream<T> fetch(RefField<? super T, ?> ref) {
        List<RefField<? super T, ?>> f = new ArrayList<>(fetches);
        f.add(Objects.requireNonNull(ref));
        return new QueryStream<>(host, target, fixed, filters, keys, skip, limit, stage, List.copyOf(f));
    }

    // ---------------------------------------------------------------- bulk writes

    /**
     * Applies {@code change} to every result and stores all of them in one atomic batch (optimistic
     * locking per object). Returns the number of updated objects.
     */
    public int updateEach(Consumer<? super T> change) {
        List<T> objects = toList();
        objects.forEach(change);
        try (WriteBatch b = host.database().batch()) {
            objects.forEach(b::update);
            b.commit();
        }
        return objects.size();
    }

    /** Deletes every result in one atomic batch; returns the number of objects selected. */
    public long deleteAll() {
        Set<Long> oids = oids();
        try (WriteBatch b = host.database().batch()) {
            oids.forEach(b::delete);
            b.commit();
        }
        return oids.size();
    }

    // ---------------------------------------------------------------- projections and grouping

    public <V> Projection<V> map(Field<? super T, V> field) {
        return new Projection<>(this, List.of(field), v -> cast(v[0]));
    }

    public <A, B> Projection<Tuple2<A, B>> select(Field<? super T, A> a, Field<? super T, B> b) {
        return new Projection<>(this, List.of(a, b), v -> new Tuple2<>(cast(v[0]), cast(v[1])));
    }

    public <A, B, C> Projection<Tuple3<A, B, C>> select(Field<? super T, A> a, Field<? super T, B> b, Field<? super T, C> c) {
        return new Projection<>(this, List.of(a, b, c), v -> new Tuple3<>(cast(v[0]), cast(v[1]), cast(v[2])));
    }

    public <A, B, R> Projection<R> select(BiFunction<A, B, R> ctor, Field<? super T, A> a, Field<? super T, B> b) {
        return new Projection<>(this, List.of(a, b), v -> ctor.apply(cast(v[0]), cast(v[1])));
    }

    public <A, B, C, R> Projection<R> select(Fn3<A, B, C, R> ctor, Field<? super T, A> a, Field<? super T, B> b,
                                             Field<? super T, C> c) {
        return new Projection<>(this, List.of(a, b, c), v -> ctor.apply(cast(v[0]), cast(v[1]), cast(v[2])));
    }

    @SafeVarargs
    public final GroupStream<T> groupBy(Field<? super T, ?>... keys) {
        require(stage == FILTERING, "groupBy() must follow filters directly (sort / slice the groups instead)");
        require(keys.length > 0, "groupBy() needs at least one key");
        return new GroupStream<>(this, List.of(keys));
    }

    /** Aggregates over all matches (one row). */
    @SafeVarargs
    public final Row aggregate(Agg<? super T>... aggs) {
        require(aggs.length > 0, "aggregate() needs at least one aggregate");
        List<Row> rows = new GroupStream<>(this, List.of()).agg(aggs).toList();
        return rows.get(0);
    }

    // ---------------------------------------------------------------- terminals

    /** Same as {@link #toList()}. */
    public List<T> list() {
        return toList();
    }

    public List<T> toList() {
        try (Run r = run()) {
            List<T> out = new ArrayList<>();
            for (Executor.Hit h : r.hits()) {
                out.add(target.result().materialize(host, h.view(), h.doc()));
            }
            preload(r, out);
            return out;
        }
    }

    /** Resolves {@link #fetch} references of {@code objects} with one batch lookup per field. */
    private void preload(Run r, List<T> objects) {
        for (RefField<? super T, ?> f : fetches) {
            @SuppressWarnings("unchecked")
            RefField<Object, ?> any = (RefField<Object, ?>) f;
            Map<Long, List<Ref<?>>> refs = new LinkedHashMap<>();
            for (T o : objects) {
                Ref<?> ref = any.get(o);
                if (ref != null && !ref.isResolved()) {
                    refs.computeIfAbsent(ref.oid(), k -> new ArrayList<>()).add(ref);
                }
            }
            host.getAll(r.view, refs.keySet()).forEach((oid, target) -> refs.get(oid).forEach(ref -> ref.preload(target)));
        }
    }

    public Optional<T> first() {
        List<T> l = limit(1).toList();
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public long count() {
        try (Run r = run()) {
            long n = Math.max(0, r.ex.count() - skip);
            return limit < 0 ? n : Math.min(n, limit);
        }
    }

    public boolean exists() {
        try (Run r = run()) {
            boolean[] found = {false};
            long[] seen = {0};
            r.ex.forEach(h -> {
                found[0] = seen[0]++ >= skip;
                return !found[0];
            });
            return found[0] && limit != 0;
        }
    }

    public void forEach(Consumer<? super T> action) {
        toList().forEach(action);
    }

    /** Lazily materializing cursor; holds its snapshot until closed or exhausted. */
    public Cursor<T> cursor() {
        Run r = run();
        try {
            return new HitCursor<>(r, r.hits(), h -> target.result().materialize(host, h.view(), h.doc()));
        } catch (RuntimeException e) {
            r.close();
            throw e;
        }
    }

    /** JDK stream over the pushed-down result; closing it releases the snapshot. */
    public Stream<T> stream() {
        Cursor<T> c = cursor();
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(c, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(c::close);
    }

    /** Sum of non-null values; 0 when there are none. */
    public long sum(IntField<? super T> field) {
        Object v = aggregate(Q.sum(field)).get(0);
        return v == null ? 0 : ((Number) v).longValue();
    }

    /** Sum of non-null values; 0 when there are none. */
    public long sum(LongField<? super T> field) {
        Object v = aggregate(Q.sum(field)).get(0);
        return v == null ? 0 : ((Number) v).longValue();
    }

    /** Sum of non-null values; 0 when there are none. */
    public double sum(DoubleField<? super T> field) {
        Object v = aggregate(Q.sum(field)).get(0);
        return v == null ? 0 : ((Number) v).doubleValue();
    }

    /** Exact sum of non-null decimal values; {@link BigDecimal#ZERO} when there are none. */
    public BigDecimal sum(ComparableField<? super T, BigDecimal> field) {
        Object v = aggregate(Q.sum(field)).get(0);
        return v == null ? BigDecimal.ZERO : (BigDecimal) v;
    }

    /** Average as a double (like JPA's {@code AVG}); OQL and {@link Q#avg} in groups keep decimals exact. */
    public OptionalDouble avg(Field<? super T, ? extends Number> field) {
        Object v = aggregate(Q.avg(field)).get(0);
        return v == null ? OptionalDouble.empty() : OptionalDouble.of(((Number) v).doubleValue());
    }

    @SuppressWarnings("unchecked")
    public <V extends Comparable<? super V>> Optional<V> min(ComparableField<? super T, V> field) {
        return Optional.ofNullable((V) aggregate(Q.min(field)).get(0));
    }

    @SuppressWarnings("unchecked")
    public <V extends Comparable<? super V>> Optional<V> max(ComparableField<? super T, V> field) {
        return Optional.ofNullable((V) aggregate(Q.max(field)).get(0));
    }

    /** Query plan in text form (design §10.5). */
    public String explain() {
        try (Run r = run()) {
            StringBuilder sb = new StringBuilder();
            List<SegmentView> views = r.ex.views();
            sb.append("QUERY ").append(target.label()).append("  snapshot=seq ").append(r.view.seq())
                    .append("  segments=").append(views.size());
            if (!views.isEmpty()) {
                sb.append(" (").append(String.join(", ", views.stream().map(SegmentView::label).toList())).append(')');
            }
            sb.append('\n');
            if (r.types != null) {
                sb.append("TYPE IN ").append(r.types).append('\n');
            }
            sb.append("FILTER");
            if (r.cond instanceof Cond.Const c && c.value()) {
                sb.append(" none\n");
            } else {
                sb.append('\n');
                describe(sb, r.cond, views.isEmpty() ? null : views.get(0), "  ");
            }
            if (!keys.isEmpty()) {
                sb.append("SORT ").append(String.join(", ", keys.stream().map(Order.Key::toString).toList()))
                        .append(limit >= 0 ? "  [top-N heap]" : "  [full sort]").append('\n');
            }
            if (skip > 0 || limit >= 0) {
                sb.append("SLICE skip=").append(skip).append(" limit=").append(limit < 0 ? "all" : limit).append('\n');
            }
            return sb.toString();
        }
    }

    private static void describe(StringBuilder sb, Cond c, SegmentView v, String indent) {
        if (c instanceof Cond.And a) {
            sb.append(indent).append("AND\n");
            a.conds().forEach(x -> describe(sb, x, v, indent + "  "));
        } else if (c instanceof Cond.Or o) {
            sb.append(indent).append("OR\n");
            o.conds().forEach(x -> describe(sb, x, v, indent + "  "));
        } else if (c instanceof Cond.Not n) {
            sb.append(indent).append("NOT\n");
            describe(sb, n.cond(), v, indent + "  ");
        } else {
            sb.append(indent).append(c);
            if (v != null) {
                String access = Executor.access(c, v);
                if (!access.isEmpty()) {
                    sb.append("  [").append(access).append(']');
                }
            }
            sb.append('\n');
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Internal (OQL fast path): groups matches by stored paths and aggregates stored paths on canonical
     * values, without materializing objects. {@code argPaths} entries are null for {@code COUNT(*)}.
     */
    public List<Aggregator.Group> canonicalGroups(
            List<String> keyPaths, List<Aggregator.Fn> fns, List<String> argPaths) {
        try (Run r = run()) {
            List<String> keys = new ArrayList<>();
            keyPaths.forEach(k -> keys.add(r.planner.storedPath(k)));
            List<Aggregator.Spec> specs = new ArrayList<>();
            for (int i = 0; i < fns.size(); i++) {
                String p = argPaths.get(i) == null ? null : r.planner.storedPath(argPaths.get(i));
                specs.add(new Aggregator.Spec(fns.get(i), p,
                        p == null ? null : r.planner.typeOf(p).scalarType()));
            }
            return Aggregator.run(r.ex, keys, specs);
        }
    }

    /** Internal (OQL): number of matches ignoring skip / limit. */
    public long countAll() {
        try (Run r = run()) {
            return r.ex.count();
        }
    }

    /** OIDs of all matches (used for reference sub-queries). */
    Set<Long> oids() {
        try (Run r = run()) {
            Set<Long> out = new LinkedHashSet<>();
            for (Executor.Hit h : r.hits()) {
                out.add(h.view().oid(h.doc()));
            }
            return out;
        }
    }

    boolean filtersOnly() {
        return stage == FILTERING;
    }

    long skipCount() {
        return skip;
    }

    long limitCount() {
        return limit;
    }

    /** One execution: read view + plan + executor. */
    class Run implements AutoCloseable {
        final ReadView view;
        final Planner planner;
        final Cond cond;
        final Set<Integer> types;
        final Executor ex;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Run() {
            this.view = fixed != null ? fixed : host.openRead();
            try {
                Schema schema = host.registry().schema();
                ClassDef def = schema.byName(target.className());
                String collection = def.collection();
                this.types = def.isRoot() ? null : schema.subclassIds(def.name());
                this.planner = new Planner(schema, collection, (cls, pred) ->
                        new QueryStream(host, (Class) cls, view).filter((Pred) pred).oids())
                        .withStreamOids(src -> ((QueryStream<?>) src).withView(view).oids())
                        .withDynamicSubquery((cls, pred) ->
                                new QueryStream<>(host, Target.dynamic(cls), view).filter((Pred) pred).oids());
                List<Pred<Object>> preds = new ArrayList<>();
                for (Predicate<? super T> p : filters) {
                    preds.add((Pred<Object>) (Pred) Pred.wrap(p));
                }
                this.cond = preds.isEmpty() ? new Cond.Const(true) : planner.plan(Pred.And.of(preds));
                this.ex = new Executor(view, collection, types, cond, (v, d) -> target.residual().materialize(host, v, d));
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }

        List<Executor.Hit> hits() {
            if (keys.isEmpty()) {
                return ex.hits(skip, limit);
            }
            return ex.sorted(sortKeys(), skip, limit);
        }

        List<Executor.SortKey> sortKeys() {
            List<Executor.SortKey> out = new ArrayList<>();
            for (Order.Key<?> k : keys) {
                out.add(new Executor.SortKey(planner.storedPath(k.field().path()), typeOf(k.field()), k.descending(),
                        k.nullsFirst()));
            }
            return out;
        }

        TypeDef typeOf(Field<?, ?> f) {
            String path = planner.storedPath(f.path());
            TypeDef t = planner.typeOf(path);
            if (t == null) {
                throw new IllegalStateException("'" + f.path() + "' is not stored in " + target.label());
            }
            return t.scalarType();
        }

        /** Java value of a field for a hit, from canonical values where possible. */
        Object value(Executor.Hit h, Field<?, ?> f, Object[] objectCache) {
            String path = planner.storedPath(f.path());
            if (Values.canonical(f)) {
                Object c = Executor.value(h, path);
                return path.startsWith("$") ? c : Values.toJava(f, c);
            }
            if (objectCache[0] == null) {
                objectCache[0] = target.result().materialize(host, h.view(), h.doc());
            }
            @SuppressWarnings("unchecked")
            Field<Object, ?> any = (Field<Object, ?>) f;
            return any.get(objectCache[0]);
        }

        @Override
        public void close() {
            if (fixed == null && view != null) {
                view.close();
            }
        }
    }

    Run run() {
        return new Run();
    }

    /** Cursor over precomputed hits, materializing lazily. */
    static class HitCursor<R> implements Cursor<R> {
        private final AutoCloseable owner;
        private final Iterator<Executor.Hit> hits;
        private final Function<Executor.Hit, R> map;
        private boolean closed;

        HitCursor(AutoCloseable owner, List<Executor.Hit> hits, Function<Executor.Hit, R> map) {
            this.owner = owner;
            this.hits = hits.iterator();
            this.map = map;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                return false;
            }
            if (!hits.hasNext()) {
                close();
                return false;
            }
            return true;
        }

        @Override
        public R next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return map.apply(hits.next());
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    owner.close();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> V cast(Object o) {
        return (V) o;
    }

    static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalStateException(message);
        }
    }

    /** Removes duplicates keeping first occurrences (used by projections). */
    static <R> List<R> distinct(List<R> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }
}
