package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Vector;
import com.github.kookaburra.annotation.VectorDistance;

/**
 * Vector test entity: an indexed language (pre-filter), a 3-dimensional cosine embedding, a free-length L2 sketch
 * and an embedded chunk with its own vector.
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
@Entity
public class Doc {
    @Id long oid;
    String title;
    @Index String lang;
    @Vector(dim = 3) float[] embedding;
    @Vector(distance = VectorDistance.L2) float[] sketch;
    Chunk chunk;
    String tail;

    public Doc() {
    }

    public Doc(String title, String lang, float... embedding) {
        this.title = title;
        this.lang = lang;
        this.embedding = embedding.length == 0 ? null : embedding;
        this.tail = "after-" + title;
    }

    public long getOid() { return oid; }
    public String getTitle() { return title; }
    public String getLang() { return lang; }
    public float[] getEmbedding() { return embedding; }
    public float[] getSketch() { return sketch; }
    public Chunk getChunk() { return chunk; }
    public String getTail() { return tail; }
    public Doc sketch(float... v) { sketch = v; return this; }
    public Doc chunk(Chunk v) { chunk = v; return this; }
    public Doc embedding(float... v) { embedding = v; return this; }
}
