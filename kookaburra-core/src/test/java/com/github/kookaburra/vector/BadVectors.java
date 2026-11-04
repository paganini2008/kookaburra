package com.github.kookaburra.vector;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Unique;
import com.github.kookaburra.annotation.Vector;

/**
 * Entities with invalid vector declarations.
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
class BadVectors {

    private BadVectors() {
    }

    @Entity
    static class OnDoubles {
        @Id long oid;
        @Vector(dim = 3) double[] v;
    }

    @Entity
    static class NegativeDim {
        @Id long oid;
        @Vector(dim = -1) float[] v;
    }

    @Entity
    static class Indexed {
        @Id long oid;
        @Index float[] v;
    }

    @Entity
    static class UniqueVector {
        @Id long oid;
        @Unique float[] v;
    }
}
