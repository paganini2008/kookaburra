package com.github.kookaburra.query;

import com.github.kookaburra.exec.Aggregator;
import com.github.kookaburra.exec.Executor;
import com.github.kookaburra.field.Field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Grouped query (SQL {@code GROUP BY … HAVING … ORDER BY … LIMIT}). Rows hold the group keys (named by
 * field path) followed by the aggregates (named by {@link Agg#name()}). Aggregates ignore nulls;
 * {@code count()} counts rows. Without {@code agg} the rows are the distinct key tuples.
 *
 * @param <T> entity type
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class GroupStream<T> {

    private final QueryStream<T> source;
    private final List<Field<? super T, ?>> keys;
    private final List<Agg<? super T>> aggs;
    private final List<Predicate<Row>> having;
    private final List<Comparator<Row>> orders;
    private final long skip;
    private final long limit;

    GroupStream(QueryStream<T> source, List<Field<? super T, ?>> keys) {
        this(source, keys, List.of(), List.of(), List.of(), 0, -1);
        for (Field<? super T, ?> k : keys) {
            QueryStream.require(k.type().isScalar(), "cannot group by " + k.type() + " field " + k.path());
        }
    }

    private GroupStream(QueryStream<T> source, List<Field<? super T, ?>> keys, List<Agg<? super T>> aggs,
                        List<Predicate<Row>> having, List<Comparator<Row>> orders, long skip, long limit) {
        this.source = source;
        this.keys = keys;
        this.aggs = aggs;
        this.having = having;
        this.orders = orders;
        this.skip = skip;
        this.limit = limit;
    }

    @SafeVarargs
    public final GroupStream<T> agg(Agg<? super T>... aggregates) {
        for (Agg<? super T> a : aggregates) {
            QueryStream.require(a.kind() != Agg.Kind.KEY, "key(..) is not an aggregate; use it in having / sortedBy");
        }
        List<Agg<? super T>> a = new ArrayList<>(aggs);
        a.addAll(List.of(aggregates));
        return new GroupStream<>(source, keys, List.copyOf(a), having, orders, skip, limit);
    }

    /** Filters groups, e.g. {@code having(count().gt(10))}. */
    public GroupStream<T> having(Predicate<Row> predicate) {
        List<Predicate<Row>> h = new ArrayList<>(having);
        h.add(predicate);
        return new GroupStream<>(source, keys, aggs, List.copyOf(h), orders, skip, limit);
    }

    /** Orders groups, e.g. {@code sortedBy(count().desc(), key(Person_.city).asc())}. */
    @SafeVarargs
    public final GroupStream<T> sortedBy(Comparator<Row>... comparators) {
        List<Comparator<Row>> o = new ArrayList<>(orders);
        o.addAll(List.of(comparators));
        return new GroupStream<>(source, keys, aggs, having, List.copyOf(o), skip, limit);
    }

    public GroupStream<T> skip(long n) {
        QueryStream.require(n >= 0, "negative skip");
        return new GroupStream<>(source, keys, aggs, having, orders, skip + n, limit < 0 ? -1 : Math.max(0, limit - n));
    }

    public GroupStream<T> limit(long n) {
        QueryStream.require(n >= 0, "negative limit");
        return new GroupStream<>(source, keys, aggs, having, orders, skip, limit < 0 ? n : Math.min(limit, n));
    }

    public List<Row> toList() {
        try (QueryStream<T>.Run r = source.run()) {
            List<String> keyPaths = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Field<? super T, ?> k : keys) {
                keyPaths.add(r.planner.storedPath(k.path()));
                names.add(k.path());
            }
            List<Aggregator.Spec> specs = new ArrayList<>();
            for (Agg<? super T> a : aggs) {
                names.add(a.name());
                if (a.field() == null) {
                    specs.add(new Aggregator.Spec(Aggregator.Fn.COUNT, null, null));
                } else {
                    String path = r.planner.storedPath(a.field().path());
                    specs.add(new Aggregator.Spec(fn(a.kind()), path, r.typeOf(a.field())));
                }
            }
            List<Executor.Hit> sliced = source.filtersOnly() ? null : r.hits();
            List<Aggregator.Group> groups = sliced == null ? Aggregator.run(r.ex, keyPaths, specs) : Aggregator.run(v -> {
                for (Executor.Hit h : sliced) {
                    if (!v.test(h)) {
                        return;
                    }
                }
            }, keyPaths, specs);

            List<Row> rows = new ArrayList<>();
            for (Aggregator.Group g : groups) {
                Object[] values = new Object[names.size()];
                for (int i = 0; i < keys.size(); i++) {
                    values[i] = keyPaths.get(i).startsWith("$") ? g.keys()[i] : Values.toJava(keys.get(i), g.keys()[i]);
                }
                for (int i = 0; i < aggs.size(); i++) {
                    Agg<? super T> a = aggs.get(i);
                    Object v = g.values()[i];
                    boolean minMax = a.kind() == Agg.Kind.MIN || a.kind() == Agg.Kind.MAX;
                    values[keys.size() + i] = minMax && !specs.get(i).path().startsWith("$") ? Values.toJava(a.field(), v) : v;
                }
                Row row = new Row(names, values);
                if (having.stream().allMatch(p -> p.test(row))) {
                    rows.add(row);
                }
            }
            if (!orders.isEmpty()) {
                Comparator<Row> c = orders.get(0);
                for (int i = 1; i < orders.size(); i++) {
                    c = c.thenComparing(orders.get(i));
                }
                rows.sort(c);
            }
            int from = (int) Math.min(skip, rows.size());
            int to = limit < 0 ? rows.size() : (int) Math.min(rows.size(), from + limit);
            return new ArrayList<>(rows.subList(from, to));
        }
    }

    private static Aggregator.Fn fn(Agg.Kind k) {
        return switch (k) {
            case COUNT -> Aggregator.Fn.COUNT;
            case COUNT_DISTINCT -> Aggregator.Fn.COUNT_DISTINCT;
            case SUM -> Aggregator.Fn.SUM;
            case AVG -> Aggregator.Fn.AVG;
            case MIN -> Aggregator.Fn.MIN;
            case MAX -> Aggregator.Fn.MAX;
            case KEY -> throw new IllegalStateException("KEY is not an aggregate");
        };
    }

    public Optional<Row> first() {
        List<Row> l = limit(1).toList();
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public long count() {
        return toList().size();
    }

    public void forEach(Consumer<? super Row> action) {
        toList().forEach(action);
    }

    public Stream<Row> stream() {
        return toList().stream();
    }
}
