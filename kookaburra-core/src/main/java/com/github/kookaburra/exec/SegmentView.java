package com.github.kookaburra.exec;

import com.github.kookaburra.mapping.Record;
import org.roaringbitmap.RoaringBitmap;

import java.util.Set;

/**
 * Read interface shared by in-memory and on-disk segments (design §2): one executor for all.
 * Documents are identified by segment-local ints ({@code docid}).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface SegmentView {

    /** Result of {@link #find} when the object's visible version is deleted. */
    int DELETED = -2;

    /** Short label for explain output, e.g. {@code mem#3} or {@code seg#12}. */
    String label();

    /** Documents visible and not deleted at snapshot {@code seq}. */
    RoaringBitmap live(long seq);

    long oid(int doc);

    long version(int doc);

    int classId(int doc);

    /** Decoded canonical record. */
    Record record(int doc);

    /** Canonical value at a path (column if available, else from the record). */
    default Object value(int doc, String path) {
        return record(doc).get(path);
    }

    /** Index on a path, or null. */
    ColumnIndex index(String path);

    /** Documents whose class id is in {@code classIds}. */
    default RoaringBitmap classes(Set<Integer> classIds, RoaringBitmap within) {
        RoaringBitmap out = new RoaringBitmap();
        within.forEach((int d) -> {
            if (classIds.contains(classId(d))) {
                out.add(d);
            }
        });
        return out;
    }

    /** Document holding the version of {@code oid} visible at {@code seq}; -1 if absent here, {@link #DELETED} if deleted. */
    int find(long oid, long seq);

    /** Approximate number of documents (for explain / planning). */
    int size();
}
