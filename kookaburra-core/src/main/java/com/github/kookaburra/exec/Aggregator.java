package com.github.kookaburra.exec;

import com.github.kookaburra.mapping.Canonical;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Grouping and aggregation over canonical values (design §10.4).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Aggregator {

    /** Aggregate function. */
    public enum Fn { COUNT, COUNT_DISTINCT, SUM, AVG, MIN, MAX }

    /**
     * One aggregate column.
     *
     * @param path stored path, or null for {@code COUNT(*)}
     */
    public record Spec(Fn fn, String path, TypeDef type) {
    }

    /** One group: canonical key values and aggregate results (Long / Double / canonical value / null). */
    public record Group(Object[] keys, Object[] values) {
    }

    private Aggregator() {
    }

    /**
     * Groups the matches of {@code ex}. {@code GROUP BY key} with only {@code COUNT(*)} is answered per segment
     * from postings ({@code andCardinality} per value, no document visited); otherwise as {@link #run(Consumer, List, List)}.
     */
    public static List<Group> run(Executor ex, List<String> keyPaths, List<Spec> specs) {
        boolean countsOnly = keyPaths.size() == 1 && specs.stream().allMatch(s -> s.fn() == Fn.COUNT && s.path() == null);
        if (!countsOnly) {
            return run(ex::forEach, keyPaths, specs);
        }
        Map<List<Object>, State[]> groups = new LinkedHashMap<>();
        ByOrdinal byOrdinal = new ByOrdinal(keyPaths.get(0), specs, groups);
        for (SegmentView v : ex.views()) {
            RoaringBitmap m = ex.matches(v);
            ColumnIndex c = m.isEmpty() ? null : v.index(keyPaths.get(0));
            long[] counts = c == null ? null : c.countByOrdinal(m);
            if (counts != null) {
                for (int o = 0; o < counts.length; o++) {
                    if (counts[o] > 0) {
                        State[] st = groups.computeIfAbsent(Arrays.asList(o == 0 ? null : c.key(o - 1)), x -> newStates(specs));
                        for (State state : st) {
                            state.count += counts[o];
                        }
                    }
                }
            } else {
                m.forEach((int d) -> {
                    Executor.Hit h = new Executor.Hit(v, d);
                    if (!byOrdinal.add(h)) {
                        addByValue(groups, keyPaths, specs, h);
                    }
                });
            }
        }
        byOrdinal.flush();
        return results(groups, keyPaths, specs);
    }

    /** Groups the hits of {@code source} by {@code keyPaths} (empty = one global group) and computes {@code specs}. */
    public static List<Group> run(Consumer<Predicate<Executor.Hit>> source,
                                  List<String> keyPaths, List<Spec> specs) {
        Map<List<Object>, State[]> groups = new LinkedHashMap<>();
        ByOrdinal byOrdinal = keyPaths.size() == 1 ? new ByOrdinal(keyPaths.get(0), specs, groups) : null;
        source.accept(h -> {
            if (byOrdinal == null || !byOrdinal.add(h)) {
                addByValue(groups, keyPaths, specs, h);
            }
            return true;
        });
        if (byOrdinal != null) {
            byOrdinal.flush();
        }
        return results(groups, keyPaths, specs);
    }

    private static void addByValue(Map<List<Object>, State[]> groups, List<String> keyPaths, List<Spec> specs, Executor.Hit h) {
        Object[] k = new Object[keyPaths.size()];
        for (int i = 0; i < k.length; i++) {
            k[i] = Executor.value(h, keyPaths.get(i));
        }
        add(groups.computeIfAbsent(Arrays.asList(k), x -> newStates(specs)), specs, h);
    }

    private static List<Group> results(Map<List<Object>, State[]> groups, List<String> keyPaths, List<Spec> specs) {
        if (keyPaths.isEmpty() && groups.isEmpty()) {
            groups.put(List.of(), newStates(specs));
        }
        List<Group> out = new ArrayList<>(groups.size());
        for (Map.Entry<List<Object>, State[]> e : groups.entrySet()) {
            Object[] values = new Object[specs.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = e.getValue()[i].result();
            }
            out.add(new Group(e.getKey().toArray(), values));
        }
        return out;
    }

    private static void add(State[] st, List<Spec> specs, Executor.Hit h) {
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            st[i].add(s.path() == null ? Boolean.TRUE : Executor.value(h, s.path()));
        }
    }

    /**
     * Single-key grouping on dictionary-encoded segment columns: states are indexed by ordinal for the current
     * segment (no key decoding, no hashing per row) and merged into the global groups once per segment.
     */
    private static class ByOrdinal {
        private final String path;
        private final List<Spec> specs;
        private final Map<List<Object>, State[]> groups;
        private SegmentView view;
        private ColumnIndex column;
        private State[][] states;

        ByOrdinal(String path, List<Spec> specs, Map<List<Object>, State[]> groups) {
            this.path = path;
            this.specs = specs;
            this.groups = groups;
        }

        /** Accumulates the hit by ordinal; false when its segment has no ordinal column for the key. */
        boolean add(Executor.Hit h) {
            if (h.view() != view) {
                flush();
                view = h.view();
                ColumnIndex c = view.index(path);
                int n = c == null ? -1 : c.ordinals();
                column = n >= 0 ? c : null;
                states = column == null ? null : new State[n + 1][];
            }
            if (column == null) {
                return false;
            }
            int o = column.ordinal(h.doc());
            State[] st = states[o];
            if (st == null) {
                st = newStates(specs);
                states[o] = st;
            }
            Aggregator.add(st, specs, h);
            return true;
        }

        void flush() {
            if (column == null) {
                return;
            }
            for (int o = 0; o < states.length; o++) {
                if (states[o] != null) {
                    Object key = o == 0 ? null : column.key(o - 1);
                    State[] into = groups.computeIfAbsent(Arrays.asList(key), x -> newStates(specs));
                    for (int i = 0; i < into.length; i++) {
                        into[i].merge(states[o][i]);
                    }
                }
            }
            column = null;
            states = null;
        }
    }

    private static State[] newStates(List<Spec> specs) {
        State[] st = new State[specs.size()];
        for (int i = 0; i < st.length; i++) {
            st[i] = new State(specs.get(i));
        }
        return st;
    }

    private static class State {
        private final Spec spec;
        private long count;
        private long longSum;
        private double doubleSum;
        private Object best;
        private Set<Object> distinct;

        State(Spec spec) {
            this.spec = spec;
        }

        void add(Object v) {
            if (v == null) {
                return;
            }
            count++;
            switch (spec.fn()) {
                case SUM, AVG -> {
                    if (v instanceof Double d) {
                        doubleSum += d;
                    } else {
                        longSum += ((Number) v).longValue();
                    }
                }
                case COUNT_DISTINCT -> {
                    if (distinct == null) {
                        distinct = new HashSet<>();
                    }
                    distinct.add(v);
                }
                case MIN -> {
                    if (best == null || Canonical.compare(spec.type(), v, best) < 0) {
                        best = v;
                    }
                }
                case MAX -> {
                    if (best == null || Canonical.compare(spec.type(), v, best) > 0) {
                        best = v;
                    }
                }
                default -> {
                }
            }
        }

        void merge(State o) {
            count += o.count;
            longSum += o.longSum;
            doubleSum += o.doubleSum;
            if (o.distinct != null) {
                if (distinct == null) {
                    distinct = new HashSet<>();
                }
                distinct.addAll(o.distinct);
            }
            if (o.best != null && (best == null
                    || Canonical.compare(spec.type(), o.best, best) * (spec.fn() == Fn.MIN ? -1 : 1) > 0)) {
                best = o.best;
            }
        }

        Object result() {
            boolean floating = spec.type() != null && spec.type().kind() == ValueType.FLOAT64;
            return switch (spec.fn()) {
                case COUNT -> count;
                case COUNT_DISTINCT -> distinct == null ? 0L : (long) distinct.size();
                case SUM -> count == 0 ? null : floating ? (Object) doubleSum : (Object) longSum;
                case AVG -> count == 0 ? null : (floating ? doubleSum : (double) longSum) / count;
                case MIN, MAX -> best;
            };
        }
    }
}
