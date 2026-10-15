package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Embeddable;

/**
 * Third nesting level: {@code Shipment.route.to.geo.lat}.
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

    public double getLat() { return lat; }
    public double getLon() { return lon; }
}
