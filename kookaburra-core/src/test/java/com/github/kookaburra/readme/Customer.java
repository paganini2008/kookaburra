package com.github.kookaburra.readme;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Unique;

/**
 * README example: a customer with a unique e-mail.
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
@Entity
public class Customer {
    @Id long oid;
    String name;
    @Unique String email;

    public Customer() {
    }

    public Customer(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public long getOid() { return oid; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}
