package com.github.kookaburra.query;

/**
 * A result of a nearest-neighbour search with its distance to the query vector (smaller is closer).
 *
 * @param value    the object
 * @param distance distance by the search's {@code VectorDistance}
 * @param score    the reranker's relevance after {@code rerank(...)} (higher is better), else NaN
 * @param <T>      result type
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
public record Neighbor<T>(T value, double distance, double score) {

    /** A result without a rerank score. */
    public Neighbor(T value, double distance) {
        this(value, distance, Double.NaN);
    }
}
