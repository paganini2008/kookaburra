package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Index;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Address {
    @Index String city;
    String zip;

    public Address() {
    }

    public Address(String city, String zip) {
        this.city = city;
        this.zip = zip;
    }

    public String getCity() {
        return city;
    }

    public String getZip() {
        return zip;
    }
}
