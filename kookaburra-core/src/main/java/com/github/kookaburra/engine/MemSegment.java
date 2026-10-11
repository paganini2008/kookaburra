package com.github.kookaburra.engine;

import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.exec.ColumnIndex;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.mapping.ColumnDef;
import com.github.kookaburra.mapping.Record;
import org.roaringbitmap.RoaringBitmap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory segment (memtable, design §6.2): append-only rows with sequence numbers, a version chain per
 * OID, and postings for BITMAP / AUTO columns. One writer (the sequencer); readers are lock-free and see
 * rows with {@code seq <= snapshot} that were not deleted at or before the snapshot.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class MemSegment implements SegmentView {

    private final int id;
    private final Chunks.Longs oids = new Chunks.Longs(0);
    private final Chunks.Longs seqs = new Chunks.Longs(0);
    private final Chunks.Longs versions = new Chunks.Longs(0);
    private final Chunks.Ints classIds = new Chunks.Ints();
    private final Chunks.Ints prevRow = new Chunks.Ints();
    private final Chunks.Refs<Record> records = new Chunks.Refs<>();
    private final Chunks.Refs<byte[]> rows = new Chunks.Refs<>();
    private final Chunks.Longs deletedSeq = new Chunks.Longs(Long.MAX_VALUE);
    private final Chunks.Ints deletedRows = new Chunks.Ints();
    private final Chunks.Longs deletedRowSeq = new Chunks.Longs(0);
    private final Map<Long, Integer> latest = new ConcurrentHashMap<>();
    private final Map<String, MemIndex> indexes = new ConcurrentHashMap<>();
    private volatile int size;
    private volatile int deletedCount;
    private long bytes;
    private long maxSeq;
    /** Set by the sequencer when no more rows may be appended. */
    boolean frozen;

    MemSegment(int id, Map<String, ColumnDef> columns) {
        this.id = id;
        for (ColumnDef c : columns.values()) {
            if (c.kind() == IndexKind.BITMAP || c.kind() == IndexKind.AUTO) {
                indexes.put(c.path(), new MemIndex(c.multi()));
            }
        }
    }

    int id() {
        return id;
    }

    long bytes() {
        return bytes;
    }

    /** Sequence number of the first row (size must be &gt; 0). */
    long firstSeq() {
        return seqs.get(0);
    }

    /** Sequence number of the last appended row. */
    long maxSeq() {
        return maxSeq;
    }

    // ---------------------------------------------------------------- writer side (sequencer thread)

    /** Appends a row; {@code prev} is the previous version's row in this segment or -1. */
    int append(long oid, long version, long seq, Record record, byte[] row) {
        int r = size;
        oids.set(r, oid);
        seqs.set(r, seq);
        versions.set(r, version);
        classIds.set(r, record.classId());
        Integer prev = latest.get(oid);
        prevRow.set(r, prev == null ? -1 : prev);
        records.set(r, record);
        rows.set(r, row);
        deletedSeq.set(r, Long.MAX_VALUE);
        for (Map.Entry<String, MemIndex> e : indexes.entrySet()) {
            e.getValue().add(r, record.get(e.getKey()));
        }
        latest.put(oid, r);
        bytes += row.length + 96;
        maxSeq = seq;
        size = r + 1;
        return r;
    }

    /** Marks a row deleted (superseded or removed) as of {@code seq}. */
    void markDeleted(int row, long seq) {
        deletedSeq.set(row, seq);
        int n = deletedCount;
        deletedRows.set(n, row);
        deletedRowSeq.set(n, seq);
        deletedCount = n + 1;
    }

    /** Latest row of an OID in this segment (ignoring snapshots), or -1. */
    int latestRow(long oid) {
        Integer r = latest.get(oid);
        return r == null ? -1 : r;
    }

    /** Number of rows deleted so far (in sequence order). */
    int deletedCount() {
        return deletedCount;
    }

    int deletedRow(int i) {
        return deletedRows.get(i);
    }

    long deletedRowSeq(int i) {
        return deletedRowSeq.get(i);
    }

    boolean isDeleted(int row) {
        return deletedSeq.get(row) != Long.MAX_VALUE;
    }

    byte[] row(int r) {
        return rows.get(r);
    }

    // ---------------------------------------------------------------- reader side

    @Override
    public String label() {
        return "mem#" + id;
    }

    @Override
    public int size() {
        return size;
    }

    /** Number of rows with {@code seq <= snapshot} (rows are appended in sequence order). */
    int visibleCount(long snapshot) {
        int lo = 0;
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (seqs.get(mid) <= snapshot) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    @Override
    public RoaringBitmap live(long seq) {
        int n = visibleCount(seq);
        RoaringBitmap bm = new RoaringBitmap();
        if (n == 0) {
            return bm;
        }
        bm.add(0L, n);
        int dc = deletedCount;
        for (int i = 0; i < dc; i++) {
            if (deletedRowSeq.get(i) <= seq) {
                bm.remove(deletedRows.get(i));
            } else {
                break;
            }
        }
        return bm;
    }

    @Override
    public long oid(int doc) {
        return oids.get(doc);
    }

    @Override
    public long version(int doc) {
        return versions.get(doc);
    }

    @Override
    public int classId(int doc) {
        return classIds.get(doc);
    }

    @Override
    public Record record(int doc) {
        return records.get(doc);
    }

    @Override
    public ColumnIndex index(String path) {
        return indexes.get(path);
    }

    @Override
    public int find(long oid, long seq) {
        Integer r = latest.get(oid);
        int row = r == null ? -1 : r;
        while (row >= 0 && seqs.get(row) > seq) {
            row = prevRow.get(row);
        }
        if (row < 0) {
            return -1;
        }
        return deletedSeq.get(row) <= seq ? DELETED : row;
    }

    /** Postings of one column in a MemSegment. */
    private static class MemIndex implements ColumnIndex {
        private final boolean multi;
        private final Map<Object, Chunks.IntList> postings = new ConcurrentHashMap<>();
        private final Chunks.IntList nulls = new Chunks.IntList();

        MemIndex(boolean multi) {
            this.multi = multi;
        }

        void add(int doc, Object value) {
            if (value == null) {
                nulls.add(doc);
            } else if (multi) {
                for (Object e : (List<?>) value) {
                    Chunks.IntList l = postings.computeIfAbsent(e, k -> new Chunks.IntList());
                    if (l.last() != doc) {
                        l.add(doc);
                    }
                }
            } else {
                postings.computeIfAbsent(value, k -> new Chunks.IntList()).add(doc);
            }
        }

        @Override
        public boolean hasPostings() {
            return true;
        }

        @Override
        public RoaringBitmap eq(Object key, RoaringBitmap within) {
            RoaringBitmap bm = new RoaringBitmap();
            Chunks.IntList l = postings.get(key);
            if (l != null) {
                l.addTo(bm, Integer.MAX_VALUE);
            }
            return bm;
        }

        @Override
        public RoaringBitmap nulls() {
            RoaringBitmap bm = new RoaringBitmap();
            nulls.addTo(bm, Integer.MAX_VALUE);
            return bm;
        }

        @Override
        public String describe() {
            return "mem postings";
        }
    }
}
