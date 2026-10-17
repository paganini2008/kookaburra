package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.IndexKind;
import com.github.kookaburra.annotation.Version;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Decimal test entity: an indexed amount (bitmap), an ordinal-indexed rate, an unindexed tax, a list and a map of
 * decimals.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class Invoice {
    @Id long oid;
    @Version long version;
    @Index String customer;
    @Index BigDecimal amount;
    @Index(IndexKind.ORDINAL) BigDecimal rate;
    BigDecimal tax;
    @Index List<BigDecimal> parts;
    Map<String, BigDecimal> fees;

    public Invoice() {
    }

    public Invoice(String customer, String amount) {
        this.customer = customer;
        this.amount = amount == null ? null : new BigDecimal(amount);
    }

    public long getOid() { return oid; }
    public String getCustomer() { return customer; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getTax() { return tax; }
    public List<BigDecimal> getParts() { return parts; }
    public Map<String, BigDecimal> getFees() { return fees; }

    public Invoice rate(String v) { rate = new BigDecimal(v); return this; }
    public Invoice tax(String v) { tax = new BigDecimal(v); return this; }
    public Invoice parts(List<BigDecimal> v) { parts = v; return this; }
    public Invoice fees(Map<String, BigDecimal> v) { fees = v; return this; }
    public Invoice amount(BigDecimal v) { amount = v; return this; }
}
