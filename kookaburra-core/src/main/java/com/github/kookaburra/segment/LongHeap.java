package com.github.kookaburra.segment;

import java.util.Arrays;

/**
 * Binary heap of primitive longs, min-heap or max-heap, growing as needed.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
final class LongHeap {

    private final boolean max;
    private long[] heap;
    private int size;

    LongHeap(boolean max, int capacity) {
        this.max = max;
        this.heap = new long[Math.max(4, capacity)];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        size = 0;
    }

    /** The smallest (min-heap) or largest (max-heap) value; the heap must not be empty. */
    long peek() {
        return heap[0];
    }

    private boolean before(long a, long b) {
        return max ? a > b : a < b;
    }

    void push(long v) {
        if (size == heap.length) {
            heap = Arrays.copyOf(heap, size * 2);
        }
        int i = size++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (!before(v, heap[p])) {
                break;
            }
            heap[i] = heap[p];
            i = p;
        }
        heap[i] = v;
    }

    long pop() {
        long top = heap[0];
        long last = heap[--size];
        int i = 0;
        int half = size >>> 1;
        while (i < half) {
            int c = 2 * i + 1;
            if (c + 1 < size && before(heap[c + 1], heap[c])) {
                c++;
            }
            if (!before(heap[c], last)) {
                break;
            }
            heap[i] = heap[c];
            i = c;
        }
        if (size > 0) {
            heap[i] = last;
        }
        return top;
    }

    /** The values in ascending order (the heap is left unchanged). */
    long[] sortedAscending() {
        long[] out = Arrays.copyOf(heap, size);
        Arrays.sort(out);
        return out;
    }
}
