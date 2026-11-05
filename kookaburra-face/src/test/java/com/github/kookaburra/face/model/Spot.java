package com.github.kookaburra.face.model;

import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Vector;
import com.github.kookaburra.annotation.VectorDistance;

/**
 * Studio vector test entity with the other two distances: an inner-product direction and an L2 position inside an
 * embedded value.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@Entity
public class Spot {
    @Id long oid;
    String name;
    @Vector(dim = 2, distance = VectorDistance.INNER_PRODUCT) float[] dir;
    Place place;

    public Spot() {
    }

    public Spot(String name, float[] dir, float x, float y) {
        this.name = name;
        this.dir = dir;
        this.place = new Place(new float[]{x, y});
    }

    /** Where a spot is. */
    @Embeddable
    public static class Place {
        @Vector(dim = 2, distance = VectorDistance.L2) float[] pos;

        public Place() {
        }

        Place(float[] pos) {
            this.pos = pos;
        }
    }
}
