package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;

/**
 * Subclass of {@link Member}: shares the unique constraints of its superclass.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class VipMember extends Member {
    int tier;

    public VipMember() {
    }

    public VipMember(String name, String email) {
        super(name, email);
    }
}
