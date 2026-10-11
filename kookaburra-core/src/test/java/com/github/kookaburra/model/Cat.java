package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
public class Cat extends Animal {
    boolean indoor;

    public Cat() {
    }

    public Cat(String name, int age, boolean indoor) {
        super(name, age);
        this.indoor = indoor;
    }

    public boolean isIndoor() { return indoor; }
}
