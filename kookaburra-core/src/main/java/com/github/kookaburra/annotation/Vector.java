package com.github.kookaburra.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a {@code float[]} field as an embedding vector (float32): its dimension, checked on every write, and the
 * distance its nearest-neighbour searches use by default. A {@code float[]} field without it is a vector of any
 * length with {@link VectorDistance#COSINE}.
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Vector {

    /** Number of components every stored vector must have (0 = not checked). */
    int dim() default 0;

    /** Default distance of {@code nearest(...)} on this field. */
    VectorDistance distance() default VectorDistance.COSINE;

    /** How the vector index keeps the vectors for its search ({@code INT8}: a quarter of the memory). */
    VectorQuantization quantization() default VectorQuantization.NONE;
}
