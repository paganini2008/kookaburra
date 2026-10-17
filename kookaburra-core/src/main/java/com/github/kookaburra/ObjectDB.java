package com.github.kookaburra;

import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.engine.Database;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.oql.OqlQuery;
import com.github.kookaburra.query.QueryStream;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * An embedded object database. Thread-safe; open one instance per directory per process.
 *
 * <pre>{@code
 * try (ObjectDB db = ObjectDB.open(Path.of("db"))) {
 *     long oid = db.insert(new Person("Ann", "Sydney", 30));
 *     List<Person> ps = db.from(Person.class).filter(where(Person::getAge).gt(20)).toList();
 *     db.inTransaction(tx -> {
 *         Person p = tx.get(Person.class, oid);
 *         p.setAge(31);
 *         tx.update(p);
 *     });
 * }
 * }</pre>
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface ObjectDB extends AutoCloseable {

    /** Opens (or creates) a database directory with default settings. */
    static ObjectDB open(Path dir) {
        return open(dir, Config.defaults());
    }

    static ObjectDB open(Path dir, Config config) {
        return Database.open(dir, config);
    }

    /** A database that lives only in memory. */
    static ObjectDB inMemory() {
        return inMemory(Config.defaults());
    }

    static ObjectDB inMemory(Config config) {
        return Database.open(null, config);
    }

    /** Inserts a new object; sets and returns its OID and sets its version to 1. */
    long insert(Object entity);

    /** Stores a modified object; checks and increments its {@code @Version} if it has one. */
    void update(Object entity);

    /** Deletes an object; checks its {@code @Version} if it has one. */
    void delete(Object entity);

    /** Deletes by OID without a version check; returns whether an object was deleted. */
    boolean delete(Class<?> type, long oid);

    WriteBatch batch();

    /** Atomically adds {@code delta} to a numeric field of an object, on its latest value (never conflicts). */
    void increment(Class<?> type, long oid, String field, Number delta);

    // ---------------------------------------------------------------- transactions (docs/transaction-design.md)

    /** Starts a transaction with the configured isolation ({@code kookaburra.tx-isolation}, default SNAPSHOT); close it (try-with-resources): an uncommitted one is rolled back. */
    Transaction begin();

    /** {@code begin(TxOptions.defaults().isolation(isolation))}. */
    Transaction begin(Isolation isolation);

    Transaction begin(TxOptions options);

    /** Runs {@code work} in a transaction and commits; retries on {@link ConflictException} ({@code kookaburra.tx-retries}). */
    void inTransaction(TxWork work);

    void inTransaction(TxWork work, int retries);

    /**
     * Like {@link #inTransaction(TxWork)}, returning the body's result (a separate name, so that
     * {@code tx -> tx.insert(x)} is not ambiguous between the two forms).
     */
    <R> R inTransactionResult(TxFunction<R> work);

    <R> R inTransactionResult(TxFunction<R> work, int retries);

    /** {@link #inTransaction(TxWork)} with explicit options, e.g. {@code TxOptions.defaults().isolation(SERIALIZABLE)}. */
    void inTransaction(TxOptions options, TxWork work);

    <R> R inTransactionResult(TxOptions options, TxFunction<R> work);

    /** Number of open transactions. */
    int activeTransactions();

    // ---------------------------------------------------------------- lifecycle (see LifecycleEvent)

    /** Registers a listener for entities of {@code type} and its subclasses; listeners run in registration order. */
    <T> void addListener(Class<T> type, EntityListener<? super T> listener);

    /** Removes a listener; false if it was not registered for {@code type}. */
    <T> boolean removeListener(Class<T> type, EntityListener<? super T> listener);

    /** The object with this OID, whatever its class; null if it does not exist. */
    Object get(long oid);

    /** The object with this OID if it is an instance of {@code type}, else null. */
    <T> T get(Class<T> type, long oid);

    <T> Map<Long, T> getAll(Class<T> type, Collection<Long> oids);

    /** Query over {@code type} and its subclasses. */
    <T> QueryStream<T> from(Class<T> type);

    /**
     * Backlinks: every object that references {@code oid} through a reference path of any class ({@code Ref} fields,
     * collections of refs, refs inside embedded objects and embedded lists). Indexed paths (and every
     * {@code @ForeignKey}) are answered from the index, the others by a scan. Schema-driven, so no entity class is
     * needed.
     */
    List<DynamicObject> referrers(long oid);

    /** The referrers of {@code oid} that are instances of {@code type}, as entities. */
    <T> List<T> referrers(Class<T> type, long oid);

    /** Same as {@link #from(Class)}, for the fluent style {@code query(Order.class).where(Order::getOwner).eq(oid).list()}. */
    default <T> QueryStream<T> query(Class<T> type) {
        return from(type);
    }

    Snapshot snapshot();

    /** An OQL statement (JPQL-style, SQL-92 semantics); see {@link com.github.kookaburra.oql.OqlQuery}. */
    OqlQuery<Object> oql(String text);

    /** An OQL statement whose {@code list()} returns {@code resultType}. */
    <T> OqlQuery<T> oql(String text, Class<T> resultType);

    /** Schema-driven access to a stored class (fully qualified or unique simple name); no entity class needed. */
    DynamicCollection dynamic(String className);

    /**
     * Online backup: writes the database as of one snapshot (every commit up to it) into {@code target}, a new or
     * empty directory, as compacted segment files. Reads and writes continue meanwhile; an in-memory database is
     * backed up to disk the same way. Restore = open the backup directory.
     */
    BackupInfo backup(Path target);

    /** Turns all in-memory segments into immutable segments and waits until they are installed. */
    void flush();

    /** Flushes, then merges the segments of every collection (up to the segment size limit) and waits. */
    void compact();

    Schema schema();

    /** Path of the database directory, or null for an in-memory database. */
    Path path();

    @Override
    void close();
}
