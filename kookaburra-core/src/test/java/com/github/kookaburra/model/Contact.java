package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Embeddable;
import com.github.kookaburra.annotation.Unique;

/**
 * Embedded contact of a {@link Member} with a unique phone number ({@code contact.phone}).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Contact {
    @Unique String phone;
    String city;

    public Contact() {
    }

    public Contact(String phone, String city) {
        this.phone = phone;
        this.city = city;
    }

    public String getPhone() { return phone; }
    public String getCity() { return city; }
}
