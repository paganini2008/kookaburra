package com.github.kookaburra.text;

/**
 * Okapi BM25 relevance (Lucene's formula and defaults {@code k1 = 1.2}, {@code b = 0.75}): for each query word,
 * {@code idf · tf · (k1 + 1) / (tf + k1 · (1 - b + b · length / averageLength))} with
 * {@code idf = ln(1 + (N - df + 0.5) / (df + 0.5))}. Collection statistics count every document with text, deleted
 * ones included until a merge (as Lucene does).
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public final class Bm25 {

    public static final double K1 = 1.2;
    public static final double B = 0.75;

    private Bm25() {
    }

    public static double idf(long docCount, long df) {
        return Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
    }

    /** Contribution of one word occurring {@code tf} times in a document of {@code length} words. */
    public static double term(double idf, int tf, int length, double averageLength) {
        if (tf <= 0) {
            return 0;
        }
        double norm = K1 * (1 - B + B * (averageLength > 0 ? length / averageLength : 1));
        return idf * tf * (K1 + 1) / (tf + norm);
    }
}
