package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;

/**
 * Target of foreign keys in the constraint tests.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class Team {
    @Id long oid;
    String name;

    public Team() {
    }

    public Team(String name) {
        this.name = name;
    }

    public long getOid() { return oid; }
    public String getName() { return name; }
}
