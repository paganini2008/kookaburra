package com.github.kookaburra.segment;

import com.github.kookaburra.Config;
import com.github.kookaburra.exec.ColumnIndex;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.io.ByteSource;
import com.github.kookaburra.io.MappedFile;
import com.github.kookaburra.mapping.Record;
import com.github.kookaburra.mapping.RecordCodec;
import com.github.kookaburra.mapping.Schema;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.buffer.ImmutableRoaringBitmap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

/**
 * Reader of an immutable segment file written by {@link SegmentWriter}. Thread-safe.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Segment implements SegmentView {

    private final int id;
    private final int postingsOrMax;
    private final MappedFile file;
    private final Supplier<Schema> schema;
    private final SegmentDeletes deletes;
    private final int n;
    private final long oidMin;
    private final long oidMax;
    private final long oidsOff;
    private final long versionsOff;
    private final long classIdsOff;
    private final long rowOffsetsOff;
    private final long rowsOff;
    private final long bloomOff;
    private final long bloomBits;
    private final int bloomK;
    private final Map<Integer, ImmutableRoaringBitmap> types = new HashMap<>();
    private final Map<String, SegmentColumn> columns = new LinkedHashMap<>();

    public Segment(int id, MappedFile file, Supplier<Schema> schema, SegmentDeletes deletes) {
        this(id, file, schema, deletes, Config.defaults().postingsOrMax());
    }

    /**
     * @param postingsOrMax range lookups OR at most this many postings, else they scan ordinals
     */
    public Segment(int id, MappedFile file, Supplier<Schema> schema, SegmentDeletes deletes, int postingsOrMax) {
        this.postingsOrMax = postingsOrMax;
        this.id = id;
        this.file = file;
        this.schema = schema;
        this.deletes = deletes;
        long size = file.size();
        if (size < 36 || file.getLong(0) != SegmentWriter.MAGIC || file.getLong(size - 8) != SegmentWriter.MAGIC) {
            throw new IllegalStateException(file.name() + " is not a segment file");
        }
        long footerOff = file.getLong(size - 20);
        int footerLen = (int) (size - 20 - footerOff);
        CRC32C crc = new CRC32C();
        crc.update(file.slice(footerOff, footerLen));
        if ((int) crc.getValue() != file.getInt(size - 12)) {
            throw new IllegalStateException(file.name() + ": footer checksum mismatch");
        }
        ByteSource f = new ByteSource(file.slice(footerOff, footerLen), 0, footerLen);
        n = f.readVarInt();
        oidMin = f.readLong();
        oidMax = f.readLong();
        oidsOff = f.readVarLong();
        versionsOff = f.readVarLong();
        classIdsOff = f.readVarLong();
        rowOffsetsOff = f.readVarLong();
        rowsOff = f.readVarLong();
        bloomOff = f.readVarLong();
        bloomBits = f.readVarLong();
        bloomK = f.readVarInt();
        int nt = f.readVarInt();
        for (int i = 0; i < nt; i++) {
            int classId = f.readVarInt();
            types.put(classId, bitmap(f.readVarLong(), f.readVarInt()));
        }
        int nc = f.readVarInt();
        for (int i = 0; i < nc; i++) {
            SegmentColumn c = SegmentColumn.read(this, f);
            columns.put(c.path(), c);
        }
    }

    MappedFile file() {
        return file;
    }

    int postingsOrMax() {
        return postingsOrMax;
    }

    ImmutableRoaringBitmap bitmap(long offset, int length) {
        return new ImmutableRoaringBitmap(file.slice(offset, length));
    }

    public int id() {
        return id;
    }

    public SegmentDeletes deletes() {
        return deletes;
    }

    public long oidMin() {
        return oidMin;
    }

    public long oidMax() {
        return oidMax;
    }

    public long fileSize() {
        return file.size();
    }

    @Override
    public String label() {
        return "seg#" + id;
    }

    @Override
    public int size() {
        return n;
    }

    @Override
    public RoaringBitmap live(long seq) {
        RoaringBitmap all = new RoaringBitmap();
        if (n > 0) {
            all.add(0L, n);
        }
        return RoaringBitmap.andNot(all, deletes.deleted(seq));
    }

    @Override
    public long oid(int doc) {
        return file.getLong(oidsOff + 8L * doc);
    }

    @Override
    public long version(int doc) {
        return file.getLong(versionsOff + 8L * doc);
    }

    @Override
    public int classId(int doc) {
        return file.getInt(classIdsOff + 4L * doc);
    }

    /** Encoded row bytes. */
    public byte[] row(int doc) {
        long start = file.getLong(rowOffsetsOff + 8L * doc);
        long end = file.getLong(rowOffsetsOff + 8L * (doc + 1));
        return file.bytes(rowsOff + start, (int) (end - start));
    }

    @Override
    public Record record(int doc) {
        return RecordCodec.decode(schema.get(), rowSource(doc));
    }

    /** Column value when the path is a column, else only that field is decoded from the row (others are skipped). */
    @Override
    public Object value(int doc, String path) {
        SegmentColumn c = columns.get(path);
        return c != null ? c.value(doc) : RecordCodec.readPath(schema.get(), rowSource(doc), path);
    }

    private ByteSource rowSource(int doc) {
        long start = file.getLong(rowOffsetsOff + 8L * doc);
        long end = file.getLong(rowOffsetsOff + 8L * (doc + 1));
        int len = (int) (end - start);
        return file.source(rowsOff + start, len);
    }

    @Override
    public ColumnIndex index(String path) {
        return columns.get(path);
    }

    @Override
    public RoaringBitmap classes(Set<Integer> classIds, RoaringBitmap within) {
        RoaringBitmap out = new RoaringBitmap();
        for (int c : classIds) {
            ImmutableRoaringBitmap b = types.get(c);
            if (b != null) {
                out.or(b.toRoaringBitmap());
            }
        }
        out.and(within);
        return out;
    }

    @Override
    public int find(long oid, long seq) {
        int doc = docOf(oid);
        if (doc < 0) {
            return -1;
        }
        return deletes.deleted(seq).contains(doc) ? DELETED : doc;
    }

    /** Document holding {@code oid}, ignoring deletes; -1 if absent. */
    public int docOf(long oid) {
        if (n == 0 || oid < oidMin || oid > oidMax || !Bloom.mightContain(file, bloomOff, bloomBits, bloomK, oid)) {
            return -1;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = oid(mid);
            if (v < oid) {
                lo = mid + 1;
            } else if (v > oid) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /** Columns by path (for inspection and tests). */
    public Map<String, SegmentColumn> columns() {
        return columns;
    }

    public void close() {
        file.close();
    }
}
