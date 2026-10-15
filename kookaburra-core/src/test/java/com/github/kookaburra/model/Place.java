package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Index;

/**
 * Second nesting level: {@code Shipment.route.to.city}.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Place {
    @Index String city;
    String street;
    Geo geo;

    public Place() {
    }

    public Place(String city, String street, Geo geo) {
        this.city = city;
        this.street = street;
        this.geo = geo;
    }

    public String getCity() { return city; }
    public String getStreet() { return street; }
    public Geo getGeo() { return geo; }
}
