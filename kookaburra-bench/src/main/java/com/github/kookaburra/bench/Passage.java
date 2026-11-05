package com.github.kookaburra.bench;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Vector;

/**
 * Vector benchmark entity: an indexed topic (filters of any selectivity) and a 384-dimensional embedding.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@Entity
public class Passage {

    static final int DIM = 384;

    @Id long oid;
    @Index int topic;
    @Vector(dim = DIM) float[] embedding;

    public Passage() {
    }

    Passage(int topic, float[] embedding) {
        this.topic = topic;
        this.embedding = embedding;
    }

    public long getOid() {
        return oid;
    }

    public int getTopic() {
        return topic;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
