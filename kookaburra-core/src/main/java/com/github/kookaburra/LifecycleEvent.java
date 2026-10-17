package com.github.kookaburra;

import com.github.kookaburra.annotation.PostLoad;
import com.github.kookaburra.annotation.PostPersist;
import com.github.kookaburra.annotation.PostRemove;
import com.github.kookaburra.annotation.PostUpdate;
import com.github.kookaburra.annotation.PrePersist;
import com.github.kookaburra.annotation.PreRemove;
import com.github.kookaburra.annotation.PreUpdate;

import java.lang.annotation.Annotation;

/**
 * Entity lifecycle events, delivered to {@link EntityListener}s and to annotated entity methods.
 * <ul>
 *   <li>{@code PRE_*} run on the caller's thread when the write is issued (inside a transaction: when the
 *       transaction's {@code insert / update / delete} is called); an exception cancels that write.</li>
 *   <li>{@code POST_PERSIST / POST_UPDATE / POST_REMOVE} run after the write has committed (inside a transaction:
 *       after {@code commit()}); the data is already durable when they run.</li>
 *   <li>{@code POST_LOAD} runs for every object read as an entity (get, queries, OQL results).</li>
 * </ul>
 * Like JPA, bulk OQL {@code UPDATE / DELETE}, deletes by OID, increments and schema-driven
 * {@link com.github.kookaburra.dynamic.DynamicObject}s do not run callbacks. Order: listeners in registration
 * order, then the entity's own methods (superclass methods first).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
public enum LifecycleEvent {
    PRE_PERSIST(PrePersist.class),
    POST_PERSIST(PostPersist.class),
    PRE_UPDATE(PreUpdate.class),
    POST_UPDATE(PostUpdate.class),
    PRE_REMOVE(PreRemove.class),
    POST_REMOVE(PostRemove.class),
    POST_LOAD(PostLoad.class);

    private final Class<? extends Annotation> annotation;

    LifecycleEvent(Class<? extends Annotation> annotation) {
        this.annotation = annotation;
    }

    /** The method annotation of this event. */
    public Class<? extends Annotation> annotation() {
        return annotation;
    }
}
