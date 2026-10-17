package com.github.kookaburra;

import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.oql.OqlQuery;
import com.github.kookaburra.query.QueryStream;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A snapshot-isolated unit of work (docs/transaction-design.md). Reads see the database as of {@code begin()}
 * plus this transaction's own writes; writes are buffered until {@link #commit()}. A call that throws has no
 * effect and the transaction stays usable. Not thread-safe: use it from one thread at a time.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public interface Transaction extends AutoCloseable {

    /** Lifecycle states. */
    enum State { ACTIVE, COMMITTED, ROLLED_BACK }

    long id();

    /** Sequence number of the snapshot the transaction reads. */
    long snapshotSeq();

    Isolation isolation();

    boolean isReadOnly();

    State state();

    /** Objects with buffered (uncommitted) changes. */
    int pendingWrites();

    // ---------------------------------------------------------------- reads (see own writes)

    <T> T get(Class<T> type, long oid);

    Object get(long oid);

    <T> Map<Long, T> getAll(Class<T> type, Collection<Long> oids);

    <T> QueryStream<T> from(Class<T> type);

    /** Same as {@link #from(Class)}, for the fluent style {@code query(Order.class).where(Order::getOwner).eq(oid).list()}. */
    default <T> QueryStream<T> query(Class<T> type) {
        return from(type);
    }

    OqlQuery<Object> oql(String text);

    <T> OqlQuery<T> oql(String text, Class<T> resultType);

    DynamicCollection dynamic(String className);

    /** {@link ObjectDB#referrers(long)} as seen by this transaction (its own writes included). */
    List<DynamicObject> referrers(long oid);

    <T> List<T> referrers(Class<T> type, long oid);

    // ---------------------------------------------------------------- writes (buffered)

    /** Inserts; the OID is assigned (and set on the entity) immediately. */
    long insert(Object entity);

    /** Updates; fails at once with {@link OptimisticLockException} if the entity's version is stale. */
    void update(Object entity);

    void delete(Object entity);

    /** Deletes by OID without a version check; returns whether an object was visible to delete. */
    boolean delete(Class<?> type, long oid);

    /** Adds {@code delta} to a numeric field at commit, on the latest value; increments never conflict. */
    void increment(Class<?> type, long oid, String field, Number delta);

    // ---------------------------------------------------------------- control

    /** Validates and stores the writes atomically; {@link ConflictException} if a concurrent commit won. */
    void commit();

    /** Discards the writes. */
    void rollback();

    /** Rolls back if still active. */
    @Override
    void close();
}
