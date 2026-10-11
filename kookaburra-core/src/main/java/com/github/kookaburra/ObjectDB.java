package com.github.kookaburra;

import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.engine.Database;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.oql.OqlQuery;
import com.github.kookaburra.query.QueryStream;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * An embedded object database. Thread-safe; open one instance per directory per process.
 *
 * <pre>{@code
 * try (ObjectDB db = ObjectDB.open(Path.of("db"))) {
 *     long oid = db.insert(new Person("Ann", "Sydney", 30));
 *     List<Person> ps = db.from(Person.class).filter(where(Person::getAge).gt(20)).toList();
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

    /** The object with this OID, whatever its class; null if it does not exist. */
    Object get(long oid);

    /** The object with this OID if it is an instance of {@code type}, else null. */
    <T> T get(Class<T> type, long oid);

    <T> Map<Long, T> getAll(Class<T> type, Collection<Long> oids);

    /** Query over {@code type} and its subclasses. */
    <T> QueryStream<T> from(Class<T> type);

    Snapshot snapshot();

    /** An OQL statement (JPQL-style, SQL-92 semantics); see {@link com.github.kookaburra.oql.OqlQuery}. */
    OqlQuery<Object> oql(String text);

    /** An OQL statement whose {@code list()} returns {@code resultType}. */
    <T> OqlQuery<T> oql(String text, Class<T> resultType);

    /** Schema-driven access to a stored class (fully qualified or unique simple name); no entity class needed. */
    DynamicCollection dynamic(String className);

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
