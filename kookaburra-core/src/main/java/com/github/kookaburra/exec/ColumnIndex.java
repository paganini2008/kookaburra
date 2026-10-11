package com.github.kookaburra.exec;

import org.roaringbitmap.RoaringBitmap;

import java.util.function.IntPredicate;

/**
 * Optional accelerated lookups on one column of one segment. A method returning {@code null} means
 * "not supported here": the executor then scans. {@code within} is the current candidate set; results
 * may contain other documents (the executor intersects), but scan-based implementations only need to
 * examine {@code within}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface ColumnIndex {

    /** Documents whose value (any element for multi-valued columns) equals the canonical {@code key}. */
    default RoaringBitmap eq(Object key, RoaringBitmap within) {
        return null;
    }

    /** Documents with a value in the range; bounds may be null (open). */
    default RoaringBitmap range(Object lo, boolean loInclusive, Object hi, boolean hiInclusive, RoaringBitmap within) {
        return null;
    }

    /** Documents whose string value starts with {@code prefix}. */
    default RoaringBitmap prefix(String prefix, RoaringBitmap within) {
        return null;
    }

    /** Documents whose value is null. */
    default RoaringBitmap nulls() {
        return null;
    }

    /** True if equality lookups are answered from postings (cheap, used for conjunct ordering). */
    default boolean hasPostings() {
        return false;
    }

    /**
     * Dictionary size when every document has exactly one ordinal (single-valued, dictionary-encoded column),
     * else -1. Enables grouping by ordinal without decoding values (design §10.4).
     */
    default int ordinals() {
        return -1;
    }

    /** Ordinal + 1 of a document's value, 0 for null; only valid when {@link #ordinals()} {@code >= 0}. */
    default int ordinal(int doc) {
        return 0;
    }

    /** Canonical value of dictionary entry {@code ord}; only valid when {@link #ordinals()} {@code >= 0}. */
    default Object key(int ord) {
        return null;
    }

    /**
     * Visits the non-null documents in value order (ascending, or descending), documents of equal value in
     * ascending order, until {@code visitor} returns false. Returns false when the column cannot do this.
     */
    default boolean forEachInOrder(boolean descending, IntPredicate visitor) {
        return false;
    }

    /**
     * Matching documents per value without visiting them: {@code [0]} = nulls, {@code [ord + 1]} = value
     * {@code ord}; null when the column has no postings (or is multi-valued).
     */
    default long[] countByOrdinal(RoaringBitmap within) {
        return null;
    }

    /** Short description for explain output, e.g. {@code BITMAP post}. */
    String describe();
}
