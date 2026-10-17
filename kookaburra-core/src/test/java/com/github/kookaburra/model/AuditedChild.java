package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.PrePersist;

/**
 * Subclass of {@link Audited}: its own {@code @PrePersist} runs after the inherited one.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class AuditedChild extends Audited {
    int level;

    public AuditedChild() {
    }

    public AuditedChild(String name) {
        super(name);
    }

    @PrePersist
    void childPrePersist() {
        level = 7;
        EVENTS.add("childPrePersist " + getName());
    }

    public int getLevel() { return level; }
}
