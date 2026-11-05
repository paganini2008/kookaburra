package com.github.kookaburra.segment;

import com.github.kookaburra.annotation.VectorDistance;
import com.github.kookaburra.annotation.VectorQuantization;
import com.github.kookaburra.exec.VectorColumn;
import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.io.ByteSource;
import com.github.kookaburra.mapping.TypeDef;
import org.roaringbitmap.RoaringBitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.ToDoubleFunction;

/**
 * Vector column of a segment (storage-design §4.4): the documents that have a vector of the declared dimension,
 * their vectors as one contiguous float32 array read straight from the mapped file, and an HNSW graph when the
 * segment has at least {@code kookaburra.vector-index-min-rows} vectors.
 *
 * <pre>
 * footer: path · dim · distance · count · docsOff · vectorsOff · [format 4: int8(0|1) · min · scale · bytesOff]
 *         · graph(0|1)
 *         [ m · entry · maxLevel · level0Off · (nodes · nodesOff · adjOff) per upper level ]
 * data:   docs int[count] · vectors float[count × dim] · [int8 byte[count × dim]] · level 0: (n · neighbours[2m]) per slot
 *         · level l: slots int[nodes] · (n · neighbours[m]) per node
 * </pre>
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
final class SegmentVectors implements VectorColumn, Hnsw.Graph, Hnsw.Vectors {

    private final String path;
    private final int dim;
    private final VectorDistance distance;
    private final int count;
    private final IntBuffer docs;
    private final FloatBuffer vectors;
    private final boolean graph;
    private int m;
    private int entry = -1;
    private int maxLevel = -1;
    private IntBuffer level0;
    private IntBuffer[] levelNodes;
    private IntBuffer[] levelAdj;
    private final ThreadLocal<Hnsw.Searcher> searchers;
    /** int8 copies of the vectors for the graph search, or null. */
    private final ByteBuffer quantized;
    private float qMin;
    private float qScale;

    // ---------------------------------------------------------------- write

    /** Writes the vector column of {@code path}; returns the number of vectors. */
    static int write(ByteSink out, ByteSink footer, String path, TypeDef type, List<SegmentWriter.Entry> entries, Hnsw.Params p) {
        int dim = type.dim();
        List<Integer> docs = new ArrayList<>();
        List<float[]> vecs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).record().get(path) instanceof float[] f && f.length == dim) {
                docs.add(i);
                vecs.add(f);
            }
        }
        int count = docs.size();
        footer.writeString(path).writeVarInt(dim).writeString(type.distance().name()).writeVarInt(count);
        align(out);
        footer.writeVarLong(out.size());
        docs.forEach(out::writeInt);
        align(out);
        footer.writeVarLong(out.size());
        vecs.forEach(out::writeFloats);
        boolean int8 = type.quantization() == VectorQuantization.INT8 && count > 0;
        footer.writeByte(int8 ? 1 : 0);
        if (int8) {
            float[] range = range(vecs);
            float scale = Math.max((range[1] - range[0]) / 255f, Float.MIN_NORMAL);
            footer.writeInt(Float.floatToRawIntBits(range[0])).writeInt(Float.floatToRawIntBits(scale));
            align(out);
            footer.writeVarLong(out.size());
            byte[] q = new byte[dim];
            for (float[] v : vecs) {
                for (int i = 0; i < dim; i++) {
                    q[i] = (byte) Math.max(0, Math.min(255, Math.round((v[i] - range[0]) / scale)));
                }
                out.writeBytes(q);
            }
        }
        boolean withGraph = count > 0 && count >= p.minRows();
        footer.writeByte(withGraph ? 1 : 0);
        if (withGraph) {
            Hnsw.Builder g = new Hnsw.Builder(vecs.toArray(new float[0][]), dim, type.distance(), p).build();
            int m = g.m();
            footer.writeVarInt(m).writeVarInt(g.entry()).writeVarInt(g.maxLevel());
            int[] nb = new int[2 * m + 1];
            align(out);
            footer.writeVarLong(out.size());
            for (int s = 0; s < count; s++) {
                writeBlock(out, nb, g.neighbors(s, 0, nb), 2 * m);
            }
            for (int l = 1; l <= g.maxLevel(); l++) {
                List<Integer> nodes = new ArrayList<>();
                for (int s = 0; s < count; s++) {
                    if (g.level(s) >= l) {
                        nodes.add(s);
                    }
                }
                footer.writeVarInt(nodes.size()).writeVarLong(out.size());
                nodes.forEach(out::writeInt);
                footer.writeVarLong(out.size());
                for (int s : nodes) {
                    writeBlock(out, nb, g.neighbors(s, l, nb), m);
                }
            }
        }
        return count;
    }

    /**
     * The value range the int8 scale covers: the 0.5 and 99.5 percentiles of all components (of a sample of at most
     * 100,000), so a few outliers do not waste the 256 steps (Lucene's confidence interval).
     */
    static float[] range(List<float[]> vecs) {
        int dim = vecs.get(0).length;
        long total = (long) vecs.size() * dim;
        int n = (int) Math.min(total, 100_000);
        float[] sample = new float[n];
        for (int k = 0; k < n; k++) {
            long at = total <= n ? k : (long) ((double) k * total / n);
            sample[k] = vecs.get((int) (at / dim))[(int) (at % dim)];
        }
        Arrays.sort(sample);
        float lo = sample[(int) Math.floor(0.005 * (n - 1))];
        float hi = sample[(int) Math.ceil(0.995 * (n - 1))];
        return hi > lo ? new float[]{lo, hi} : new float[]{sample[0], sample[n - 1] > sample[0] ? sample[n - 1] : sample[0] + 1};
    }

    private static void writeBlock(ByteSink out, int[] nb, int n, int width) {
        out.writeInt(n);
        for (int i = 0; i < width; i++) {
            out.writeInt(i < n ? nb[i] : -1);
        }
    }

    private static void align(ByteSink out) {
        while ((out.size() & 7) != 0) {
            out.writeByte(0);
        }
    }

    // ---------------------------------------------------------------- read

    SegmentVectors(Segment seg, ByteSource f, int format) {
        path = f.readString();
        dim = f.readVarInt();
        distance = VectorDistance.valueOf(f.readString());
        count = f.readVarInt();
        docs = ints(seg, f.readVarLong(), count);
        long vecOff = f.readVarLong();
        vectors = seg.file().slice(vecOff, count * dim * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        if (format >= 4 && f.readByte() == 1) {
            qMin = Float.intBitsToFloat(f.readInt());
            qScale = Float.intBitsToFloat(f.readInt());
            quantized = seg.file().slice(f.readVarLong(), count * dim);
        } else {
            quantized = null;
        }
        graph = f.readByte() == 1;
        if (graph) {
            m = f.readVarInt();
            entry = f.readVarInt();
            maxLevel = f.readVarInt();
            level0 = ints(seg, f.readVarLong(), count * (2 * m + 1));
            levelNodes = new IntBuffer[maxLevel + 1];
            levelAdj = new IntBuffer[maxLevel + 1];
            for (int l = 1; l <= maxLevel; l++) {
                int nodes = f.readVarInt();
                levelNodes[l] = ints(seg, f.readVarLong(), nodes);
                levelAdj[l] = ints(seg, f.readVarLong(), nodes * (m + 1));
            }
        }
        Hnsw.Vectors forSearch = quantized == null ? this : (slot, scratch) -> {
            int base = slot * dim;
            for (int i = 0; i < dim; i++) {
                scratch[i] = qMin + qScale * (quantized.get(base + i) & 0xff);
            }
            return scratch;
        };
        searchers = ThreadLocal.withInitial(() -> new Hnsw.Searcher(this, forSearch, dim, 2 * Math.max(m, 1)));
    }

    private static IntBuffer ints(Segment seg, long off, int n) {
        return seg.file().slice(off, n * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
    }

    String path() {
        return path;
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public VectorDistance graphDistance() {
        return graph ? distance : null;
    }

    /** Document of a slot. */
    int doc(int slot) {
        return docs.get(slot);
    }

    /** Slot of a document, or -1 when it has no vector here. */
    int slot(int doc) {
        int lo = 0;
        int hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int d = docs.get(mid);
            if (d < doc) {
                lo = mid + 1;
            } else if (d > doc) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    @Override
    public float[] get(int slot, float[] scratch) {
        vectors.get(slot * dim, scratch, 0, dim);
        return scratch;
    }

    /** Copy of the vector of {@code doc}, or null. */
    float[] vector(int doc) {
        int s = slot(doc);
        return s < 0 ? null : get(s, new float[dim]);
    }

    @Override
    public void scan(RoaringBitmap within, ToDoubleFunction<float[]> to, Sink sink) {
        float[] scratch = new float[dim];
        long w = within.getLongCardinality();
        if (w * 16 < count) {                         // few candidates: look each one up
            within.forEach((int d) -> {
                int s = slot(d);
                if (s >= 0) {
                    sink.accept(d, to.applyAsDouble(get(s, scratch)));
                }
            });
            return;
        }
        for (int s = 0; s < count; s++) {             // many: walk the column in order
            int d = docs.get(s);
            if (within.contains(d)) {
                sink.accept(d, to.applyAsDouble(get(s, scratch)));
            }
        }
    }

    @Override
    public boolean search(float[] query, int k, int ef, RoaringBitmap accept, int visitLimit, Sink sink) {
        if (!graph) {
            throw new IllegalStateException("no graph for " + path);
        }
        ToDoubleFunction<float[]> to = distance.to(query);
        IntPredicate ok = accept == null ? null : s -> accept.contains(docs.get(s));
        // int8: the graph search ranks with approximate distances; all ef candidates are re-ranked with the floats
        long[] found = searchers.get().search(to, quantized == null ? k : Math.max(k, ef), ef, ok, visitLimit);
        if (found == null) {
            return false;
        }
        float[] scratch = new float[dim];
        if (quantized == null) {
            for (long key : found) {
                int s = Hnsw.node(key);
                sink.accept(docs.get(s), to.applyAsDouble(get(s, scratch)));
            }
            return true;
        }
        long[] exact = new long[found.length];
        double[] dist = new double[found.length];
        for (int i = 0; i < found.length; i++) {
            int s = Hnsw.node(found[i]);
            dist[i] = to.applyAsDouble(get(s, scratch));
            exact[i] = Hnsw.key(dist[i], s);
        }
        Integer[] order = new Integer[found.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(exact[a], exact[b]));
        for (int i = 0; i < Math.min(k, order.length); i++) {
            sink.accept(docs.get(Hnsw.node(found[order[i]])), dist[order[i]]);
        }
        return true;
    }

    @Override
    public String describe() {
        return (graph ? "HNSW m=" + m + ", " : "") + (quantized != null ? "int8, " : "") + count + " vectors" + (graph ? "" : ", no graph");
    }

    // ---------------------------------------------------------------- graph

    @Override
    public int size() {
        return count;
    }

    @Override
    public int entry() {
        return entry;
    }

    @Override
    public int maxLevel() {
        return maxLevel;
    }

    @Override
    public int neighbors(int node, int level, int[] out) {
        IntBuffer adj;
        int base;
        int width;
        if (level == 0) {
            adj = level0;
            width = 2 * m + 1;
            base = node * width;
        } else {
            IntBuffer nodes = levelNodes[level];
            int lo = 0;
            int hi = nodes.limit() - 1;
            int at = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int v = nodes.get(mid);
                if (v < node) {
                    lo = mid + 1;
                } else if (v > node) {
                    hi = mid - 1;
                } else {
                    at = mid;
                    break;
                }
            }
            if (at < 0) {
                return 0;
            }
            adj = levelAdj[level];
            width = m + 1;
            base = at * width;
        }
        int n = adj.get(base);
        for (int i = 0; i < n; i++) {
            out[i] = adj.get(base + 1 + i);
        }
        return n;
    }
}
