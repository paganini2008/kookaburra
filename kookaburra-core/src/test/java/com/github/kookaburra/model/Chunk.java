package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Vector;

/**
 * Embedded value with a vector (dotted path {@code chunk.vector}).
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
@Embeddable
public class Chunk {
    String text;
    @Vector(dim = 2) float[] vector;

    public Chunk() {
    }

    public Chunk(String text, float... vector) {
        this.text = text;
        this.vector = vector;
    }

    public String getText() { return text; }
    public float[] getVector() { return vector; }
}
