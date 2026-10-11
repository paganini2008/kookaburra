package com.github.kookaburra.query;

/**
 * Three-argument function, e.g. a constructor reference for {@code select(NameAgeCity::new, ...)}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@FunctionalInterface
public interface Fn3<A, B, C, R> {
    R apply(A a, B b, C c);
}
