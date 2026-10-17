package com.github.kookaburra.readme;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.OnDelete;
import com.github.kookaburra.annotation.PrePersist;
import com.github.kookaburra.annotation.Unique;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * README example: unique number, a cascading foreign key, an exact decimal total and a lifecycle callback.
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Entity
public class Order {
    @Id long oid;
    @Unique String number;                                            // no two orders share a number
    @ForeignKey(onDelete = OnDelete.CASCADE) Ref<Customer> customer;  // deleted with the customer
    @Index BigDecimal total;                                          // exact money, numeric order
    Instant created;

    public Order() {
    }

    public Order(String number, long customer, String total) {
        this.number = number;
        this.customer = Ref.of(customer);
        this.total = new BigDecimal(total);
    }

    @PrePersist
    void stamp() {
        created = Instant.now();
    }

    public long getOid() { return oid; }
    public String getNumber() { return number; }
    public Ref<Customer> getCustomer() { return customer; }
    public BigDecimal getTotal() { return total; }
    public Instant getCreated() { return created; }
}
