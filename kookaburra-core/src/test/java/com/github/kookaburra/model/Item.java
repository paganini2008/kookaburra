package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Embeddable;

import java.util.Objects;

/**
 * Element of {@code Shipment.items}: a list of embedded objects.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Item {
    String sku;
    int qty;
    Money price;

    public Item() {
    }

    public Item(String sku, int qty, Money price) {
        this.sku = sku;
        this.qty = qty;
        this.price = price;
    }

    public String getSku() { return sku; }
    public int getQty() { return qty; }
    public Money getPrice() { return price; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Item i && Objects.equals(sku, i.sku) && qty == i.qty;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sku, qty);
    }
}
