package com.github.kookaburra.engine;

import com.github.kookaburra.exec.ColumnIndex;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.mapping.Record;
import org.roaringbitmap.RoaringBitmap;

import java.util.Set;

/**
 * A snapshot view without the objects a transaction has updated or deleted (their current versions live in the
 * transaction's overlay). Index lookups pass through: the executor intersects them with {@link #live}
 * (docs/transaction-design.md §10).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class MaskedView implements SegmentView {

    private final SegmentView base;
    private final Set<Long> hidden;
    private final long snapshot;
    private RoaringBitmap hiddenDocs;

    MaskedView(SegmentView base, Set<Long> hidden, long snapshot) {
        this.base = base;
        this.hidden = hidden;
        this.snapshot = snapshot;
    }

    SegmentView base() {
        return base;
    }

    /** Documents of the hidden OIDs in the base view (computed once: the hidden set is fixed per instance). */
    private RoaringBitmap hiddenDocs() {
        if (hiddenDocs == null) {
            RoaringBitmap docs = new RoaringBitmap();
            for (long oid : hidden) {
                int d = base.find(oid, snapshot);
                if (d >= 0) {
                    docs.add(d);
                }
            }
            hiddenDocs = docs;
        }
        return hiddenDocs;
    }

    @Override
    public String label() {
        return base.label() + " (masked " + hidden.size() + ")";
    }

    @Override
    public RoaringBitmap live(long seq) {
        RoaringBitmap live = base.live(seq);
        live.andNot(hiddenDocs());
        return live;
    }

    @Override
    public long oid(int doc) {
        return base.oid(doc);
    }

    @Override
    public long version(int doc) {
        return base.version(doc);
    }

    @Override
    public int classId(int doc) {
        return base.classId(doc);
    }

    @Override
    public Record record(int doc) {
        return base.record(doc);
    }

    @Override
    public Object value(int doc, String path) {
        return base.value(doc, path);
    }

    @Override
    public ColumnIndex index(String path) {
        return base.index(path);
    }

    @Override
    public RoaringBitmap classes(Set<Integer> classIds, RoaringBitmap within) {
        return base.classes(classIds, within);
    }

    @Override
    public int find(long oid, long seq) {
        return hidden.contains(oid) ? DELETED : base.find(oid, seq);
    }

    @Override
    public int size() {
        return base.size();
    }
}
