package com.github.kookaburra.segment;

import com.github.kookaburra.exec.ColumnIndex;
import com.github.kookaburra.io.ByteSource;
import com.github.kookaburra.io.MappedFile;
import com.github.kookaburra.mapping.ValueType;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;
import org.roaringbitmap.buffer.MutableRoaringBitmap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * One column of a segment: sorted dictionary, ordinals (bit-packed, 0 = null) or multi-value lists,
 * optional postings and a null bitmap (design §5.1). Answers equality, range and prefix lookups.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class SegmentColumn implements ColumnIndex {

    private final Segment seg;
    private final MappedFile f;
    private final String path;
    private final ValueType type;
    private final boolean multi;
    private final List<String> constants;
    private final int card;
    private final Keys.Kind kind;
    private final long dictOff;
    private final long dictDataOff;
    private final long ordOff;
    private final int ordBits;
    private final long multiOffsetsOff;
    private final long multiDataOff;
    private final boolean postings;
    private final long postOffsetsOff;
    private final long postDataOff;
    private final boolean byValue;
    private final long byValueStartOff;
    private final long byValueDocsOff;
    private final ImmutableRoaringBitmap nulls;

    private SegmentColumn(Segment seg, ByteSource in) {
        this.seg = seg;
        this.f = seg.file();
        this.path = in.readString();
        this.type = ValueType.valueOf(in.readString());
        this.multi = in.readByte() != 0;
        int nc = in.readVarInt();
        List<String> cs = new ArrayList<>(nc);
        for (int i = 0; i < nc; i++) {
            cs.add(in.readString());
        }
        this.constants = List.copyOf(cs);
        this.card = in.readVarInt();
        this.kind = Keys.kind(type);
        this.dictOff = in.readVarLong();
        this.dictDataOff = kind == Keys.Kind.STRING ? in.readVarLong() : -1;
        if (!multi) {
            this.ordOff = in.readVarLong();
            this.ordBits = in.readVarInt();
            this.multiOffsetsOff = -1;
            this.multiDataOff = -1;
        } else {
            this.ordOff = -1;
            this.ordBits = 0;
            this.multiOffsetsOff = in.readVarLong();
            this.multiDataOff = in.readVarLong();
        }
        this.postings = in.readByte() != 0;
        this.postOffsetsOff = postings ? in.readVarLong() : -1;
        this.postDataOff = postings ? in.readVarLong() : -1;
        this.byValue = in.readByte() != 0;
        this.byValueStartOff = byValue ? in.readVarLong() : -1;
        this.byValueDocsOff = byValue ? in.readVarLong() : -1;
        this.nulls = seg.bitmap(in.readVarLong(), in.readVarInt());
    }

    static SegmentColumn read(Segment seg, ByteSource in) {
        return new SegmentColumn(seg, in);
    }

    public String path() {
        return path;
    }

    public int cardinality() {
        return card;
    }

    public boolean multi() {
        return multi;
    }

    @Override
    public boolean hasPostings() {
        return postings;
    }

    @Override
    public String describe() {
        return (postings ? "postings" : byValue ? "sorted docs" : "ordinal scan") + " card=" + card;
    }

    // ---------------------------------------------------------------- dictionary

    @Override
    public int ordinals() {
        return multi ? -1 : card;
    }

    @Override
    public int ordinal(int doc) {
        return ord(doc);
    }

    /** Canonical value of a dictionary entry. */
    @Override
    public Object key(int ord) {
        return switch (kind) {
            case LONG -> Keys.fromLong(type, f.getLong(dictOff + 8L * ord), constants);
            case FIXED128 -> Keys.fromFixed(type, f.getLong(dictOff + 16L * ord), f.getLong(dictOff + 16L * ord + 8));
            case STRING -> string(ord);
        };
    }

    private String string(int ord) {
        int start = f.getInt(dictOff + 4L * ord);
        int end = f.getInt(dictOff + 4L * (ord + 1));
        return new String(f.bytes(dictDataOff + start, end - start), StandardCharsets.UTF_8);
    }

    /** Compares dictionary entry {@code ord} with a canonical value. */
    private int compare(int ord, Object v) {
        return switch (kind) {
            case LONG -> Long.compare(f.getLong(dictOff + 8L * ord), Keys.toLong(type, v, constants));
            case FIXED128 -> {
                long[] k = Keys.toFixed(v);
                int c = Long.compare(f.getLong(dictOff + 16L * ord), k[0]);
                yield c != 0 ? c : Long.compare(f.getLong(dictOff + 16L * ord + 8), k[1]);
            }
            case STRING -> string(ord).compareTo((String) v);
        };
    }

    /** First ordinal whose key is {@code >= v} ({@code strict}: {@code > v}). */
    private int lowerBound(Object v, boolean strict) {
        int lo = 0;
        int hi = card;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int c = compare(mid, v);
            if (c < 0 || (strict && c == 0)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Ordinal of a canonical value, or -1. */
    public int find(Object v) {
        if (type == ValueType.ENUM && !constants.contains(v)) {
            return -1;
        }
        int i = lowerBound(v, false);
        return i < card && compare(i, v) == 0 ? i : -1;
    }

    // ---------------------------------------------------------------- per-document access

    /** Ordinal + 1 of a document's value, 0 for null (single-valued columns). */
    int ord(int doc) {
        return PackedInts.get(f, ordOff, ordBits, doc);
    }

    private int[] ords(int doc) {
        int start = f.getInt(multiOffsetsOff + 4L * doc);
        int end = f.getInt(multiOffsetsOff + 4L * (doc + 1));
        int[] out = new int[end - start];
        for (int i = 0; i < out.length; i++) {
            out[i] = f.getInt(multiDataOff + 4L * (start + i));
        }
        return out;
    }

    /** Canonical value of a document (list for multi-valued columns). */
    public Object value(int doc) {
        if (multi) {
            if (nulls.contains(doc)) {
                return null;
            }
            List<Object> out = new ArrayList<>();
            for (int o : ords(doc)) {
                out.add(key(o));
            }
            return out;
        }
        int o = ord(doc);
        return o == 0 ? null : key(o - 1);
    }

    // ---------------------------------------------------------------- ColumnIndex

    @Override
    public RoaringBitmap eq(Object k, RoaringBitmap within) {
        int o = find(k);
        if (o < 0) {
            return new RoaringBitmap();
        }
        if (postings) {
            return posting(o);
        }
        return ordinals(o, o + 1, within);
    }

    @Override
    public boolean forEachInOrder(boolean descending, IntPredicate visitor) {
        if (multi || !(postings || byValue)) {
            return false;
        }
        for (int i = 0; i < card; i++) {
            int o = descending ? card - 1 - i : i;
            if (postings) {
                var it = postingView(o).getIntIterator();
                while (it.hasNext()) {
                    if (!visitor.test(it.next())) {
                        return true;
                    }
                }
            } else {
                long end = byValueStart(o + 1);
                for (long j = byValueStart(o); j < end; j++) {
                    if (!visitor.test(f.getInt(byValueDocsOff + 4L * j))) {
                        return true;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public long[] countByOrdinal(RoaringBitmap within) {
        if (multi || !postings) {
            return null;
        }
        MutableRoaringBitmap w = within.toMutableRoaringBitmap();
        long[] out = new long[card + 1];
        out[0] = ImmutableRoaringBitmap.andCardinality(nulls, w);
        for (int o = 0; o < card; o++) {
            out[o + 1] = ImmutableRoaringBitmap.andCardinality(postingView(o), w);
        }
        return out;
    }

    private int byValueStart(int o) {
        return f.getInt(byValueStartOff + 4L * o);
    }

    @Override
    public RoaringBitmap range(Object lo, boolean loInclusive, Object hi, boolean hiInclusive, RoaringBitmap within) {
        if (multi) {
            return null;
        }
        int from = lo == null ? 0 : lowerBound(lo, !loInclusive);
        int to = hi == null ? card : lowerBound(hi, hiInclusive);
        return ordinals(from, to, within);
    }

    @Override
    public RoaringBitmap prefix(String p, RoaringBitmap within) {
        if (kind != Keys.Kind.STRING || multi) {
            return null;
        }
        int from = lowerBound(p, false);
        int lo = from;
        int hi = card;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (string(mid).startsWith(p)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return ordinals(from, lo, within);
    }

    @Override
    public RoaringBitmap nulls() {
        return nulls.toRoaringBitmap();
    }

    private RoaringBitmap ordinals(int from, int to, RoaringBitmap within) {
        if (from >= to) {
            return new RoaringBitmap();
        }
        if (postings && to - from <= seg.postingsOrMax()) {
            RoaringBitmap r = new RoaringBitmap();
            for (int o = from; o < to; o++) {
                r.or(posting(o));
            }
            return r;
        }
        if (byValue) {
            int lo = byValueStart(from);
            int hi = byValueStart(to);
            // the value-sorted slice is cheaper than checking every candidate's ordinal
            if ((long) (hi - lo) * 4 <= within.getLongCardinality()) {
                int[] docs = new int[hi - lo];
                for (int j = 0; j < docs.length; j++) {
                    docs[j] = f.getInt(byValueDocsOff + 4L * (lo + j));
                }
                return RoaringBitmap.bitmapOf(docs);
            }
        }
        return scan(within, from, to);
    }

    /** Documents of {@code within} whose ordinal (any, for multi) is in {@code [from, to)}. */
    private RoaringBitmap scan(RoaringBitmap within, int from, int to) {
        RoaringBitmap r = new RoaringBitmap();
        within.forEach((int d) -> {
            if (multi) {
                for (int o : ords(d)) {
                    if (o >= from && o < to) {
                        r.add(d);
                        return;
                    }
                }
            } else {
                int o = ord(d) - 1;
                if (o >= from && o < to) {
                    r.add(d);
                }
            }
        });
        return r;
    }

    private RoaringBitmap posting(int o) {
        return postingView(o).toRoaringBitmap();
    }

    private ImmutableRoaringBitmap postingView(int o) {
        long start = f.getLong(postOffsetsOff + 8L * o);
        long end = f.getLong(postOffsetsOff + 8L * (o + 1));
        return seg.bitmap(postDataOff + start, (int) (end - start));
    }
}
