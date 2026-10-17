package com.github.kookaburra;

/**
 * Receives lifecycle events of entities of one type (and its subclasses), registered with
 * {@link ObjectDB#addListener}. Implement only the methods you need; see {@link LifecycleEvent} for timing.
 * Typical uses: audit fields, derived values, validation, cache invalidation.
 *
 * <pre>{@code
 * db.addListener(Auditable.class, new EntityListener<>() {
 *     public void prePersist(Auditable a) { a.created = Instant.now(); }
 *     public void preUpdate(Auditable a)  { a.modified = Instant.now(); }
 * });
 * }</pre>
 *
 * @param <T> entity type
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
public interface EntityListener<T> {

    default void prePersist(T entity) {
    }

    default void postPersist(T entity) {
    }

    default void preUpdate(T entity) {
    }

    default void postUpdate(T entity) {
    }

    default void preRemove(T entity) {
    }

    default void postRemove(T entity) {
    }

    default void postLoad(T entity) {
    }

    /** Dispatches {@code event} to the matching method. */
    default void on(LifecycleEvent event, T entity) {
        switch (event) {
            case PRE_PERSIST -> prePersist(entity);
            case POST_PERSIST -> postPersist(entity);
            case PRE_UPDATE -> preUpdate(entity);
            case POST_UPDATE -> postUpdate(entity);
            case PRE_REMOVE -> preRemove(entity);
            case POST_REMOVE -> postRemove(entity);
            case POST_LOAD -> postLoad(entity);
        }
    }
}
