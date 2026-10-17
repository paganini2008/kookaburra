package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.PostLoad;
import com.github.kookaburra.annotation.PostPersist;
import com.github.kookaburra.annotation.PostRemove;
import com.github.kookaburra.annotation.PostUpdate;
import com.github.kookaburra.annotation.PrePersist;
import com.github.kookaburra.annotation.PreRemove;
import com.github.kookaburra.annotation.PreUpdate;
import com.github.kookaburra.annotation.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lifecycle test entity: every callback annotation, audit timestamps set by callbacks, a derived field and a
 * transient load marker. Callbacks append to {@link #EVENTS}.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Entity
public class Audited {
    /** Callback log of all instances: {@code "<event> <name>"}. */
    public static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    @Id long oid;
    @Version long version;
    String name;
    String upper;
    Instant created;
    Instant modified;
    transient boolean loaded;

    public Audited() {
    }

    public Audited(String name) {
        this.name = name;
    }

    @PrePersist
    void prePersist() {
        if ("boom".equals(name)) {
            throw new IllegalStateException("rejected by @PrePersist");
        }
        created = Instant.parse("2026-01-01T00:00:00Z");
        upper = name.toUpperCase();
        EVENTS.add("prePersist " + name);
    }

    @PostPersist
    void postPersist() {
        EVENTS.add("postPersist " + name + " oid=" + (oid != 0));
    }

    @PreUpdate
    private void preUpdate() {
        modified = Instant.parse("2026-02-01T00:00:00Z");
        upper = name.toUpperCase();
        EVENTS.add("preUpdate " + name);
    }

    @PostUpdate
    protected void postUpdate() {
        EVENTS.add("postUpdate " + name + " v" + version);
    }

    @PreRemove
    public void preRemove() {
        EVENTS.add("preRemove " + name);
    }

    @PostRemove
    void postRemove() {
        EVENTS.add("postRemove " + name);
    }

    @PostLoad
    void postLoad() {
        loaded = true;
    }

    public long getOid() { return oid; }
    public String getName() { return name; }
    public String getUpper() { return upper; }
    public Instant getCreated() { return created; }
    public Instant getModified() { return modified; }
    public boolean isLoaded() { return loaded; }
    public Audited name(String v) { name = v; return this; }
}
