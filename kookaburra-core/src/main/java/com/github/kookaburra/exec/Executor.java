package com.github.kookaburra.exec;

import com.github.kookaburra.mapping.Canonical;
import com.github.kookaburra.mapping.TypeDef;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

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

    public List<SegmentView> views() {
        return read.queryViews(collection, types, cond, materializer);
    }

    /** Matching documents of one view. */
    public RoaringBitmap matches(SegmentView v) {
        RoaringBitmap universe = v.live(read.seq());
        if (types != null) {
            universe = v.classes(types, universe);
        }
        return eval(cond, v, universe);
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
        if (c instanceof Cond.Leaf l) {
            ColumnIndex idx = v.index(l.path());
            return idx != null && indexed(l, idx, new RoaringBitmap()) != null ? idx.describe() : "scan";
        }
        return "";
    }
}
