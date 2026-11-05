package com.github.kookaburra.annotation;

/**
 * How the vector index keeps vectors for its search (as Elasticsearch's {@code int8_hnsw} / Lucene's scalar
 * quantization). The full float32 vectors are kept as well: results are always ranked by exact distances.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public enum VectorQuantization {
    /** float32 (4 bytes per component). */
    NONE,
    /**
     * One byte per component for the graph search (a quarter of the memory it reads), scaled per segment to the
     * range of 99 % of the values; the closest candidates are then re-ranked with the float vectors.
     */
    INT8
}
