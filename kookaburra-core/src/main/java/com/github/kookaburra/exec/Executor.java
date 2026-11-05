package com.github.kookaburra.exec;

import com.github.kookaburra.annotation.VectorDistance;
import com.github.kookaburra.mapping.Canonical;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.text.Bm25;
import com.github.kookaburra.text.TextQuery;
import com.github.kookaburra.text.Token;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Runs one planned query over the segment views of a snapshot (design §10).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Executor {

    /** One matching document. */
    public record Hit(SegmentView view, int doc) {
    }

    /** Materializes the object of a document (for residual predicates and results). */
    @FunctionalInterface
    public interface Materializer {
        Object materialize(SegmentView view, int doc);
    }

    /** Sort key on a stored path. */
    public record SortKey(String path, TypeDef type, boolean descending, boolean nullsFirst) {
    }

    private final ReadView read;
    private final String collection;
    private final Set<Integer> types;
    private final Cond cond;
    private final Materializer materializer;

    /**
     * @param types class ids to include, or null for every class of the collection
     */
    public Executor(ReadView read, String collection, Set<Integer> types, Cond cond, Materializer materializer) {
        this.read = read;
        this.collection = collection;
        this.types = types;
        this.cond = cond;
        this.materializer = materializer;
    }

    /** Measurements of this run, or null (not measured). */
    private Profile profile;

    /** Measures the filters of this executor into {@code p} (explain analyze). */
    public Executor profile(Profile p) {
        this.profile = p;
        return this;
    }

    public List<SegmentView> views() {
        return read.queryViews(collection, types, cond, materializer);
    }

    /** Matching documents of one view. */
    public RoaringBitmap matches(SegmentView v) {
        long start = System.nanoTime();
        RoaringBitmap universe = v.live(read.seq());
        if (types != null) {
            universe = v.classes(types, universe);
        }
        if (profile == null) {
            return eval(cond, v, universe);
        }
        profile.current = new ArrayList<>();
        profile.depth = 0;
        RoaringBitmap m = eval(cond, v, universe);
        profile.add(new Profile.View(v.label(), v.size(), universe.getLongCardinality(), m.getLongCardinality(),
                System.nanoTime() - start, profile.current));
        profile.current = null;
        return m;
    }

    /** Visits matches (views newest first, docs ascending) until the visitor returns false. */
    public void forEach(Predicate<Hit> visitor) {
        for (SegmentView v : views()) {
            var it = matches(v).getIntIterator();
            while (it.hasNext()) {
                if (!visitor.test(new Hit(v, it.next()))) {
                    return;
                }
            }
        }
    }

    public long count() {
        long n = 0;
        for (SegmentView v : views()) {
            n += matches(v).getLongCardinality();
        }
        return n;
    }

    /** Unordered matches after skipping {@code skip}, at most {@code limit} (negative = unlimited). */
    public List<Hit> hits(long skip, long limit) {
        List<Hit> out = new ArrayList<>();
        if (limit == 0) {
            return out;
        }
        long[] seen = {0};
        forEach(h -> {
            if (seen[0]++ >= skip) {
                out.add(h);
            }
            return limit < 0 || out.size() < limit;
        });
        return out;
    }

    /** A match and its distance to the query vector. */
    public record Near(Hit hit, double distance, long order) {
    }

    /** Exact k-nearest-neighbour search: {@code nearest(path, query, distance, skip, limit, 0)}. */
    public List<Near> nearest(String path, float[] query, VectorDistance distance, long skip, long limit) {
        return nearest(path, query, distance, skip, limit, 0);
    }

    /**
     * k-nearest-neighbour search: the matches (filters applied first) ordered by the distance of their vector at
     * {@code path} to {@code query}, closest first; ties and NaN (last) in storage order. Objects without a vector, or
     * with one of another dimension, are left out.
     *
     * <p>Per segment view: with {@code candidates > 0}, a limit, and an HNSW graph built for {@code distance}, the
     * graph is searched with {@code ef = max(candidates, skip + limit)} (approximate, like Lucene's kNN query); a
     * filter that leaves at most {@code ef} matches, or one so selective that the graph search would compare more
     * vectors than there are matches, is answered by an exact scan instead. Without a graph the vector column is
     * scanned (floats read in place), in-memory segments read their records. Top-N with a bounded heap.</p>
     *
     * @param candidates HNSW candidates per segment; 0 = exact search everywhere
     */
    public List<Near> nearest(String path, float[] query, VectorDistance distance, long skip, long limit, int candidates) {
        long bound = limit < 0 ? -1 : skip + limit;
        if (bound == 0) {
            return List.of();
        }
        Comparator<Near> closest = Comparator.comparingDouble(Near::distance).thenComparingLong(Near::order);
        PriorityQueue<Near> heap = new PriorityQueue<>(closest.reversed());
        List<Near> all = new ArrayList<>();
        ToDoubleFunction<float[]> to = distance.to(query);
        List<SegmentView> views = views();
        for (int vi = 0; vi < views.size(); vi++) {
            SegmentView v = views.get(vi);
            RoaringBitmap m = matches(v);
            if (m.isEmpty()) {
                continue;
            }
            long base = (long) vi << 32;
            VectorColumn.Sink sink = (d, dist) -> {
                Near n = new Near(new Hit(v, d), dist, base | d);
                if (bound < 0) {
                    all.add(n);
                } else if (heap.size() < bound) {
                    heap.add(n);
                } else if (closest.compare(n, heap.peek()) < 0) {
                    heap.poll();
                    heap.add(n);
                }
            };
            VectorColumn col = v.vectors(path);
            if (col != null && col.dim() == query.length) {
                int ef = ef(col, distance, bound, candidates, m);
                if (ef == 0 || !col.search(query, (int) bound, ef, m, (int) Math.min(Integer.MAX_VALUE, m.getLongCardinality()), sink)) {
                    col.scan(m, to, sink);
                }
            } else {
                m.forEach((int d) -> {
                    if (value(v, d, path) instanceof float[] f && f.length == query.length) {
                        sink.accept(d, to.applyAsDouble(f));
                    }
                });
            }
        }
        all.addAll(heap);
        all.sort(closest);
        return all.subList((int) Math.min(skip, all.size()), all.size());
    }

    /**
     * Full-text relevance search: the matches (filters first) that contain any word of {@code q}, ordered by BM25
     * (highest first; ties in storage order), with collection statistics over every view (Lucene's formula). Views
     * without a full-text index for {@code path} split their rows on the fly. Top-N with a bounded heap.
     *
     * @return hits with {@code distance = -score} (closest = most relevant) for one merge path with {@link #nearest}
     */
    public List<Near> search(String path, TextQuery q, long skip, long limit) {
        long bound = limit < 0 ? -1 : skip + limit;
        if (bound == 0) {
            return List.of();
        }
        List<SegmentView> views = views();
        long docs = 0;
        long words = 0;
        Map<String, Long> df = new HashMap<>();
        List<Map<Integer, Map<String, Integer>>> scanned = new ArrayList<>();   // views without an index: doc → words
        List<List<String>> terms = new ArrayList<>();                           // per view: words + expanded prefixes
        for (SegmentView v : views) {
            TextIndex ti = v.text(path);
            List<String> t = new ArrayList<>(q.words());
            for (List<Token> phrase : q.phrases()) {
                phrase.forEach(x -> {
                    if (!t.contains(x.word())) {
                        t.add(x.word());
                    }
                });
            }
            if (ti != null) {
                for (String p : q.prefixes()) {
                    t.addAll(ti.words(p, Integer.MAX_VALUE));
                }
                docs += ti.docCount();
                words += ti.totalWords();
                for (String w : t) {
                    df.merge(w, ti.docFrequency(w), Long::sum);
                }
                scanned.add(null);
            } else {
                Map<Integer, Map<String, Integer>> rows = new HashMap<>();
                v.live(read.seq()).forEach((int d) -> {
                    if (value(v, d, path) instanceof String s) {
                        rows.put(d, q.analyzer().frequencies(s));
                    }
                });
                for (Map<String, Integer> tf : rows.values()) {
                    int len = tf.values().stream().mapToInt(Integer::intValue).sum();
                    if (len > 0) {
                        docs++;
                        words += len;
                    }
                    for (String w : tf.keySet()) {
                        boolean asked = t.contains(w) || q.prefixes().stream().anyMatch(w::startsWith);
                        if (asked) {
                            if (!t.contains(w)) {
                                t.add(w);
                            }
                            df.merge(w, 1L, Long::sum);
                        }
                    }
                }
                scanned.add(rows);
            }
            terms.add(t);
        }
        double avg = docs == 0 ? 0 : (double) words / docs;
        long n = docs;
        Map<String, Double> idf = new HashMap<>();
        df.forEach((w, f) -> idf.put(w, Bm25.idf(n, f)));
        Comparator<Near> best = Comparator.comparingDouble(Near::distance).thenComparingLong(Near::order);
        PriorityQueue<Near> heap = new PriorityQueue<>(best.reversed());
        List<Near> all = new ArrayList<>();
        for (int vi = 0; vi < views.size(); vi++) {
            SegmentView v = views.get(vi);
            TextIndex ti = v.text(path);
            Map<Integer, Map<String, Integer>> rows = scanned.get(vi);
            List<String> t = terms.get(vi);
            RoaringBitmap m = matches(v);
            RoaringBitmap found;
            if (ti != null) {
                RoaringBitmap candidates = RoaringBitmap.and(matching(ti, q, false), m);
                found = q.phrases().isEmpty() ? candidates
                        : scan(candidates, d -> q.matches(value(v, d, path) instanceof String s ? s : null, false));
            } else {
                found = new RoaringBitmap();
                m.forEach((int d) -> {
                    Map<String, Integer> tf = rows.get(d);
                    if (tf != null && q.matches(tf, false)
                            && (q.phrases().isEmpty() || q.matches(value(v, d, path) instanceof String s ? s : null, false))) {
                        found.add(d);
                    }
                });
            }
            RoaringBitmap any = found;
            long base = (long) vi << 32;
            any.forEach((int d) -> {
                Map<String, Integer> tf = rows == null ? null : rows.get(d);
                int len = ti != null ? ti.length(d) : tf.values().stream().mapToInt(Integer::intValue).sum();
                double score = 0;
                for (String w : t) {
                    int f = ti != null ? ti.frequency(w, d) : tf.getOrDefault(w, 0);
                    score += Bm25.term(idf.getOrDefault(w, 0.0), f, len, avg);
                }
                Near hit = new Near(new Hit(v, d), -score, base | d);
                if (bound < 0) {
                    all.add(hit);
                } else if (heap.size() < bound) {
                    heap.add(hit);
                } else if (best.compare(hit, heap.peek()) < 0) {
                    heap.poll();
                    heap.add(hit);
                }
            });
        }
        all.addAll(heap);
        all.sort(best);
        return all.subList((int) Math.min(skip, all.size()), all.size());
    }

    /** HNSW candidates for one view, or 0 when the view is scanned exactly. */
    private static int ef(VectorColumn col, VectorDistance distance, long bound, int candidates, RoaringBitmap m) {
        if (candidates <= 0 || bound <= 0 || bound > 100_000 || col.graphDistance() != distance) {
            return 0;
        }
        int ef = (int) Math.max(candidates, bound);
        return m.getLongCardinality() <= ef ? 0 : ef;
    }

    /** Explain line: how {@link #nearest} searches one view. */
    public static String nearestAccess(SegmentView v, String path, int dim, VectorDistance distance, long bound, int candidates) {
        VectorColumn col = v.vectors(path);
        if (col == null || col.dim() != dim) {
            return "exact scan of the rows";
        }
        if (candidates > 0 && bound > 0 && col.graphDistance() == distance) {
            return "HNSW, ef=" + Math.max(candidates, bound) + " (" + col.describe() + "; exact when the filter is selective)";
        }
        return "exact scan of the vector column (" + col.describe() + ")";
    }

    /** Matches ordered by {@code keys}; top-N with a bounded heap when {@code limit >= 0}. */
    public List<Hit> sorted(List<SortKey> keys, long skip, long limit) {
        Comparator<Object[]> cmp = comparator(keys);
        Comparator<Keyed> byKeys = (a, b) -> cmp.compare(a.keys, b.keys);
        long bound = limit < 0 ? -1 : skip + limit;
        TopN top = new TopN(keys, cmp, bound, bound >= 0 ? new PriorityQueue<>(byKeys.reversed()) : null);
        List<SegmentView> views = views();
        for (int vi = 0; vi < views.size(); vi++) {
            SegmentView v = views.get(vi);
            RoaringBitmap m = matches(v);
            if (!m.isEmpty() && (bound <= 0 || !inIndexOrder(v, m, keys, bound, vi, top))) {
                long base = (long) vi << 32;
                m.forEach((int d) -> top.offer(new Hit(v, d), base | d));
            }
        }
        List<Keyed> all = top.all;
        if (top.heap != null) {
            all.addAll(top.heap);
        }
        all.sort(byKeys.thenComparingLong(e -> e.order));
        List<Hit> out = new ArrayList<>();
        for (int i = (int) Math.min(skip, all.size()); i < all.size(); i++) {
            out.add(all.get(i).hit);
        }
        return out;
    }

    /**
     * Top-N on one indexed sort key: walks the segment's documents in value order and stops after {@code bound}
     * matches, instead of reading the key of every match. False when the column cannot iterate in order or
     * matching nulls would sort first (then the caller scans).
     */
    private boolean inIndexOrder(SegmentView v, RoaringBitmap m, List<SortKey> keys, long bound, int vi, TopN top) {
        if (keys.size() != 1) {
            return false;
        }
        // walking in index order visits about bound * size / matches documents; scanning the matches reads every
        // match's key (several times dearer per document): only walk when that is clearly cheaper
        long matches = m.getLongCardinality();
        if ((double) bound * v.size() > 4.0 * matches * matches) {
            return false;
        }
        SortKey k = keys.get(0);
        ColumnIndex c = v.index(k.path());
        if (c == null) {
            return false;
        }
        RoaringBitmap nulls = c.nulls();
        RoaringBitmap nullMatches = nulls == null ? null : RoaringBitmap.and(nulls, m);
        if (nullMatches != null && !nullMatches.isEmpty() && k.nullsFirst()) {
            return false;
        }
        long base = (long) vi << 32;
        long[] taken = {0};
        boolean supported = c.forEachInOrder(k.descending(), d -> {
            if (m.contains(d)) {
                top.offer(new Hit(v, d), base | d);
                return ++taken[0] < bound;
            }
            return true;
        });
        if (supported && nullMatches != null && taken[0] < bound) {
            var it = nullMatches.getIntIterator();
            while (it.hasNext() && taken[0]++ < bound) {
                int d = it.next();
                top.offer(new Hit(v, d), base | d);
            }
        }
        return supported;
    }

    /** Collects hits: all of them, or the best {@code bound} in a bounded max-heap. */
    private static class TopN {
        private final List<SortKey> keys;
        private final Comparator<Object[]> cmp;
        private final long bound;
        private final PriorityQueue<Keyed> heap;
        private final List<Keyed> all = new ArrayList<>();
        private Object[] scratch;

        TopN(List<SortKey> keys, Comparator<Object[]> cmp, long bound, PriorityQueue<Keyed> heap) {
            this.keys = keys;
            this.cmp = cmp;
            this.bound = bound;
            this.heap = heap;
            this.scratch = new Object[keys.size()];
        }

        void offer(Hit h, long order) {
            Object[] k = scratch;
            for (int i = 0; i < k.length; i++) {
                k[i] = value(h, keys.get(i).path());
            }
            if (heap == null) {
                all.add(new Keyed(h, k, order));
            } else if (bound > 0) {
                // full heap: a candidate not better than the current worst cannot enter (no allocation, no heap churn)
                if (heap.size() >= bound && cmp.compare(k, heap.peek().keys) >= 0) {
                    return;
                }
                heap.add(new Keyed(h, k, order));
                if (heap.size() > bound) {
                    heap.poll();
                }
            } else {
                return;
            }
            scratch = new Object[keys.size()];
        }
    }

    private record Keyed(Hit hit, Object[] keys, long order) {
    }

    /** Comparator over arrays of canonical key values. */
    public static Comparator<Object[]> comparator(List<SortKey> keys) {
        return (a, b) -> {
            for (int i = 0; i < keys.size(); i++) {
                SortKey k = keys.get(i);
                Object x = a[i];
                Object y = b[i];
                int c;
                if (x == null || y == null) {
                    if (x == y) {
                        continue;
                    }
                    c = (x == null) == k.nullsFirst() ? -1 : 1;
                } else {
                    c = Canonical.compare(k.type(), x, y);
                    if (k.descending()) {
                        c = -c;
                    }
                }
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        };
    }

    /** Canonical value of a stored path, including the {@code $oid} / {@code $version} pseudo-columns. */
    public static Object value(Hit h, String path) {
        return value(h.view(), h.doc(), path);
    }

    static Object value(SegmentView v, int doc, String path) {
        return switch (path) {
            case Planner.OID -> v.oid(doc);
            case Planner.VERSION -> v.version(doc);
            default -> v.value(doc, path);
        };
    }

    public Object materialize(Hit h) {
        return materializer.materialize(h.view(), h.doc());
    }

    // ---------------------------------------------------------------- evaluation

    RoaringBitmap eval(Cond c, SegmentView v, RoaringBitmap u) {
        if (profile == null || profile.current == null || c instanceof Cond.Const) {
            return evaluate(c, v, u);
        }
        int at = profile.current.size();
        profile.current.add(null);                          // placeholder: parents before their children
        int depth = profile.depth++;
        long start = System.nanoTime();
        RoaringBitmap r = evaluate(c, v, u);
        long took = System.nanoTime() - start;
        profile.depth--;
        String text = c instanceof Cond.And ? "AND" : c instanceof Cond.Or ? "OR" : c instanceof Cond.Not ? "NOT" : c.toString();
        profile.current.set(at, new Profile.Condition(text, access(c, v), depth, u.getLongCardinality(), r.getLongCardinality(), took));
        return r;
    }

    private RoaringBitmap evaluate(Cond c, SegmentView v, RoaringBitmap u) {
        if (u.isEmpty()) {
            return u;
        }
        if (c instanceof Cond.Const k) {
            return k.value() ? u : new RoaringBitmap();
        }
        if (c instanceof Cond.And a) {
            RoaringBitmap r = u;
            for (Cond x : ordered(a.conds(), v)) {
                r = eval(x, v, r);
                if (r.isEmpty()) {
                    break;
                }
            }
            return r;
        }
        if (c instanceof Cond.Or o) {
            RoaringBitmap r = new RoaringBitmap();
            for (Cond x : o.conds()) {
                r.or(eval(x, v, u));
            }
            return r;
        }
        if (c instanceof Cond.Not n) {
            return RoaringBitmap.andNot(u, eval(n.cond(), v, u));
        }
        if (c instanceof Cond.Types t) {
            return v.classes(t.classIds(), u);
        }
        if (c instanceof Cond.Oids o) {
            RoaringBitmap r = new RoaringBitmap();
            for (long oid : o.oids()) {
                int d = v.find(oid, read.seq());
                if (d >= 0 && u.contains(d)) {
                    r.add(d);
                }
            }
            return r;
        }
        if (c instanceof Cond.Residual r) {
            return scan(u, d -> r.predicate().test(materializer.materialize(v, d)));
        }
        Cond.Leaf leaf = (Cond.Leaf) c;
        if (leaf instanceof Cond.Match m) {
            TextIndex ti = v.text(m.path());
            if (ti != null) {
                RoaringBitmap candidates = RoaringBitmap.and(matching(ti, m.query(), m.all()), u);
                // phrases: the index finds the documents with all their words; positions are checked on the text
                return m.query().phrases().isEmpty() ? candidates : scan(candidates, d -> leaf.test(value(v, d, leaf.path())));
            }
            return scan(u, d -> leaf.test(value(v, d, leaf.path())));
        }
        RoaringBitmap indexed = indexed(leaf, v.index(leaf.path()), u);
        if (indexed != null) {
            return RoaringBitmap.and(indexed, u);
        }
        return scan(u, d -> leaf.test(value(v, d, leaf.path())));
    }

    /** Answers a leaf from an index, or null when the index cannot. */
    static RoaringBitmap indexed(Cond.Leaf leaf, ColumnIndex idx, RoaringBitmap within) {
        if (idx == null) {
            return null;
        }
        if (leaf instanceof Cond.Eq e) {
            return idx.eq(e.key(), within);
        }
        if (leaf instanceof Cond.In in) {
            return union(idx, in.keys(), within);
        }
        if (leaf instanceof Cond.Contains c) {
            if (!c.all()) {
                return union(idx, c.keys(), within);
            }
            RoaringBitmap r = null;
            for (Object k : c.keys()) {
                RoaringBitmap b = idx.eq(k, within);
                if (b == null) {
                    return null;
                }
                r = r == null ? b.clone() : RoaringBitmap.and(r, b);
            }
            return r;
        }
        if (leaf instanceof Cond.Range r) {
            return idx.range(r.lo(), r.loInclusive(), r.hi(), r.hiInclusive(), within);
        }
        if (leaf instanceof Cond.Prefix p) {
            return idx.prefix(p.prefix(), within);
        }
        return idx.nulls();
    }

    /** Documents of a full-text index with every word ({@code all}) or any word of {@code q}; prefixes expanded. */
    static RoaringBitmap matching(TextIndex ti, TextQuery q, boolean all) {
        RoaringBitmap r = null;
        List<RoaringBitmap> parts = new ArrayList<>();
        for (String w : q.words()) {
            parts.add(ti.docs(w));
        }
        for (String p : q.prefixes()) {
            RoaringBitmap any = new RoaringBitmap();
            for (String w : ti.words(p, Integer.MAX_VALUE)) {
                any.or(ti.docs(w));
            }
            parts.add(any);
        }
        for (List<Token> phrase : q.phrases()) {
            RoaringBitmap every = null;
            for (Token t : phrase) {
                every = every == null ? ti.docs(t.word()) : RoaringBitmap.and(every, ti.docs(t.word()));
            }
            parts.add(every);
        }
        for (RoaringBitmap b : parts) {
            r = r == null ? b : all ? RoaringBitmap.and(r, b) : RoaringBitmap.or(r, b);
        }
        return r == null ? new RoaringBitmap() : r;
    }

    private static RoaringBitmap union(ColumnIndex idx, Set<Object> keys, RoaringBitmap within) {
        RoaringBitmap r = new RoaringBitmap();
        for (Object k : keys) {
            RoaringBitmap b = idx.eq(k, within);
            if (b == null) {
                return null;
            }
            r.or(b);
        }
        return r;
    }

    private static RoaringBitmap scan(RoaringBitmap u, IntPredicate test) {
        RoaringBitmap r = new RoaringBitmap();
        u.forEach((int d) -> {
            if (test.test(d)) {
                r.add(d);
            }
        });
        return r;
    }

    /** Conjunct order: indexed leaves first, then type / OID filters, scans, residuals last. */
    private static List<Cond> ordered(List<Cond> conds, SegmentView v) {
        List<Cond> out = new ArrayList<>(conds);
        out.sort(Comparator.comparingInt(c -> cost(c, v)));
        return out;
    }

    static int cost(Cond c, SegmentView v) {
        if (c instanceof Cond.Oids) return 0;
        if (c instanceof Cond.Match m) return v.text(m.path()) != null ? 1 : 3;
        if (c instanceof Cond.Leaf l && indexedHint(l, v.index(l.path()))) return 1;
        if (c instanceof Cond.Types) return 2;
        if (c instanceof Cond.Leaf) return 3;
        if (c instanceof Cond.Residual) return 5;
        return 4;
    }

    private static boolean indexedHint(Cond.Leaf l, ColumnIndex idx) {
        return idx != null && idx.hasPostings() && (l instanceof Cond.Eq || l instanceof Cond.In || l instanceof Cond.Contains);
    }

    /** Explain line for a leaf on a view: how it is evaluated. */
    public static String access(Cond c, SegmentView v) {
        if (c instanceof Cond.Residual) {
            return "decode rows";
        }
        if (c instanceof Cond.Types) {
            return "$type";
        }
        if (c instanceof Cond.Oids) {
            return "OID lookup";
        }
        if (c instanceof Cond.Match m) {
            TextIndex ti = v.text(m.path());
            return ti != null ? ti.describe() : "scan (words of each row)";
        }
        if (c instanceof Cond.Leaf l) {
            ColumnIndex idx = v.index(l.path());
            return idx != null && indexed(l, idx, new RoaringBitmap()) != null ? idx.describe() : "scan";
        }
        return "";
    }
}
