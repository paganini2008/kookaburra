package com.github.kookaburra.face.demo;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.OnDelete;
import com.github.kookaburra.annotation.Version;

import java.time.Instant;
import java.util.List;

/**
 * Purchase of the demo data set: references a customer and a product.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
public class Purchase {
    @Id long oid;
    @Version long version;
    /** Deleting a customer deletes their purchases. */
    @ForeignKey(onDelete = OnDelete.CASCADE) Ref<Customer> customer;
    /** A product that was purchased cannot be deleted. */
    @ForeignKey Ref<Product> product;
    int quantity;
    @Index Instant placedAt;
    /** Order lines: a list of embedded objects, each with a nested price. */
    List<Line> lines;
}
