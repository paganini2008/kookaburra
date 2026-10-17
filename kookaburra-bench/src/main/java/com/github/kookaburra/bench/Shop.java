package com.github.kookaburra.bench;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.ForeignKey;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.OnDelete;
import com.github.kookaburra.annotation.Unique;

/**
 * Entities of the integrity phase: the same e-mail field without and with {@code @Unique}, and orders with a
 * cascading {@code @ForeignKey}.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class Shop {

    private Shop() {
    }

    @Entity
    static class Plain {
        @Id long oid;
        String email;

        Plain() {
        }

        Plain(String email) {
            this.email = email;
        }
    }

    @Entity
    static class Customer {
        @Id long oid;
        @Unique String email;

        Customer() {
        }

        Customer(String email) {
            this.email = email;
        }

        long getOid() {
            return oid;
        }
    }

    @Entity
    static class Order {
        @Id long oid;
        @ForeignKey(onDelete = OnDelete.CASCADE) Ref<Customer> customer;
        int amount;

        Order() {
        }

        Order(long customer, int amount) {
            this.customer = Ref.of(customer);
            this.amount = amount;
        }
    }
}
