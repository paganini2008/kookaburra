package com.github.kookaburra.query;

import com.github.kookaburra.exec.Executor;
import com.github.kookaburra.field.Field;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Projected query results ({@code map} / {@code select}). Scalar and multi-valued fields are read from
 * columns / records without materializing objects. {@code distinct}, {@code skip} and {@code limit} here
 * apply to the projected values, after the source stream's own sort / slice.
 *
 * @param <R> projected type
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Projection<R> {

    private final QueryStream<?> source;
    private final List<Field<?, ?>> fields;
    private final Function<Object[], R> combine;
    private final boolean distinct;
    private final long skip;
    private final long limit;

    Projection(QueryStream<?> source, List<Field<?, ?>> fields, Function<Object[], R> combine) {
        this(source, fields, combine, false, 0, -1);
    }

    private Projection(QueryStream<?> source, List<Field<?, ?>> fields, Function<Object[], R> combine,
                       boolean distinct, long skip, long limit) {
        this.source = source;
        this.fields = fields;
        this.combine = combine;
        this.distinct = distinct;
        this.skip = skip;
        this.limit = limit;
    }

    public Projection<R> distinct() {
        return new Projection<>(source, fields, combine, true, skip, limit);
    }

    public Projection<R> skip(long n) {
        QueryStream.require(n >= 0, "negative skip");
        return new Projection<>(source, fields, combine, distinct, skip + n, limit < 0 ? -1 : Math.max(0, limit - n));
    }

    public Projection<R> limit(long n) {
        QueryStream.require(n >= 0, "negative limit");
        return new Projection<>(source, fields, combine, distinct, skip, limit < 0 ? n : Math.min(limit, n));
    }

    public List<R> toList() {
        try (QueryStream<?>.Run r = source.run()) {
            List<R> values = new ArrayList<>();
            for (Executor.Hit h : r.hits()) {
                Object[] cache = new Object[1];
                Object[] v = new Object[fields.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = r.value(h, fields.get(i), cache);
                }
                values.add(combine.apply(v));
            }
            if (distinct) {
                values = new ArrayList<>(new LinkedHashSet<>(values));
            }
            int from = (int) Math.min(skip, values.size());
            int to = limit < 0 ? values.size() : (int) Math.min(values.size(), from + limit);
            return new ArrayList<>(values.subList(from, to));
        }
    }

    public Optional<R> first() {
        List<R> l = limit(1).toList();
        return l.isEmpty() ? Optional.empty() : Optional.ofNullable(l.get(0));
    }

    public long count() {
        return toList().size();
    }

    public void forEach(Consumer<? super R> action) {
        toList().forEach(action);
    }

    public Stream<R> stream() {
        return toList().stream();
    }
}
