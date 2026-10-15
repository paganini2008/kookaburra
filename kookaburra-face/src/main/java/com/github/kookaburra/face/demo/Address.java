package com.github.kookaburra.face.demo;

import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Index;

/**
 * Embedded address of the demo data set.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Address {
    @Index String city;
    String street;
    Geo geo;

    public Address() {
    }

    public Address(String city, String street) {
        this.city = city;
        this.street = street;
    }

    public Address(String city, String street, Geo geo) {
        this(city, street);
        this.geo = geo;
    }
}
