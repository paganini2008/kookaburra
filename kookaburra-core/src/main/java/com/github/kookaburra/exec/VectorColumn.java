package com.github.kookaburra.exec;

import com.github.kookaburra.annotation.VectorDistance;
import org.roaringbitmap.RoaringBitmap;

import java.util.function.ToDoubleFunction;

/**
 * The vectors of one {@code @Vector(dim = N)} path in one segment, stored contiguously (float32), with an optional
 * HNSW graph for approximate nearest-neighbour search. Only vectors of the declared dimension are in it.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public interface VectorColumn {

    /** Receives one document and its distance to the query. */
    @FunctionalInterface
    interface Sink {
        void accept(int doc, double distance);
    }

    /** Dimension of every vector in the column. */
    int dim();

    /** Number of documents with a vector. */
    int count();

    /** Distance the graph was built for, or null when the segment has no graph. */
    VectorDistance graphDistance();

    /** Exact: every document of {@code within} that has a vector, with {@code to} applied to it. */
    void scan(RoaringBitmap within, ToDoubleFunction<float[]> to, Sink sink);

    /**
     * Approximate: the {@code k} documents of {@code accept} closest to {@code query} found by the graph with
     * {@code ef} candidates (exact distances). False, with nothing sent, when the search compared more than
     * {@code visitLimit} vectors: a selective filter, better scanned exactly.
     */
    boolean search(float[] query, int k, int ef, RoaringBitmap accept, int visitLimit, Sink sink);

    /** Short description for explain output. */
    String describe();
}
