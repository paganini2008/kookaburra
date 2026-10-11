package com.github.kookaburra.segment;

import org.roaringbitmap.RoaringBitmap;

import java.util.Arrays;

/**
 * Deleted documents of one segment (design §6.3): a persisted base bitmap plus an append-only log of
 * {@code (seq, docid)} written by the sequencer. Readers ask for the deletions visible at a snapshot;
 * the result for the latest prefix is cached and extended incrementally.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class SegmentDeletes {

    private record Cached(int count, RoaringBitmap bitmap) {
    }

    private final RoaringBitmap base;
    private volatile long[] seqs = new long[8];
    private volatile int[] docs = new int[8];
    private volatile int count;
    private volatile Cached cached;
    /** Sequencer-only view: base plus every logged delete. */
    private final RoaringBitmap latest;

    public SegmentDeletes(RoaringBitmap base) {
        this.base = base;
        this.cached = new Cached(0, base);
        this.latest = base.clone();
    }

    /** Appends a delete; sequence numbers must not decrease. Sequencer thread only. */
    public void add(long seq, int doc) {
        int n = count;
        long[] s = seqs;
        int[] d = docs;
        if (n == s.length) {
            s = Arrays.copyOf(s, n * 2);
            d = Arrays.copyOf(d, n * 2);
        }
        s[n] = seq;
        d[n] = doc;
        seqs = s;
        docs = d;
        count = n + 1;
        latest.add(doc);
    }

    /** Whether the document is deleted in the latest state. Sequencer thread only. */
    public boolean isDeleted(int doc) {
        return latest.contains(doc);
    }

    /** Number of logged deletes. */
    public int logged() {
        return count;
    }

    public RoaringBitmap base() {
        return base;
    }

    /** Logged deletes with {@code seq <= upTo}, as (seq, doc) pairs. */
    public long[][] entries(long upTo) {
        int k = visible(upTo);
        long[][] out = new long[k][];
        long[] s = seqs;
        int[] d = docs;
        for (int i = 0; i < k; i++) {
            out[i] = new long[]{s[i], d[i]};
        }
        return out;
    }

    /** Deleted documents visible at snapshot {@code seq}. The result must not be modified. */
    public RoaringBitmap deleted(long seq) {
        int k = visible(seq);
        Cached c = cached;
        if (k == c.count()) {
            return c.bitmap();
        }
        int[] d = docs;
        if (k > c.count()) {
            RoaringBitmap bm = c.bitmap().clone();
            for (int i = c.count(); i < k; i++) {
                bm.add(d[i]);
            }
            cached = new Cached(k, bm);
            return bm;
        }
        RoaringBitmap bm = base.clone();
        for (int i = 0; i < k; i++) {
            bm.add(d[i]);
        }
        return bm;
    }

    /** Number of log entries with {@code seq <= snapshot}. */
    private int visible(long snapshot) {
        int n = count;
        long[] s = seqs;
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (s[mid] <= snapshot) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
