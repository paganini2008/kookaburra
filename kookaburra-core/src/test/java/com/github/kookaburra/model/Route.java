package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;

/**
 * First nesting level of a {@link Shipment}.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Route {
    Place from;
    Place to;
    @Index(IndexKind.ORDINAL) int legs;

    public Route() {
    }

    public Route(Place from, Place to, int legs) {
        this.from = from;
        this.to = to;
        this.legs = legs;
    }

    public Place getFrom() { return from; }
    public Place getTo() { return to; }
    public int getLegs() { return legs; }
}
