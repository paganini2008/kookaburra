package com.github.kookaburra.field;

import java.util.List;

/**
 * Field of a {@code float[]} embedding vector: stored in the row, searched with
 * {@code QueryStream.nearest(...)}; as a condition only {@code isNull} / {@code isNotNull}. An {@link ObjectField}, so
 * {@code where(Doc::getEmbedding).isNotNull()} works like for any row-only value.
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
public class VectorField<T> extends ObjectField<T, float[]> {

    VectorField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }
}
