package com.github.kookaburra.face.demo;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Unique;

import java.math.BigDecimal;

/**
 * Product of the demo data set (root of a small class hierarchy).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
public class Product {
    @Id long oid;
    @Index String title;
    /** Stock keeping unit, unique across all products. */
    @Unique String sku;
    @Index BigDecimal price;
    /** PNG thumbnail (binary content). */
    byte[] photo;
}
