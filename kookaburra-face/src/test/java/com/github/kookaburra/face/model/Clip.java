package com.github.kookaburra.face.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Vector;

/**
 * Test entity with a 3-dimensional embedding vector.
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
@Entity
public class Clip {
    @Id long oid;
    String name;
    @Vector(dim = 3) float[] embedding;

    public Clip() {
    }

    public Clip(String name, float... embedding) {
        this.name = name;
        this.embedding = embedding.length == 0 ? null : embedding;
    }
}
