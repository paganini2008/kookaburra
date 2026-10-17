package com.github.kookaburra.segment;

import com.github.kookaburra.Config;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.mapping.Canonical;
import com.github.kookaburra.mapping.ColumnDef;
import com.github.kookaburra.mapping.Record;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import org.roaringbitmap.RoaringBitmap;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32C;

/**
 * Builds an immutable segment (design §5.1) from rows sorted by OID.
 *
 * <pre>
 * header(16) · oids · versions · classIds · rowOffsets · rows · bloom · $type postings · columns · footer · trailer(20)
 * trailer = footerOffset(8) · crc32c(footer)(4) · magic(8)
 * </pre>
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class SegmentWriter {

    static final long MAGIC = 0x3130474553424B4BL;   // "KKBSEG01" little-endian
    static final int FORMAT = 1;

    /** One row to write. */
    public record Entry(long oid, long version, Record record, byte[] row) {
    }

    private SegmentWriter() {
    }

    /**
     * @param entries rows with strictly increasing OIDs
     * @param columns indexed columns of the collection
     */
    public static byte[] write(List<Entry> entries, Collection<ColumnDef> columns, double bloomFpp) {
        return write(entries, columns, bloomFpp, Config.defaults().autoPostingsRatio());
    }

    /**
     * @param autoPostingsRatio AUTO columns get postings when cardinality &lt;= rows / ratio
     */
    public static byte[] write(List<Entry> entries, Collection<ColumnDef> columns, double bloomFpp, int autoPostingsRatio) {
        int n = entries.size();
        ByteSink out = new ByteSink(Math.max(1024, n * 64));
        out.writeLong(MAGIC).writeInt(FORMAT).writeInt(0);
        ByteSink footer = new ByteSink();
        footer.writeVarInt(n);

        long[] oids = new long[n];
        for (int i = 0; i < n; i++) {
            oids[i] = entries.get(i).oid();
            if (i > 0 && oids[i] <= oids[i - 1]) {
                throw new IllegalArgumentException("OIDs must be strictly increasing");
            }
        }
        footer.writeLong(n == 0 ? 0 : oids[0]).writeLong(n == 0 ? 0 : oids[n - 1]);

        footer.writeVarLong(out.size());
        for (long o : oids) {
            out.writeLong(o);
        }
        footer.writeVarLong(out.size());
        for (Entry e : entries) {
            out.writeLong(e.version());
        }
        footer.writeVarLong(out.size());
        for (Entry e : entries) {
            out.writeInt(e.record().classId());
        }
        align(out);

        footer.writeVarLong(out.size());           // row offsets
        long rel = 0;
        for (Entry e : entries) {
            out.writeLong(rel);
            rel += e.row().length;
        }
        out.writeLong(rel);
        footer.writeVarLong(out.size());           // row data
        for (Entry e : entries) {
            out.writeBytes(e.row());
        }
        align(out);

        long bloomBits = Bloom.bits(n, bloomFpp);
        int k = Bloom.hashes(n, bloomBits);
        footer.writeVarLong(out.size()).writeVarLong(bloomBits).writeVarInt(k);
        for (long w : Bloom.build(oids, bloomBits, k)) {
            out.writeLong(w);
        }

        Map<Integer, RoaringBitmap> types = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            types.computeIfAbsent(entries.get(i).record().classId(), c -> new RoaringBitmap()).add(i);
        }
        footer.writeVarInt(types.size());
        for (Map.Entry<Integer, RoaringBitmap> t : types.entrySet()) {
            long[] at = writeBitmap(out, t.getValue());
            footer.writeVarInt(t.getKey()).writeVarLong(at[0]).writeVarInt((int) at[1]);
        }

        footer.writeVarInt(columns.size());
        for (ColumnDef c : columns) {
            writeColumn(out, footer, c, entries, autoPostingsRatio);
        }

        long footerOffset = out.size();
        byte[] f = footer.toByteArray();
        out.writeBytes(f);
        CRC32C crc = new CRC32C();
        crc.update(f);
        out.writeLong(footerOffset).writeInt((int) crc.getValue()).writeLong(MAGIC);
        return out.toByteArray();
    }

    private static void writeColumn(ByteSink out, ByteSink footer, ColumnDef c, List<Entry> entries, int autoPostingsRatio) {
        int n = entries.size();
        TypeDef type = c.scalarType();
        boolean multi = c.multi();
        Comparator<Object> cmp = (a, b) -> Canonical.compare(type, a, b);
        TreeSet<Object> distinct = new TreeSet<>(cmp);
        Object[] values = new Object[n];
        RoaringBitmap nulls = new RoaringBitmap();
        for (int i = 0; i < n; i++) {
            Object v = entries.get(i).record().get(c.path());
            values[i] = v;
            if (v == null) {
                nulls.add(i);
            } else if (multi) {
                distinct.addAll((List<?>) v);
            } else {
                distinct.add(v);
            }
        }
        List<Object> dict = new ArrayList<>(distinct);
        TreeMap<Object, Integer> ordinal = new TreeMap<>(cmp);
        for (int i = 0; i < dict.size(); i++) {
            ordinal.put(dict.get(i), i);
        }
        int card = dict.size();

        footer.writeString(c.path()).writeString(type.kind().name()).writeByte(multi ? 1 : 0);
        footer.writeVarInt(type.constants().size());
        for (String s : type.constants()) {
            footer.writeString(s);
        }
        footer.writeVarInt(card);

        // dictionary
        Keys.Kind kind = Keys.kind(type.kind());
        align(out);
        footer.writeVarLong(out.size());
        switch (kind) {
            case LONG -> dict.forEach(v -> out.writeLong(Keys.toLong(type.kind(), v, type.constants())));
            case FIXED128 -> dict.forEach(v -> {
                long[] f = Keys.toFixed(v);
                out.writeLong(f[0]).writeLong(f[1]);
            });
            case STRING, DECIMAL -> {
                int off = 0;
                List<byte[]> utf = new ArrayList<>(card);
                for (Object v : dict) {
                    byte[] b = kind == Keys.Kind.STRING ? ((String) v).getBytes(StandardCharsets.UTF_8) : Keys.decimalBytes((BigDecimal) v);
                    utf.add(b);
                    out.writeInt(off);
                    off += b.length;
                }
                out.writeInt(off);
                footer.writeVarLong(out.size());
                utf.forEach(out::writeBytes);
                align(out);
            }
        }

        // ordinals (postings are only built when they will be written)
        boolean withPostings = c.kind() == IndexKind.BITMAP || (c.kind() == IndexKind.AUTO && card <= Math.max(1, n / autoPostingsRatio));
        List<RoaringBitmap> postings = new ArrayList<>(withPostings ? card : 0);
        for (int i = 0; withPostings && i < card; i++) {
            postings.add(new RoaringBitmap());
        }
        int[] ord = multi ? null : new int[n];
        align(out);
        if (!multi) {
            for (int i = 0; i < n; i++) {
                if (values[i] != null) {
                    int o = ordinal.get(values[i]);
                    ord[i] = o + 1;
                    if (withPostings) {
                        postings.get(o).add(i);
                    }
                }
            }
            int bits = PackedInts.bitsFor(card);
            footer.writeVarLong(out.size()).writeVarInt(bits);
            PackedInts.write(out, ord, bits);
        } else {
            footer.writeVarLong(out.size());
            List<int[]> lists = new ArrayList<>(n);
            int total = 0;
            for (int i = 0; i < n; i++) {
                TreeSet<Integer> ords = new TreeSet<>();
                if (values[i] != null) {
                    for (Object e : (List<?>) values[i]) {
                        ords.add(ordinal.get(e));
                    }
                }
                int[] arr = ords.stream().mapToInt(Integer::intValue).toArray();
                for (int o = 0; withPostings && o < arr.length; o++) {
                    postings.get(arr[o]).add(i);
                }
                lists.add(arr);
                out.writeInt(total);
                total += arr.length;
            }
            out.writeInt(total);
            footer.writeVarLong(out.size());
            for (int[] arr : lists) {
                for (int o : arr) {
                    out.writeInt(o);
                }
            }
        }

        // postings
        footer.writeByte(withPostings ? 1 : 0);
        if (withPostings) {
            align(out);
            long[] offsets = new long[card + 1];
            ByteSink data = new ByteSink();
            for (int i = 0; i < card; i++) {
                offsets[i] = data.size();
                RoaringBitmap bm = postings.get(i);
                bm.runOptimize();
                byte[] b = serialize(bm);
                data.writeBytes(b);
            }
            offsets[card] = data.size();
            footer.writeVarLong(out.size());
            for (long o : offsets) {
                out.writeLong(o);
            }
            footer.writeVarLong(out.size());
            out.writeBytes(data.array(), 0, data.size());
        }
        // documents sorted by value (single-valued columns without postings): offsets[card+1] · docs[non-null]
        boolean byValue = !withPostings && !multi;
        footer.writeByte(byValue ? 1 : 0);
        if (byValue) {
            int[] start = new int[card + 1];
            for (int i = 0; i < n; i++) {
                if (ord[i] != 0) {
                    start[ord[i]]++;
                }
            }
            for (int o = 0; o < card; o++) {
                start[o + 1] += start[o];
            }
            int[] docs = new int[start[card]];
            int[] next = Arrays.copyOf(start, card);
            for (int i = 0; i < n; i++) {
                if (ord[i] != 0) {
                    docs[next[ord[i] - 1]++] = i;
                }
            }
            align(out);
            footer.writeVarLong(out.size());
            for (int o : start) {
                out.writeInt(o);
            }
            footer.writeVarLong(out.size());
            for (int d : docs) {
                out.writeInt(d);
            }
        }
        long[] at = writeBitmap(out, nulls);
        footer.writeVarLong(at[0]).writeVarInt((int) at[1]);
    }

    private static long[] writeBitmap(ByteSink out, RoaringBitmap bm) {
        align(out);
        bm.runOptimize();
        byte[] b = serialize(bm);
        long at = out.size();
        out.writeBytes(b);
        return new long[]{at, b.length};
    }

    static byte[] serialize(RoaringBitmap bm) {
        ByteBuffer buf = ByteBuffer.allocate(bm.serializedSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
        bm.serialize(buf);
        return buf.array();
    }

    private static void align(ByteSink out) {
        while ((out.size() & 7) != 0) {
            out.writeByte(0);
        }
    }

    /** True for value types that have a dictionary column. */
    static boolean columnar(ValueType t) {
        return t.isScalar();
    }
}
