package com.github.kookaburra.annotation;

import java.util.function.ToDoubleFunction;

/**
 * How close two vectors are, as a distance: smaller is closer, so nearest-neighbour search sorts ascending. Same
 * definitions as pgvector's operators ({@code <=>}, {@code <->}, {@code <#>}).
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
public enum VectorDistance {
    /** {@code 1 - cos(a, b)}, from 0 (same direction) to 2; NaN when a vector is all zeros. Text embeddings. */
    COSINE,
    /** Euclidean distance {@code |a - b|}. */
    L2,
    /** Negative inner product {@code -(a · b)}: for normalized vectors it orders like COSINE, without the norms. */
    INNER_PRODUCT;

    /** The distance between {@code a} and {@code b}; both must have the same length. */
    public double distance(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vectors of different dimensions: " + a.length + " and " + b.length);
        }
        return switch (this) {
            case COSINE -> cosine(a, b, Math.sqrt(dot(b, b)));
            case L2 -> Math.sqrt(squaredL2(a, b));
            case INNER_PRODUCT -> -dot(a, b);
        };
    }

    /**
     * The distance to a fixed {@code query} as a function of the other vector (same length), with what depends on
     * the query alone (its norm) computed once: for scanning many vectors.
     */
    public ToDoubleFunction<float[]> to(float[] query) {
        float[] q = query.clone();
        return switch (this) {
            case COSINE -> {
                double qn = Math.sqrt(dot(q, q));
                yield v -> cosine(v, q, qn);
            }
            case L2 -> v -> Math.sqrt(squaredL2(v, q));
            case INNER_PRODUCT -> v -> -dot(v, q);
        };
    }

    /** {@code a · b}, accumulated in double. */
    public static double dot(float[] a, float[] b) {
        double s0 = 0;
        double s1 = 0;
        double s2 = 0;
        double s3 = 0;
        int i = 0;
        for (int n = a.length & ~3; i < n; i += 4) {
            s0 += a[i] * b[i];
            s1 += a[i + 1] * b[i + 1];
            s2 += a[i + 2] * b[i + 2];
            s3 += a[i + 3] * b[i + 3];
        }
        for (; i < a.length; i++) {
            s0 += a[i] * b[i];
        }
        return s0 + s1 + s2 + s3;
    }

    private static double squaredL2(float[] a, float[] b) {
        double s0 = 0;
        double s1 = 0;
        int i = 0;
        for (int n = a.length & ~1; i < n; i += 2) {
            double d0 = a[i] - b[i];
            double d1 = a[i + 1] - b[i + 1];
            s0 += d0 * d0;
            s1 += d1 * d1;
        }
        if (i < a.length) {
            double d = a[i] - b[i];
            s0 += d * d;
        }
        return s0 + s1;
    }

    /** Cosine distance with the norm of {@code b} given. */
    private static double cosine(float[] a, float[] b, double bNorm) {
        double d0 = 0;
        double d1 = 0;
        double n0 = 0;
        double n1 = 0;
        int i = 0;
        for (int n = a.length & ~1; i < n; i += 2) {
            float x0 = a[i];
            float x1 = a[i + 1];
            d0 += x0 * b[i];
            d1 += x1 * b[i + 1];
            n0 += x0 * x0;
            n1 += x1 * x1;
        }
        if (i < a.length) {
            d0 += a[i] * b[i];
            n0 += a[i] * a[i];
        }
        return 1 - (d0 + d1) / (Math.sqrt(n0 + n1) * bNorm);
    }
}
