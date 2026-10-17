package com.github.kookaburra.face.demo;

import com.github.kookaburra.annotation.Embeddable;

import java.math.BigDecimal;

/**
 * An amount of money (embedded in purchase lines).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Money {
    BigDecimal amount;
    String currency;

    public Money() {
    }

    public Money(BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }
}
