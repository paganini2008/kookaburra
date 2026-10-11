package com.github.kookaburra;

/**
 * A reference to another entity, stored as its OID.
 *
 * @param <T> referenced entity type
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public final class Ref<T> {
    private final long oid;
    /** Resolved target (not part of equality); set by {@link #resolve} or by {@code QueryStream.fetch}. */
    private transient volatile Object target;

    private Ref(long oid) {
        if (oid <= 0) {
            throw new IllegalArgumentException("OID must be positive: " + oid);
        }
        this.oid = oid;
    }

    public static <T> Ref<T> of(long oid) {
        return new Ref<>(oid);
    }

    public long oid() {
        return oid;
    }

    /** The referenced object (loaded once, then cached); null if it no longer exists. */
    @SuppressWarnings("unchecked")
    public T resolve(ObjectDB db) {
        Object t = target;
        if (t == null) {
            t = db.get(oid);
            target = t;
        }
        return (T) t;
    }

    /** Whether the target is already loaded. */
    public boolean isResolved() {
        return target != null;
    }

    /** Internal: stores a pre-fetched target. */
    public void preload(Object resolved) {
        this.target = resolved;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Ref<?> r && r.oid == oid;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(oid);
    }

    @Override
    public String toString() {
        return "Ref(" + oid + ")";
    }
}
