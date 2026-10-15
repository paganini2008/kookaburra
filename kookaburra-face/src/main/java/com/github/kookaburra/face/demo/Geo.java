package com.github.kookaburra.face.demo;

import com.github.kookaburra.annotation.Embeddable;

/**
 * Coordinates of an address (second level of embedding in the demo data set).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Geo {
    double lat;
    double lon;

    public Geo() {
    }

    public Geo(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }
}
