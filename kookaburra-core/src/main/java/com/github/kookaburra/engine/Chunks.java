package com.github.kookaburra.engine;

import org.roaringbitmap.RoaringBitmap;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Append-only arrays for one writer and many lock-free readers. Storage is split into fixed chunks;
 * growth only appends chunks and republishes the (volatile) directory, so a reader holding any directory
 * sees every element written before the size it read (storage-design §6.2).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Chunks {

    static final int SHIFT = 12;
    static final int SIZE = 1 << SHIFT;
    static final int MASK = SIZE - 1;

    private Chunks() {
    }

    /** {@code long} values, settable after append with release / acquire semantics. */
    static class Longs {
        private volatile AtomicLongArray[] dir = new AtomicLongArray[0];
        private final long initial;

        Longs(long initial) {
            this.initial = initial;
        }

        void set(int i, long v) {
            ensure(i);
            dir[i >>> SHIFT].setRelease(i & MASK, v);
        }

        long get(int i) {
            return dir[i >>> SHIFT].getAcquire(i & MASK);
        }

        private void ensure(int i) {
            int c = i >>> SHIFT;
            AtomicLongArray[] d = dir;
            if (c >= d.length) {
                AtomicLongArray[] n = Arrays.copyOf(d, c + 1);
                for (int k = d.length; k <= c; k++) {
                    n[k] = new AtomicLongArray(SIZE);
                    if (initial != 0) {
                        for (int j = 0; j < SIZE; j++) {
                            n[k].setPlain(j, initial);
                        }
                    }
                }
                dir = n;
            }
        }
    }

    /** {@code int} values. */
    static class Ints {
        private volatile int[][] dir = new int[0][];

        void set(int i, int v) {
            int c = i >>> SHIFT;
            int[][] d = dir;
            if (c >= d.length) {
                d = Arrays.copyOf(d, c + 1);
                d[c] = new int[SIZE];
                dir = d;
            }
            d[c][i & MASK] = v;
        }

        int get(int i) {
            return dir[i >>> SHIFT][i & MASK];
        }
    }

    /** Object references. */
    static class Refs<T> {
        private volatile Object[][] dir = new Object[0][];

        void set(int i, T v) {
            int c = i >>> SHIFT;
            Object[][] d = dir;
            if (c >= d.length) {
                d = Arrays.copyOf(d, c + 1);
                d[c] = new Object[SIZE];
                dir = d;
            }
            d[c][i & MASK] = v;
        }

        @SuppressWarnings("unchecked")
        T get(int i) {
            return (T) dir[i >>> SHIFT][i & MASK];
        }
    }

    /** Growing list of ints (postings) with a volatile size; readers see a consistent prefix. */
    static class IntList {
        private volatile int[] data = new int[4];
        private volatile int size;

        void add(int v) {
            int[] d = data;
            int n = size;
            if (n == d.length) {
                d = Arrays.copyOf(d, n * 2);
                data = d;
            }
            d[n] = v;
            size = n + 1;
        }

        /** Last element, or -1 when empty. */
        int last() {
            int n = size;
            return n == 0 ? -1 : data[n - 1];
        }

        /** Adds elements {@code < limit} to the bitmap. */
        void addTo(RoaringBitmap bitmap, int limit) {
            int n = size;
            int[] d = data;
            for (int i = 0; i < n; i++) {
                if (d[i] < limit) {
                    bitmap.add(d[i]);
                }
            }
        }
    }
}
