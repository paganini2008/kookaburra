package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Embeddable;

/**
 * Amount inside an {@link Item} (embedded inside an embedded list element).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Money {
    long cents;
    String currency;

    public Money() {
    }

    public Money(long cents, String currency) {
        this.cents = cents;
        this.currency = currency;
    }

    public long getCents() { return cents; }
    public String getCurrency() { return currency; }
}
