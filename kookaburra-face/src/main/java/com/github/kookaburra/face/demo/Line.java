package com.github.kookaburra.face.demo;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Embeddable;

/**
 * One line of a purchase: element of an embedded list, with a nested embedded price and a reference.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Line {
    Ref<Product> product;
    String sku;
    int qty;
    Money price;

    public Line() {
    }

    public Line(Ref<Product> product, String sku, int qty, Money price) {
        this.product = product;
        this.sku = sku;
        this.qty = qty;
        this.price = price;
    }
}
