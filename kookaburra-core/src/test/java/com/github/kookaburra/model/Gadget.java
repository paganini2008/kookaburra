package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;

/**
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@Entity
public class Gadget {
    @Id long oid;
    Specs specs;
    String name;

    public Specs getSpecs() { return specs; }
    public String getName() { return name; }
}
