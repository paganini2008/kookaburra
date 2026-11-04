package com.github.kookaburra.query;

/**
 * A result of a nearest-neighbour search with its distance to the query vector (smaller is closer).
 *
 * @param value    the object
 * @param distance distance by the search's {@link com.github.kookaburra.annotation.VectorDistance}
 * @param <T>      result type
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
public record Neighbor<T>(T value, double distance) {
}
