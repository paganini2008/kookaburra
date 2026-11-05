package com.github.kookaburra.segment;

import com.github.kookaburra.annotation.VectorDistance;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;
import java.util.function.ToDoubleFunction;

/**
 * Hierarchical navigable small world graph (Malkov and Yashunin, 2016), the approximate nearest-neighbour index of
 * Lucene, pgvector, Qdrant and hnswlib: nodes on a random number of layers, greedy descent through the sparse upper
 * layers, beam search with {@code ef} candidates on the bottom layer, neighbours chosen with the diversity heuristic.
 * Nodes are the slots of a segment's vector column.
 *
 * <p>Distances travel as {@code long} keys (sortable float bits, node) so heaps hold primitives.</p>
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
final class Hnsw {

    /** Graph parameters: {@code m} neighbours on the upper layers, {@code 2m} on the bottom one. */
    record Params(int m, int efConstruction, int minRows) {
    }

    /** Adjacency of a graph (built in memory or read from a segment). */
    interface Graph {
        int size();

        int entry();

        int maxLevel();

        /** Copies the neighbours of {@code node} on {@code level} to {@code out}; returns their number. */
        int neighbors(int node, int level, int[] out);
    }

    /** Vector of a slot: the stored array, or {@code scratch} filled. */
    interface Vectors {
        float[] get(int slot, float[] scratch);
    }

    private Hnsw() {
    }

    // ---------------------------------------------------------------- keys

    /** Key ordered by distance, then node; NaN sorts as +infinity. */
    static long key(double distance, int node) {
        float f = (float) distance;
        int bits = Float.floatToRawIntBits(Float.isNaN(f) ? Float.POSITIVE_INFINITY : f);
        bits ^= (bits >> 31) & 0x7fffffff;
        return ((long) bits << 32) | (node & 0xffffffffL);
    }

    static int node(long key) {
        return (int) key;
    }

    static float distance(long key) {
        int bits = (int) (key >>> 32);
        bits ^= (bits >> 31) & 0x7fffffff;
        return Float.intBitsToFloat(bits);
    }

    // ---------------------------------------------------------------- search

    /** Search state over one graph: visited marks, heaps and buffers, reused between searches (one thread). */
    static final class Searcher {
        private final Graph graph;
        private final Vectors vectors;
        private final int[] stamp;
        private final int[] nb;
        private final float[] scratch;
        private final LongHeap candidates = new LongHeap(false, 64);
        private int gen;
        private int visited;

        Searcher(Graph graph, Vectors vectors, int dim, int maxConn) {
            this.graph = graph;
            this.vectors = vectors;
            this.stamp = new int[graph.size()];
            this.nb = new int[maxConn + 1];
            this.scratch = new float[dim];
        }

        int visited() {
            return visited;
        }

        private double dist(ToDoubleFunction<float[]> to, int node) {
            visited++;
            return to.applyAsDouble(vectors.get(node, scratch));
        }

        /** Greedy descent from {@code ep} through the levels {@code from} down to {@code to + 1}; returns the closest key. */
        long descend(ToDoubleFunction<float[]> q, long ep, int from, int to) {
            for (int l = from; l > to; l--) {
                boolean moved = true;
                while (moved) {
                    moved = false;
                    int c = graph.neighbors(node(ep), l, nb);
                    for (int i = 0; i < c; i++) {
                        long k = key(dist(q, nb[i]), nb[i]);
                        if (k < ep) {
                            ep = k;
                            moved = true;
                        }
                    }
                }
            }
            return ep;
        }

        /**
         * Beam search on one level starting at the keys in {@code entries}: fills {@code results} (a max-heap) with the
         * {@code ef} closest nodes that pass {@code accept} (null = all). Every node is explored as a stepping stone,
         * accepted or not (Lucene's filtered search). False when more than {@code visitLimit} vectors were compared.
         */
        boolean searchLevel(ToDoubleFunction<float[]> q, long[] entries, int ef, int level, IntPredicate accept,
                            LongHeap results, int visitLimit) {
            if (++gen == 0) {
                Arrays.fill(stamp, 0);
                gen = 1;
            }
            candidates.clear();
            for (long e : entries) {
                int n = node(e);
                if (stamp[n] != gen) {
                    stamp[n] = gen;
                    candidates.push(e);
                    if (accept == null || accept.test(n)) {
                        offer(results, e, ef);
                    }
                }
            }
            while (!candidates.isEmpty()) {
                long c = candidates.pop();
                if (results.size() >= ef && c > results.peek()) {
                    break;
                }
                int count = graph.neighbors(node(c), level, nb);
                for (int i = 0; i < count; i++) {
                    int n = nb[i];
                    if (stamp[n] == gen) {
                        continue;
                    }
                    stamp[n] = gen;
                    if (visited >= visitLimit) {
                        return false;
                    }
                    long k = key(dist(q, n), n);
                    if (results.size() < ef || k < results.peek()) {
                        candidates.push(k);
                        if (accept == null || accept.test(n)) {
                            offer(results, k, ef);
                        }
                    }
                }
            }
            return true;
        }

        /**
         * k-nearest search over the whole graph: the {@code ef} (at least {@code k}) closest accepted nodes, closest
         * first; null when the search compared more than {@code visitLimit} vectors (the caller scans exactly).
         */
        long[] search(ToDoubleFunction<float[]> q, int k, int ef, IntPredicate accept, int visitLimit) {
            visited = 0;
            if (graph.entry() < 0) {
                return new long[0];
            }
            long ep = key(dist(q, graph.entry()), graph.entry());
            ep = descend(q, ep, graph.maxLevel(), 0);
            LongHeap results = new LongHeap(true, Math.max(k, ef) + 1);
            if (!searchLevel(q, new long[]{ep}, Math.max(k, ef), 0, accept, results, visitLimit)) {
                return null;
            }
            long[] sorted = results.sortedAscending();
            return sorted.length <= k ? sorted : Arrays.copyOf(sorted, k);
        }
    }

    private static void offer(LongHeap results, long k, int ef) {
        results.push(k);
        if (results.size() > ef) {
            results.pop();
        }
    }

    // ---------------------------------------------------------------- build

    /** Builds a graph in memory by inserting the vectors in slot order (deterministic: seeded levels). */
    static final class Builder implements Graph, Vectors {
        private final float[][] vectors;
        private final VectorDistance distance;
        private final int m;
        private final int m0;
        private final int efConstruction;
        private final int[] levels;
        /** adj[node][level] = {count, neighbours...}, capacity maxConn + 1. */
        private final int[][][] adj;
        private final Searcher searcher;
        private int entry = -1;
        private int maxLevel = -1;

        Builder(float[][] vectors, int dim, VectorDistance distance, Params p) {
            // cosine: build on unit vectors with 1 - dot (same distances, no norms per comparison)
            this.vectors = distance == VectorDistance.COSINE ? normalized(vectors) : vectors;
            this.distance = distance;
            this.m = p.m();
            this.m0 = 2 * p.m();
            this.efConstruction = p.efConstruction();
            int n = vectors.length;
            this.levels = new int[n];
            this.adj = new int[n][][];
            double ml = 1 / Math.log(m);
            SplittableRandom random = new SplittableRandom(0x6B6B62L ^ n);
            for (int i = 0; i < n; i++) {
                levels[i] = Math.min(16, (int) (-Math.log(1 - random.nextDouble()) * ml));
            }
            this.searcher = new Searcher(this, this, dim, m0);
        }

        private static float[][] normalized(float[][] in) {
            float[][] out = new float[in.length][];
            for (int i = 0; i < in.length; i++) {
                float[] v = in[i];
                double norm = Math.sqrt(VectorDistance.dot(v, v));
                float[] u = new float[v.length];
                for (int j = 0; j < v.length && norm > 0; j++) {
                    u[j] = (float) (v[j] / norm);
                }
                out[i] = u;
            }
            return out;
        }

        /** Distance to {@code q} as used while building. */
        private ToDoubleFunction<float[]> to(float[] q) {
            return distance == VectorDistance.COSINE ? v -> 1 - VectorDistance.dot(v, q) : distance.to(q);
        }

        private double between(float[] a, float[] b) {
            return distance == VectorDistance.COSINE ? 1 - VectorDistance.dot(a, b) : distance.distance(a, b);
        }

        Builder build() {
            for (int i = 0; i < vectors.length; i++) {
                insert(i);
            }
            return this;
        }

        private int maxConn(int level) {
            return level == 0 ? m0 : m;
        }

        @Override
        public int size() {
            return vectors.length;
        }

        @Override
        public int entry() {
            return entry;
        }

        @Override
        public int maxLevel() {
            return maxLevel;
        }

        int level(int node) {
            return levels[node];
        }

        int m() {
            return m;
        }

        @Override
        public int neighbors(int node, int level, int[] out) {
            int[] a = adj[node][level];
            System.arraycopy(a, 1, out, 0, a[0]);
            return a[0];
        }

        @Override
        public float[] get(int slot, float[] scratch) {
            return vectors[slot];
        }

        private void insert(int i) {
            int lvl = levels[i];
            adj[i] = new int[lvl + 1][];
            for (int l = 0; l <= lvl; l++) {
                adj[i][l] = new int[maxConn(l) + 1];
            }
            if (entry < 0) {
                entry = i;
                maxLevel = lvl;
                return;
            }
            ToDoubleFunction<float[]> q = to(vectors[i]);
            long ep = key(q.applyAsDouble(vectors[entry]), entry);
            ep = searcher.descend(q, ep, maxLevel, lvl);
            long[] entries = {ep};
            for (int l = Math.min(lvl, maxLevel); l >= 0; l--) {
                LongHeap results = new LongHeap(true, efConstruction + 1);
                searcher.searchLevel(q, entries, efConstruction, l, null, results, Integer.MAX_VALUE);
                long[] found = results.sortedAscending();
                int[] chosen = diverse(found, maxConn(l));
                int[] a = adj[i][l];
                a[0] = chosen.length;
                System.arraycopy(chosen, 0, a, 1, chosen.length);
                for (int n : chosen) {
                    link(n, i, l);
                }
                entries = found;
            }
            if (lvl > maxLevel) {
                maxLevel = lvl;
                entry = i;
            }
        }

        /** Adds {@code i} to the neighbours of {@code n}; a full list is re-chosen with the heuristic. */
        private void link(int n, int i, int level) {
            int[] a = adj[n][level];
            int max = maxConn(level);
            if (a[0] < max) {
                a[++a[0]] = i;
                return;
            }
            ToDoubleFunction<float[]> q = to(vectors[n]);
            long[] keys = new long[a[0] + 1];
            for (int j = 0; j < a[0]; j++) {
                keys[j] = key(q.applyAsDouble(vectors[a[j + 1]]), a[j + 1]);
            }
            keys[a[0]] = key(q.applyAsDouble(vectors[i]), i);
            Arrays.sort(keys);
            int[] chosen = diverse(keys, max);
            a[0] = chosen.length;
            System.arraycopy(chosen, 0, a, 1, chosen.length);
        }

        /**
         * Neighbour selection heuristic: closest first, a candidate is kept only when it is closer to the base node
         * than to every neighbour kept so far (spreads links over directions, keeps clusters connected).
         */
        private int[] diverse(long[] sorted, int max) {
            int[] out = new int[Math.min(max, sorted.length)];
            int n = 0;
            for (int j = 0; j < sorted.length && n < out.length; j++) {
                int c = node(sorted[j]);
                float dc = distance(sorted[j]);
                boolean keep = true;
                for (int r = 0; r < n && keep; r++) {
                    keep = !(between(vectors[c], vectors[out[r]]) < dc);
                }
                if (keep) {
                    out[n++] = c;
                }
            }
            return n == out.length ? out : Arrays.copyOf(out, n);
        }
    }
}
