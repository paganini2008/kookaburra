package com.github.kookaburra.segment;

import com.github.kookaburra.exec.TextIndex;
import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.io.ByteSource;
import com.github.kookaburra.text.Analyzer;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Full-text index of a segment (storage-design §4.6): an inverted index of one {@code @FullText} path on the same
 * building blocks as the columns, a sorted word dictionary and one Roaring posting per word, plus the frequency of
 * the word in each of its documents and the word count of every document (BM25).
 *
 * <pre>
 * footer: path · docCount · totalWords · words · dictOff · bytesOff · postOff · postDataOff · startOff · freqOff · lengthsOff
 * data:   dict int[words + 1] (UTF-8 offsets) · UTF-8 bytes · post long[words + 1] · Roaring postings
 *         · start int[words + 1] (first frequency of each word) · freq int[total postings] (in posting order) · lengths int[N]
 * </pre>
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
final class SegmentText implements TextIndex {

    private final Segment seg;
    private final String path;
    private final long docCount;
    private final long totalWords;
    private final int count;
    private final IntBuffer dict;
    private final long bytesOff;
    private final long postOff;
    private final long postDataOff;
    private final IntBuffer start;
    private final IntBuffer freq;
    private final IntBuffer lengths;

    // ---------------------------------------------------------------- write

    /** Writes the full-text index of {@code path}. */
    static void write(ByteSink out, ByteSink footer, String path, Analyzer analyzer, List<SegmentWriter.Entry> entries) {
        int n = entries.size();
        TreeMap<String, List<int[]>> postings = new TreeMap<>();      // word → (doc, freq) ascending by doc
        int[] lengths = new int[n];
        long docCount = 0;
        long total = 0;
        for (int d = 0; d < n; d++) {
            Object v = entries.get(d).record().get(path);
            if (!(v instanceof String s)) {
                continue;
            }
            Map<String, Integer> tf = analyzer.frequencies(s);
            int len = 0;
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                postings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new int[]{d, e.getValue()});
                len += e.getValue();
            }
            lengths[d] = len;
            if (len > 0) {
                docCount++;
                total += len;
            }
        }
        List<String> words = new ArrayList<>(postings.keySet());
        footer.writeString(path).writeVarLong(docCount).writeVarLong(total).writeVarInt(words.size());
        align(out);
        footer.writeVarLong(out.size());
        List<byte[]> utf = new ArrayList<>(words.size());
        int off = 0;
        for (String w : words) {
            byte[] b = w.getBytes(StandardCharsets.UTF_8);
            utf.add(b);
            out.writeInt(off);
            off += b.length;
        }
        out.writeInt(off);
        footer.writeVarLong(out.size());
        utf.forEach(out::writeBytes);
        align(out);
        footer.writeVarLong(out.size());
        ByteSink data = new ByteSink();
        List<Integer> freqs = new ArrayList<>();
        for (String w : words) {
            out.writeLong(data.size());
            RoaringBitmap bm = new RoaringBitmap();
            for (int[] p : postings.get(w)) {
                bm.add(p[0]);
                freqs.add(p[1]);
            }
            bm.runOptimize();
            data.writeBytes(SegmentWriter.serialize(bm));
        }
        out.writeLong(data.size());
        footer.writeVarLong(out.size());
        out.writeBytes(data.array(), 0, data.size());
        align(out);
        footer.writeVarLong(out.size());
        int start = 0;
        for (String w : words) {
            out.writeInt(start);
            start += postings.get(w).size();
        }
        out.writeInt(start);
        align(out);
        footer.writeVarLong(out.size());
        freqs.forEach(out::writeInt);
        align(out);
        footer.writeVarLong(out.size());
        for (int l : lengths) {
            out.writeInt(l);
        }
    }

    private static void align(ByteSink out) {
        while ((out.size() & 7) != 0) {
            out.writeByte(0);
        }
    }

    // ---------------------------------------------------------------- read

    SegmentText(Segment seg, ByteSource f) {
        this.seg = seg;
        path = f.readString();
        docCount = f.readVarLong();
        totalWords = f.readVarLong();
        count = f.readVarInt();
        dict = ints(seg, f.readVarLong(), count + 1);
        bytesOff = f.readVarLong();
        postOff = f.readVarLong();
        postDataOff = f.readVarLong();
        start = ints(seg, f.readVarLong(), count + 1);
        freq = ints(seg, f.readVarLong(), start.get(count));
        long lengthsOff = f.readVarLong();
        lengths = ints(seg, lengthsOff, seg.size());
    }

    private static IntBuffer ints(Segment seg, long off, int n) {
        ByteBuffer b = seg.file().slice(off, n * 4);
        return b.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
    }

    String path() {
        return path;
    }

    private String word(int i) {
        int a = dict.get(i);
        int b = dict.get(i + 1);
        return new String(seg.file().bytes(bytesOff + a, b - a), StandardCharsets.UTF_8);
    }

    /** Dictionary position of {@code w}, or {@code -(insertion point) - 1}. */
    private int find(String w) {
        int lo = 0;
        int hi = count - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = word(mid).compareTo(w);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -lo - 1;
    }

    private ImmutableRoaringBitmap posting(int i) {
        long a = seg.file().getLong(postOff + 8L * i);
        long b = seg.file().getLong(postOff + 8L * (i + 1));
        return seg.bitmap(postDataOff + a, (int) (b - a));
    }

    @Override
    public RoaringBitmap docs(String w) {
        int i = find(w);
        return i < 0 ? new RoaringBitmap() : posting(i).toRoaringBitmap();
    }

    @Override
    public List<String> words(String prefix, int max) {
        List<String> out = new ArrayList<>();
        int i = find(prefix);
        for (int k = i < 0 ? -i - 1 : i; k < count && out.size() < max; k++) {
            String w = word(k);
            if (!w.startsWith(prefix)) {
                break;
            }
            out.add(w);
        }
        return out;
    }

    @Override
    public int frequency(String w, int doc) {
        int i = find(w);
        if (i < 0) {
            return 0;
        }
        ImmutableRoaringBitmap p = posting(i);
        if (!p.contains(doc)) {
            return 0;
        }
        return freq.get(start.get(i) + (int) p.rank(doc) - 1);
    }

    @Override
    public int length(int doc) {
        return lengths.get(doc);
    }

    @Override
    public long docCount() {
        return docCount;
    }

    @Override
    public long totalWords() {
        return totalWords;
    }

    @Override
    public long docFrequency(String w) {
        int i = find(w);
        return i < 0 ? 0 : posting(i).getCardinality();
    }

    @Override
    public String describe() {
        return "full-text index, " + count + " words";
    }
}
