package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Vector;
import com.github.kookaburra.annotation.VectorDistance;
import com.github.kookaburra.annotation.VectorQuantization;

/**
 * HNSW test entity: the same 16-dimensional vector under the three distances (one graph each), an int8-quantized
 * copy, and a group for
 * filters of any selectivity.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@Entity
public class Emb {
    @Id long oid;
    String name;
    @Index int grp;
    @Vector(dim = 16) float[] cos;
    @Vector(dim = 16, distance = VectorDistance.L2) float[] l2;
    @Vector(dim = 16, distance = VectorDistance.INNER_PRODUCT) float[] ip;
    @Vector(dim = 16, distance = VectorDistance.L2, quantization = VectorQuantization.INT8) float[] q8;

    public Emb() {
    }

    public Emb(String name, int grp, float[] v) {
        this.name = name;
        this.grp = grp;
        this.cos = v;
        this.l2 = v;
        this.ip = v;
        this.q8 = v;
    }

    public long getOid() { return oid; }
    public String getName() { return name; }
    public int getGrp() { return grp; }
    public float[] getCos() { return cos; }
    public float[] getL2() { return l2; }
    public float[] getIp() { return ip; }
    public float[] getQ8() { return q8; }
}
