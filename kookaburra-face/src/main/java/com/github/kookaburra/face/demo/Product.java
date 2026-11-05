package com.github.kookaburra.face.demo;

import com.github.kookaburra.annotation.Comment;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.FullText;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Unique;
import com.github.kookaburra.annotation.Vector;

import java.math.BigDecimal;

/**
 * Product of the demo data set (root of a small class hierarchy).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
@Comment("Things the shop sells: books, gadgets and home items")
public class Product {
    @Id long oid;
    @Index String title;
    /** Stock keeping unit, unique across all products. */
    @Unique @Comment("Stock keeping unit, unique across all products") String sku;
    @Index @Comment("Unit price in AUD") BigDecimal price;
    /** PNG thumbnail (binary content). */
    byte[] photo;
    /** Short English description, full-text indexed: search it with MATCH / SCORE in the console. */
    @FullText String description;
    /** Embedding with readable axes {@code [reading, tech, home, outdoor]}: books, gadgets and items form clusters. */
    @Vector(dim = 4) float[] embedding;
}
