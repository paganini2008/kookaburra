package com.github.kookaburra.bench;

import java.util.Arrays;

/**
 * Latency samples (nanoseconds) and their percentiles.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Stats {

    private long[] samples = new long[1024];
    private int size;

    public void add(long nanos) {
        if (size == samples.length) {
            samples = Arrays.copyOf(samples, size * 2);
        }
        samples[size++] = nanos;
    }

    public void addAll(Stats other) {
        for (int i = 0; i < other.size; i++) {
            add(other.samples[i]);
        }
    }

    public int count() {
        return size;
    }

    /** Percentile in nanoseconds, {@code p} in [0, 100]; 0 when empty. */
    public long percentile(double p) {
        if (size == 0) {
            return 0;
        }
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        int i = (int) Math.ceil(p / 100.0 * size) - 1;
        return sorted[Math.max(0, Math.min(size - 1, i))];
    }

    public double mean() {
        if (size == 0) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i < size; i++) {
            sum += samples[i];
        }
        return sum / size;
    }

    /** "p50 / p99" in a readable unit. */
    public String summary() {
        return format(percentile(50)) + " / " + format(percentile(99));
    }

    public static String format(double nanos) {
        if (nanos < 10_000) {
            return String.format("%.2f µs", nanos / 1e3);
        }
        if (nanos < 10_000_000) {
            return String.format("%.3f ms", nanos / 1e6);
        }
        return String.format("%.1f ms", nanos / 1e6);
    }
}
