package com.github.kookaburra.query;

import com.github.kookaburra.Ref;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.annotation.VectorDistance;
import com.github.kookaburra.dynamic.DynField;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.embed.Reranker;
import com.github.kookaburra.exec.Aggregator;
import com.github.kookaburra.exec.Cond;
import com.github.kookaburra.exec.Executor;
import com.github.kookaburra.exec.Planner;
import com.github.kookaburra.exec.Profile;
import com.github.kookaburra.exec.QueryHost;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.exec.TextIndex;
import com.github.kookaburra.field.AbstractField;
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
import com.github.kookaburra.field.LongField;
import com.github.kookaburra.field.LongGetter;
import com.github.kookaburra.field.RefField;
import com.github.kookaburra.field.RefGetter;
import com.github.kookaburra.field.StringGetter;
import com.github.kookaburra.field.VectorField;
import com.github.kookaburra.field.VectorGetter;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.mapping.Vectors;
import com.github.kookaburra.text.Analyzers;
import com.github.kookaburra.text.TextQuery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    /** Nearest-neighbour ordering, or null. */
    private final Nearest nearest;

    /**
     * Order by distance of the vector at {@code path} to {@code query} ({@code distance} null = the field's);
     * {@code candidates}: HNSW candidates per segment, -1 = {@code kookaburra.vector-ef-search}, 0 = exact search.
     */
    private record Nearest(String path, float[] query, VectorDistance distance, int candidates, String text, Post post) {

        Nearest with(int c) {
            return new Nearest(path, query, distance, c, text, post);
        }

        Nearest with(Post p) {
            return new Nearest(path, query, distance, candidates, text, p);
        }
    }

    /** Re-ordering of the closest {@code fetch} candidates (0 = {@code max(20, 4 k)}). */
    private sealed interface Post permits Diverse, Rerank, Hybrid {
        int fetch();
    }

    /** Maximal marginal relevance: {@code lambda} 1 = closest only, 0 = most different only. */
    private record Diverse(double lambda, int fetch) implements Post {
    }

    /** Fusion with a full-text search of {@code field} for {@code query} (null = the nearest(…, text) query). */
    private record Hybrid(String field, String query, int fetch) implements Post {
    }

    /** Re-scoring of the candidates' text at {@code field} against {@code query} (null = the nearest(…, text) query). */
    private record Rerank(String field, String query, int fetch) implements Post {
    }

    /** Internal: created by the database or a snapshot ({@code fixed} = snapshot view, or null). */
    public QueryStream(QueryHost host, Class<T> type, ReadView fixed) {
        this(host, Target.typed(type), fixed, List.of(), List.of(), 0, -1, FILTERING, List.of(), null);
        host.registry().entity(type);
    }

    /** Internal: a stream over an arbitrary target (dynamic objects, OQL). */
    public QueryStream(QueryHost host, Target<T> target, ReadView fixed) {
        this(host, target, fixed, List.of(), List.of(), 0, -1, FILTERING, List.of(), null);
        if (host.registry().schema().byName(target.className()) == null) {
            throw new IllegalArgumentException("no stored class " + target.className());
        }
    }

    private QueryStream(QueryHost host, Target<T> target, ReadView fixed, List<Predicate<? super T>> filters,
                        List<Order.Key<?>> keys, long skip, long limit, int stage, List<RefField<? super T, ?>> fetches,
                        Nearest nearest) {
        this.host = host;
        this.target = target;
        this.fixed = fixed;
        this.filters = filters;
        this.keys = keys;
        this.skip = skip;
        this.limit = limit;
        this.stage = stage;
        this.fetches = fetches;
        this.nearest = nearest;
    }

    /** Same query on another read view (sub-queries run on the outer query's snapshot). */
    QueryStream<T> withView(ReadView view) {
        return new QueryStream<>(host, target, view, filters, keys, skip, limit, stage, fetches, nearest);
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
        return new QueryStream<>(host, target, fixed, List.copyOf(f), keys, skip, limit, stage, fetches, nearest);
    }

    @SafeVarargs
    public final QueryStream<T> sorted(Order<? super T>... orders) {
        require(stage == FILTERING, "sorted() must come before skip() / limit() and only once");
        require(nearest == null, "sorted() cannot follow nearest(): results are ordered by distance");
        List<Order.Key<?>> k = new ArrayList<>();
        for (Order<? super T> o : orders) {
            k.addAll(o.keys());
        }
        require(!k.isEmpty(), "sorted() needs at least one order");
        return new QueryStream<>(host, target, fixed, filters, List.copyOf(k), skip, limit, SORTED, fetches, nearest);
    }

    /** Appends a sort key (fluent {@link OrderBy}); allowed before skip / limit, any number of times. */
    QueryStream<T> thenSorted(Order<? super T> order) {
        require(stage == FILTERING || stage == SORTED, "orderBy() must come before skip() / limit()");
        require(nearest == null, "orderBy() cannot follow nearest(): results are ordered by distance");
        List<Order.Key<?>> k = new ArrayList<>(keys);
        k.addAll(order.keys());
        return new QueryStream<>(host, target, fixed, filters, List.copyOf(k), skip, limit, SORTED, fetches, nearest);
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

    /**
     * Objects with at least one element of the embedded list meeting every condition, all on the same element (as
     * MongoDB's {@code $elemMatch} or a JPQL {@code JOIN o.lines l WHERE l.sku = 'A' AND l.qty > 5}):
     * {@code whereElement(Order::getLines, e -> e.where(Line::getSku).eq("A").where(Line::getQty).gt(5))}.
     * Equalities narrow the candidates through the index of the element path; the conditions are then checked on
     * each element.
     */
    public <E> QueryStream<T> whereElement(CollectionGetter<T, E> list, Consumer<ElementQuery<E>> conditions) {
        AbstractField<T, ?> field = Fields.listOf(list);                      // a wrong list is reported first
        ElementQuery<E> q = new ElementQuery<>();
        conditions.accept(q);
        return filter(new Pred.ElemMatch<>(field, q.conditions()));
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

    // ---------------------------------------------------------------- vector search: .nearest(Doc::getEmbedding, q, 10)

    /**
     * Orders the results by the distance of their vector to {@code query}, closest first (exact search over the
     * filtered objects, like {@code ORDER BY embedding <=> :q} in pgvector), with the field's {@code @Vector}
     * distance; objects without a vector are left out. Follow with {@code limit(k)}, or use
     * {@link #nearest(VectorGetter, float[], int)}.
     */
    public QueryStream<T> nearest(VectorGetter<T> getter, float[] query) {
        return nearest(Fields.of(getter), query, null);
    }

    /**
     * Orders by distance to the embedding of {@code text} by the configured embedder ({@code kookaburra.embedder},
     * e.g. Ollama): semantic search, {@code nearest(Doc::getEmbedding, "how to bake bread", 10)}.
     */
    public QueryStream<T> nearest(VectorGetter<T> getter, String text) {
        QueryStream<T> q = nearest(Fields.of(getter), host.embed(Objects.requireNonNull(text, "text")), null);
        return q.withNearest(new Nearest(q.nearest.path(), q.nearest.query(), null, -1, text, null));
    }

    /** The {@code k} results closest to the embedding of {@code text}. */
    public QueryStream<T> nearest(VectorGetter<T> getter, String text, int k) {
        return nearest(getter, text).limit(k);
    }

    /** The {@code k} results closest to {@code query}: {@code nearest(getter, query).limit(k)}. */
    public QueryStream<T> nearest(VectorGetter<T> getter, float[] query, int k) {
        return nearest(getter, query).limit(k);
    }

    /** Orders by distance to {@code query} with an explicit distance (null = the field's {@code @Vector} distance). */
    public QueryStream<T> nearest(VectorGetter<T> getter, float[] query, VectorDistance distance) {
        return nearest(Fields.of(getter), query, distance);
    }

    /** Same with a metamodel field ({@code Doc_.embedding}). */
    public QueryStream<T> nearest(VectorField<? super T> field, float[] query, VectorDistance distance) {
        return nearestPath(field.path(), query, distance);
    }

    /**
     * Same by dotted path (dynamic objects, OQL): {@code query} as {@code float[]}, {@code double[]}, a list of numbers
     * or the text {@code [0.1, 0.2, …]}.
     */
    public QueryStream<T> nearest(String path, Object query, VectorDistance distance) {
        return nearestPath(path, Vectors.of(Objects.requireNonNull(query, "query"), "nearest query"), distance);
    }

    private QueryStream<T> nearestPath(String path, float[] query, VectorDistance distance) {
        require(stage == FILTERING, "nearest() must follow the filters and come before skip() / limit(), once");
        float[] q = Vectors.of(Objects.requireNonNull(query, "query"), "nearest query");
        return new QueryStream<>(host, target, fixed, filters, keys, skip, limit, SORTED, fetches, new Nearest(path, q, distance, -1, null, null));
    }

    /**
     * Candidates the approximate search keeps per segment ({@code ef}, like {@code num_candidates} in Elasticsearch
     * or {@code hnsw.ef_search} in pgvector): more finds the true nearest neighbours more often, at some cost. At
     * least {@code k} are kept. Default {@code kookaburra.vector-ef-search}.
     */
    public QueryStream<T> numCandidates(int n) {
        require(nearest != null && nearest.query() != null, "numCandidates() needs nearest(...)");
        require(n > 0, "numCandidates must be positive");
        return withNearest(nearest.with(n));
    }

    /**
     * Exact search: compares the query with every matching vector instead of searching the HNSW graphs (always the
     * true nearest neighbours; slower on large collections without a selective filter).
     */
    public QueryStream<T> exact() {
        require(nearest != null && nearest.query() != null, "exact() needs nearest(...)");
        return withNearest(nearest.with(0));
    }

    // ---------------------------------------------------------------- full-text search: .search(Doc::getBody, "bread", 10)

    /**
     * Full-text relevance search: the objects whose text contains any word of {@code text}, most relevant first by
     * BM25 (Lucene's scoring; rare words weigh more, short texts with the word rank higher). Uses the field's
     * {@code @FullText} index, else splits the rows. Follow with {@code limit(k)}; {@code listWithDistance()} gives
     * the scores. For a filter that needs every word use {@code where(field).matches(text)}.
     */
    public QueryStream<T> search(StringGetter<T> getter, String text) {
        return search(Fields.of(getter).path(), text);
    }

    /** The {@code k} most relevant results: {@code search(getter, text).limit(k)}. */
    public QueryStream<T> search(StringGetter<T> getter, String text, int k) {
        return search(getter, text).limit(k);
    }

    /** Same by dotted path (dynamic objects, OQL). */
    public QueryStream<T> search(String path, String text) {
        require(stage == FILTERING, "search() must follow the filters and come before skip() / limit(), once");
        TextQuery.parse(text);                                          // fails early on a query without words
        return new QueryStream<>(host, target, fixed, filters, keys, skip, limit, SORTED, fetches,
                new Nearest(Objects.requireNonNull(path, "path"), null, null, -1, text, null));
    }

    /**
     * Hybrid search: fuses this vector search with a full-text search of {@code text} for the same query text by
     * reciprocal rank fusion ({@code 1 / (60 + rank)} summed over both lists, as Elasticsearch's RRF retriever), from
     * the best {@code max(20, 4 k)} of each. Needs {@code nearest(field, "text", k)}.
     */
    public QueryStream<T> hybrid(StringGetter<T> text) {
        return hybrid(Fields.of(text).path(), null, 0);
    }

    /**
     * @param query the full-text query (null = the text given to {@code nearest})
     * @param fetch results taken from each list (0 = {@code max(20, 4 k)})
     */
    public QueryStream<T> hybrid(String textPath, String query, int fetch) {
        require(nearest != null && nearest.query() != null, "hybrid() needs nearest(...)");
        require(query != null || nearest.text() != null, "hybrid() needs the query text: nearest(field, \"text\", k) or hybrid(field, query, fetch)");
        require(fetch >= 0, "negative fetch");
        TextQuery.parse(query != null ? query : nearest.text());
        return withNearest(nearest.with(new Hybrid(Objects.requireNonNull(textPath, "text"), query, fetch)));
    }

    /**
     * Diverse results with maximal marginal relevance (as LangChain's {@code max_marginal_relevance_search}): from the
     * closest {@code max(20, 4 k)} candidates, picks one by one the object that is close to the query and far from
     * those already picked; {@code lambda} 0.5. Needs a limit.
     */
    public QueryStream<T> diverse() {
        return diverse(0.5, 0);
    }

    /**
     * @param lambda 1 = closest only (plain order), 0 = as different as possible
     * @param fetch  candidates considered (0 = {@code max(20, 4 k)})
     */
    public QueryStream<T> diverse(double lambda, int fetch) {
        require(nearest != null && nearest.query() != null, "diverse() needs nearest(...)");
        require(lambda >= 0 && lambda <= 1, "lambda must be between 0 and 1");
        require(fetch >= 0, "negative fetch");
        return withNearest(nearest.with(new Diverse(lambda, fetch)));
    }

    /**
     * Re-orders the closest candidates by a reranker model ({@code kookaburra.reranker}, e.g. Ollama) that reads the
     * query text and each candidate's {@code text} field: the order of a cross-encoder, the speed of the vector
     * search for the first pass. The query is the text of {@code nearest(field, "text", k)}. Needs a limit.
     */
    public QueryStream<T> rerank(StringGetter<T> text) {
        return rerank(text, null, 0);
    }

    /**
     * @param query the query text (null = the text given to {@code nearest})
     * @param fetch candidates re-scored (0 = {@code max(20, 4 k)})
     */
    public QueryStream<T> rerank(StringGetter<T> text, String query, int fetch) {
        return rerank(Fields.of(text).path(), query, fetch);
    }

    /** Same by dotted path (dynamic objects). */
    public QueryStream<T> rerank(String textPath, String query, int fetch) {
        require(nearest != null, "rerank() needs nearest(...) or search(...)");
        require(query != null || nearest.text() != null, "rerank() needs the query text: nearest(field, \"text\", k) or rerank(field, query, fetch)");
        require(fetch >= 0, "negative fetch");
        return withNearest(nearest.with(new Rerank(Objects.requireNonNull(textPath, "text"), query, fetch)));
    }

    private QueryStream<T> withNearest(Nearest n) {
        return new QueryStream<>(host, target, fixed, filters, keys, skip, limit, stage, fetches, n);
    }

    /** Results of a {@link #nearest} search with their distances, closest first. */
    public List<Neighbor<T>> listWithDistance() {
        require(nearest != null, "listWithDistance() needs nearest(...) or search(...)");
        try (Run r = run()) {
            List<Neighbor<T>> out = new ArrayList<>();
            List<T> objects = new ArrayList<>();
            List<Executor.Near> near = r.near();
            for (Executor.Near n : near) {
                T o = target.result().materialize(host, n.hit().view(), n.hit().doc());
                objects.add(o);
                Double score = r.scores == null ? null : r.scores.get(n);
                double distance = nearest.query() == null ? Double.NaN : n.distance();    // a text search has no distance
                out.add(new Neighbor<>(o, distance, score == null ? Double.NaN : score));
            }
            preload(r, objects);
            return out;
        }
    }

    public QueryStream<T> skip(long n) {
        require(n >= 0, "negative skip");
        long l = limit < 0 ? -1 : Math.max(0, limit - n);
        return new QueryStream<>(host, target, fixed, filters, keys, skip + n, l, SLICED, fetches, nearest);
    }

    public QueryStream<T> limit(long n) {
        require(n >= 0, "negative limit");
        long l = limit < 0 ? n : Math.min(limit, n);
        return new QueryStream<>(host, target, fixed, filters, keys, skip, l, SLICED, fetches, nearest);
    }

    /**
     * Loads the objects referenced by {@code ref} for all results in one batch (avoids N+1 lookups);
     * {@code result.getRef().resolve(db)} then returns without another read.
     */
    public QueryStream<T> fetch(RefField<? super T, ?> ref) {
        List<RefField<? super T, ?>> f = new ArrayList<>(fetches);
        f.add(Objects.requireNonNull(ref));
        return new QueryStream<>(host, target, fixed, filters, keys, skip, limit, stage, List.copyOf(f), nearest);
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
            if (nearest != null) {
                return r.near().size();              // objects without a vector are not results
            }
            long n = Math.max(0, r.ex.count() - skip);
            return limit < 0 ? n : Math.min(n, limit);
        }
    }

    public boolean exists() {
        try (Run r = run()) {
            if (nearest != null) {
                return !r.near().isEmpty();
            }
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

    /** The query plan as text: the steps as a tree, the access path of every condition (see {@link #plan()}). */
    public String explain() {
        return plan().render();
    }

    /** Runs the query and returns the plan with actual rows and times per step as text (see {@link #analyze()}). */
    public String explainAnalyze() {
        return analyze().render();
    }

    /**
     * The plan without running the query: the steps (filter per segment, ordering, slicing) and how each condition
     * is answered (which index, or a scan of the rows), as PostgreSQL's {@code EXPLAIN}.
     */
    public QueryPlan plan() {
        try (Run r = run()) {
            return new QueryPlan(build(r, null, null), false);
        }
    }

    /**
     * Runs the query (materializing the results, as {@code list()}) and returns the plan with the actual rows and
     * time of every step, as PostgreSQL's {@code EXPLAIN ANALYZE}.
     */
    public QueryPlan analyze() {
        try (Run r = run()) {
            Profile p = new Profile();
            r.ex.profile(p);
            long start = System.nanoTime();
            List<Executor.Hit> hits = r.hits();
            long found = System.nanoTime();
            for (Executor.Hit h : hits) {
                target.result().materialize(host, h.view(), h.doc());
            }
            long end = System.nanoTime();
            long[] times = {end - start, found - start, end - found, hits.size()};
            return new QueryPlan(build(r, p, times), true);
        }
    }

    /**
     * The step tree: Query → [Limit] → [re-ordering] → [ranking or sort] → Filter → one step per segment view →
     * its conditions. {@code times}: total, finding, materializing, results (analyze only).
     */
    private QueryPlan.Step build(Run r, Profile p, long[] times) {
        List<SegmentView> views = r.ex.views();
        boolean analyzed = p != null;
        long objects = views.stream().mapToLong(SegmentView::size).sum();

        // filter, one step per view
        List<QueryPlan.Step> viewSteps = new ArrayList<>();
        if (analyzed) {
            for (Profile.View v : p.views()) {
                List<QueryPlan.Step> conds = nest(v.conditions());
                viewSteps.add(new QueryPlan.Step(v.label(), objects(v.size()),
                        QueryPlan.Access.NONE, v.candidates(), v.matched(), v.nanos(), conds));
            }
        } else {
            for (SegmentView v : views) {
                List<QueryPlan.Step> conds = new ArrayList<>();
                if (!(r.cond instanceof Cond.Const)) {
                    conds.add(describe(r.cond, v));
                }
                viewSteps.add(new QueryPlan.Step(v.label(), objects(v.size()),
                        QueryPlan.Access.NONE, conds));
            }
        }
        String filterText = r.cond instanceof Cond.Const c ? (c.value() ? "all objects" : "no objects") : r.cond.toString();
        if (r.types != null) {
            Schema schema = host.registry().schema();
            List<String> names = r.types.stream().map(id -> schema.byId(id).simpleName()).sorted().toList();
            filterText = "TYPE IN " + names + (r.cond instanceof Cond.Const ? "" : " AND " + filterText);
        }
        QueryPlan.Step step = new QueryPlan.Step("Filter", filterText, QueryPlan.Access.NONE,
                analyzed ? objects : -1, analyzed ? p.matched() : -1, analyzed ? p.filterNanos() : -1, viewSteps);

        long ordered = analyzed ? times[3] : -1;
        long orderNanos = analyzed ? Math.max(0, times[1] - p.filterNanos()) : -1;
        long matched = analyzed ? p.matched() : -1;
        if (nearest != null && nearest.query() == null) {
            String stored = r.planner.storedPath(nearest.path());
            List<QueryPlan.Step> per = new ArrayList<>();
            for (SegmentView v : views) {
                TextIndex ti = v.text(stored);
                per.add(new QueryPlan.Step(v.label(), ti != null ? "[" + ti.describe() + "]" : "[scan: words of each row]",
                        ti != null ? QueryPlan.Access.FULLTEXT : QueryPlan.Access.SCAN, List.of()));
            }
            List<QueryPlan.Step> kids = new ArrayList<>(per);
            kids.add(step);
            step = new QueryPlan.Step("Full-text search", nearest.path() + " for '" + nearest.text() + "', any word, BM25 relevance"
                    + (limit >= 0 ? ", top-N heap" : ", full sort"), QueryPlan.Access.FULLTEXT, matched, ordered, orderNanos, kids);
        } else if (nearest != null) {
            TypeDef t = r.planner.typeOf(r.planner.storedPath(nearest.path()));
            VectorDistance d = nearest.distance() != null ? nearest.distance()
                    : t != null && t.kind() == ValueType.VECTOR ? t.distance() : null;
            String stored = r.planner.storedPath(nearest.path());
            long bound = limit < 0 ? -1 : skip + limit;
            List<QueryPlan.Step> kids = new ArrayList<>();
            for (SegmentView v : views) {
                String access = Executor.nearestAccess(v, stored, nearest.query().length, d, bound, r.candidates());
                kids.add(new QueryPlan.Step(v.label(), "[" + access + "]", access.startsWith("HNSW") ? QueryPlan.Access.VECTOR
                        : access.contains("rows") ? QueryPlan.Access.SCAN : QueryPlan.Access.VECTOR, List.of()));
            }
            kids.add(step);
            step = new QueryPlan.Step("Nearest", nearest.path() + " by " + d + " to a " + nearest.query().length + "-dim vector"
                    + (limit >= 0 ? ", top-N heap" : ", full sort"), QueryPlan.Access.VECTOR, matched, ordered, orderNanos, kids);
        } else if (!keys.isEmpty()) {
            step = new QueryPlan.Step(limit >= 0 ? "Top-N sort" : "Sort", String.join(", ", keys.stream().map(Order.Key::toString).toList()),
                    QueryPlan.Access.PROCESS, matched, ordered, orderNanos, List.of(step));
        }
        Post post = nearest == null ? null : nearest.post();
        if (post != null) {
            String fetch = post.fetch() > 0 ? String.valueOf(post.fetch()) : "max(20, 4 k)";
            String[] op = post instanceof Diverse dv ? new String[]{"Diverse", "maximal marginal relevance, lambda=" + dv.lambda() + ", from " + fetch + " candidates"}
                    : post instanceof Hybrid hy ? new String[]{"Hybrid", "full-text search of " + hy.field() + ", reciprocal rank fusion k=60, " + fetch + " from each"}
                    : new String[]{"Rerank", "by " + ((Rerank) post).field() + " with " + (host.reranker() == null ? "no reranker configured"
                    : host.reranker().describe()) + ", " + fetch + " candidates"};
            step = new QueryPlan.Step(op[0], op[1], QueryPlan.Access.PROCESS, List.of(step));
        }
        if (skip > 0 || limit >= 0) {
            step = new QueryPlan.Step("Limit", (skip > 0 ? "skip " + skip + ", then " : "") + (limit < 0 ? "all" : String.valueOf(limit)),
                    QueryPlan.Access.PROCESS, -1, ordered, -1, List.of(step));
        }
        if (analyzed) {
            step = new QueryPlan.Step("Fetch objects", "materialize the results", QueryPlan.Access.PROCESS, -1, times[3], times[2], List.of(step));
        }
        return new QueryPlan.Step("Query", target.label() + " · snapshot " + r.view.seq() + " · " + views.size()
                + (views.size() == 1 ? " segment" : " segments"), QueryPlan.Access.NONE, -1, analyzed ? times[3] : -1,
                analyzed ? times[0] : -1, List.of(step));
    }

    private static String objects(long n) {
        return String.format(Locale.ROOT, "%,d %s", n, n == 1 ? "object" : "objects");
    }

    /** A condition as plan steps (no counts). */
    private static QueryPlan.Step describe(Cond c, SegmentView v) {
        if (c instanceof Cond.And a) {
            return new QueryPlan.Step("AND", "", QueryPlan.Access.NONE, a.conds().stream().map(x -> describe(x, v)).toList());
        }
        if (c instanceof Cond.Or o) {
            return new QueryPlan.Step("OR", "", QueryPlan.Access.NONE, o.conds().stream().map(x -> describe(x, v)).toList());
        }
        if (c instanceof Cond.Not n) {
            return new QueryPlan.Step("NOT", "", QueryPlan.Access.NONE, List.of(describe(n.cond(), v)));
        }
        String access = Executor.access(c, v);
        return new QueryPlan.Step(c.toString(), bracket(access), access(access), List.of());
    }

    /** Measured conditions (flat, with depths) as nested steps. */
    private static List<QueryPlan.Step> nest(List<Profile.Condition> flat) {
        List<QueryPlan.Step> out = new ArrayList<>();
        int[] at = {0};
        while (at[0] < flat.size()) {
            out.add(nest(flat, at));
        }
        return out;
    }

    private static QueryPlan.Step nest(List<Profile.Condition> flat, int[] at) {
        Profile.Condition c = flat.get(at[0]++);
        List<QueryPlan.Step> kids = new ArrayList<>();
        while (at[0] < flat.size() && flat.get(at[0]).depth() > c.depth()) {
            kids.add(nest(flat, at));
        }
        boolean group = c.text().equals("AND") || c.text().equals("OR") || c.text().equals("NOT");
        return new QueryPlan.Step(c.text(), group ? "" : bracket(c.access()), group ? QueryPlan.Access.NONE : access(c.access()),
                c.in(), c.out(), c.nanos(), kids);
    }

    /** {@code [postings]}; a row scan says so plainly. */
    private static String bracket(String access) {
        return access.isEmpty() ? "" : access.equals("scan") ? "[scan: reads each row]" : "[" + access + "]";
    }

    /** Access kind of an access description. */
    private static QueryPlan.Access access(String description) {
        if (description.startsWith("scan") || description.startsWith("decode")) {
            return QueryPlan.Access.SCAN;
        }
        return description.contains("full-text") ? QueryPlan.Access.FULLTEXT : QueryPlan.Access.INDEX;
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
            if (nearest != null) {
                return near().stream().map(Executor.Near::hit).toList();
            }
            if (keys.isEmpty()) {
                return ex.hits(skip, limit);
            }
            return ex.sorted(sortKeys(), skip, limit);
        }

        /** Nearest-neighbour hits with distances; the path must be a vector of the query's dimension. */
        List<Executor.Near> near() {
            String path = planner.storedPath(nearest.path());
            TypeDef t = planner.typeOf(path);
            if (nearest.query() == null) {
                return textSearch(path, t);
            }
            if (t == null || t.kind() != ValueType.VECTOR) {
                throw new IllegalArgumentException("nearest(): '" + nearest.path() + "' of " + target.label()
                        + " is not a vector field" + (t == null ? "" : " (" + t + ")"));
            }
            if (t.dim() > 0 && t.dim() != nearest.query().length) {
                throw new IllegalArgumentException("nearest(): query vector has " + nearest.query().length
                        + " dimensions, " + nearest.path() + " has " + t.dim());
            }
            VectorDistance d = nearest.distance() != null ? nearest.distance() : t.distance();
            Post post = nearest.post();
            if (post == null) {
                return ex.nearest(path, nearest.query(), d, skip, limit, candidates());
            }
            if (limit < 0) {
                throw new IllegalStateException((post instanceof Diverse ? "diverse()" : "rerank()") + " needs a limit: nearest(field, query, k)");
            }
            int k = (int) Math.min(Integer.MAX_VALUE, skip + limit);
            int fetch = Math.max(k, post.fetch() > 0 ? post.fetch() : Math.max(20, 4 * k));
            List<Executor.Near> pool = ex.nearest(path, nearest.query(), d, 0, fetch, candidates());
            List<Executor.Near> ordered = post instanceof Diverse dv ? mmr(pool, path, d, dv.lambda(), k)
                    : post instanceof Hybrid h ? fuse(pool, h, fetch, path, d, k) : rerank(pool, (Rerank) post, k);
            return ordered.subList((int) Math.min(skip, ordered.size()), ordered.size());
        }

        /** Scores of the last search, rerank or fusion (for listWithDistance). */
        Map<Executor.Near, Double> scores;

        /** Full-text search of {@code path}, by BM25; rerank applies to it as to a vector search. */
        private List<Executor.Near> textSearch(String path, TypeDef t) {
            if (t == null || t.kind() != ValueType.STRING) {
                throw new IllegalArgumentException("search(): '" + nearest.path() + "' of " + target.label()
                        + " is not a string field" + (t == null ? "" : " (" + t + ")"));
            }
            TextQuery q = TextQuery.parse(nearest.text(), Analyzers.get(t.name()));
            Post post = nearest.post();
            List<Executor.Near> found;
            if (post == null) {
                found = ex.search(path, q, skip, limit);
            } else {
                if (limit < 0) {
                    throw new IllegalStateException("rerank() needs a limit: search(field, text, k)");
                }
                int k = (int) Math.min(Integer.MAX_VALUE, skip + limit);
                int fetch = Math.max(k, post.fetch() > 0 ? post.fetch() : Math.max(20, 4 * k));
                List<Executor.Near> ordered = rerank(ex.search(path, q, 0, fetch), (Rerank) post, k);
                return ordered.subList((int) Math.min(skip, ordered.size()), ordered.size());
            }
            scores = new HashMap<>();
            found.forEach(n -> scores.put(n, -n.distance()));
            return found;
        }

        /**
         * Reciprocal rank fusion of the vector candidates and the full-text results (constant 60, as Elasticsearch):
         * the best {@code k}; objects found only by text get their vector distance (NaN without a vector).
         */
        private List<Executor.Near> fuse(List<Executor.Near> vector, Hybrid h, int fetch, String vectorPath, VectorDistance d, int k) {
            String textPath = planner.storedPath(h.field());
            TypeDef tt = planner.typeOf(textPath);
            if (tt == null || tt.kind() != ValueType.STRING) {
                throw new IllegalArgumentException("hybrid(): '" + h.field() + "' of " + target.label() + " is not a string field");
            }
            TextQuery q = TextQuery.parse(h.query() != null ? h.query() : nearest.text(), Analyzers.get(tt.name()));
            List<Executor.Near> text = ex.search(textPath, q, 0, fetch);
            Map<Long, Double> rrf = new HashMap<>();
            Map<Long, Executor.Near> byOrder = new LinkedHashMap<>();
            for (int i = 0; i < vector.size(); i++) {
                Executor.Near n = vector.get(i);
                rrf.merge(n.order(), 1.0 / (60 + i + 1), Double::sum);
                byOrder.put(n.order(), n);
            }
            for (int i = 0; i < text.size(); i++) {
                Executor.Near n = text.get(i);
                rrf.merge(n.order(), 1.0 / (60 + i + 1), Double::sum);
                byOrder.computeIfAbsent(n.order(), o -> {
                    Object v = Executor.value(n.hit(), vectorPath);
                    double dist = v instanceof float[] f && f.length == nearest.query().length ? d.distance(f, nearest.query()) : Double.NaN;
                    return new Executor.Near(n.hit(), dist, o);
                });
            }
            List<Executor.Near> all = new ArrayList<>(byOrder.values());
            all.sort(Comparator.comparingDouble((Executor.Near n) -> -rrf.get(n.order())).thenComparingLong(Executor.Near::order));
            scores = new HashMap<>();
            List<Executor.Near> out = new ArrayList<>();
            for (int i = 0; i < Math.min(k, all.size()); i++) {
                out.add(all.get(i));
                scores.put(all.get(i), rrf.get(all.get(i).order()));
            }
            return out;
        }

        /** Greedy maximal marginal relevance over {@code pool} (closest first): {@code k} picks. */
        private List<Executor.Near> mmr(List<Executor.Near> pool, String path, VectorDistance d, double lambda, int k) {
            List<float[]> vectors = new ArrayList<>();
            for (Executor.Near n : pool) {
                vectors.add((float[]) Executor.value(n.hit(), path));
            }
            List<Executor.Near> picked = new ArrayList<>();
            List<Integer> pickedIdx = new ArrayList<>();
            boolean[] used = new boolean[pool.size()];
            while (picked.size() < Math.min(k, pool.size())) {
                int best = -1;
                double bestScore = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < pool.size(); i++) {
                    if (used[i]) {
                        continue;
                    }
                    double nearestPicked = Double.POSITIVE_INFINITY;    // distance to the closest picked object
                    for (int j : pickedIdx) {
                        nearestPicked = Math.min(nearestPicked, d.distance(vectors.get(i), vectors.get(j)));
                    }
                    double score = pickedIdx.isEmpty() ? -pool.get(i).distance()
                            : -lambda * pool.get(i).distance() + (1 - lambda) * nearestPicked;
                    if (Double.isNaN(score)) {
                        score = Double.NEGATIVE_INFINITY;
                    }
                    if (best < 0 || score > bestScore) {
                        best = i;
                        bestScore = score;
                    }
                }
                used[best] = true;
                picked.add(pool.get(best));
                pickedIdx.add(best);
            }
            return picked;
        }

        /** The candidates ordered by the reranker's score (ties: vector order), the best {@code k}. */
        private List<Executor.Near> rerank(List<Executor.Near> pool, Rerank r, int k) {
            Reranker reranker = host.reranker();
            if (reranker == null) {
                throw new IllegalStateException("no reranker configured: set kookaburra.reranker=ollama and kookaburra.reranker-model,"
                        + " or Config.withReranker(...)");
            }
            String field = planner.storedPath(r.field());
            List<String> texts = new ArrayList<>();
            for (Executor.Near n : pool) {
                Object v = Executor.value(n.hit(), field);
                texts.add(v == null ? "" : String.valueOf(v));
            }
            double[] score = pool.isEmpty() ? new double[0] : reranker.score(r.query() != null ? r.query() : nearest.text(), texts);
            Integer[] order = new Integer[pool.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]));
            scores = new HashMap<>();
            List<Executor.Near> out = new ArrayList<>();
            for (int i = 0; i < Math.min(k, order.length); i++) {
                Executor.Near n = pool.get(order[i]);
                out.add(n);
                scores.put(n, score[order[i]]);
            }
            return out;
        }

        int candidates() {
            return nearest.candidates() < 0 ? host.vectorEfSearch() : nearest.candidates();
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
