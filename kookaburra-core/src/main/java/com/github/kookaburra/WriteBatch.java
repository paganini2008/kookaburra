package com.github.kookaburra;

/**
 * Writes applied atomically on {@link #commit()}: all become visible together or none do. May span
 * collections. Not thread-safe.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface WriteBatch extends AutoCloseable {

    /** Queues an insert; the OID and version are set on the object at commit. */
    WriteBatch insert(Object entity);

    /** Queues an update, checked against the object's {@code @Version} if it has one. */
    WriteBatch update(Object entity);

    /** Queues a delete, checked against the object's {@code @Version} if it has one. */
    WriteBatch delete(Object entity);

    /** Queues an unconditional delete by OID. */
    WriteBatch delete(Class<?> type, long oid);

    /** Queues an unconditional delete by OID, whatever the object's class. */
    WriteBatch delete(long oid);

    int size();

    /** Applies all queued writes atomically. */
    void commit();

    /**
     * Durability of this batch's commit, overriding {@code kookaburra.durability} (null = the database's): {@code ASYNC}
     * for bulk loads into a {@code SYNC} database, {@code SYNC} for an important write in an {@code ASYNC} one.
     */
    default WriteBatch durability(Durability durability) {
        throw new UnsupportedOperationException("this batch commits with its transaction");
    }

    /** Discards uncommitted writes. */
    @Override
    void close();
}
