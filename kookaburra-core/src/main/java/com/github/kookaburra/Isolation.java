package com.github.kookaburra;

/**
 * Transaction isolation levels (docs/transaction-design.md §5), weakest first. Every level reads committed data
 * only, sees the transaction's own writes and fails the commit with {@link ConflictException} when an object it
 * writes was changed by a concurrent commit.
 *
 * <table>
 *   <caption>Anomalies prevented</caption>
 *   <tr><th>Level</th><th>dirty read</th><th>non-repeatable read</th><th>phantom</th><th>lost update</th><th>write skew</th></tr>
 *   <tr><td>{@link #READ_COMMITTED}</td><td>yes</td><td>no</td><td>no</td><td>yes</td><td>no</td></tr>
 *   <tr><td>{@link #SNAPSHOT}</td><td>yes</td><td>yes</td><td>yes</td><td>yes</td><td>no</td></tr>
 *   <tr><td>{@link #SERIALIZABLE}</td><td>yes</td><td>yes</td><td>yes</td><td>yes</td><td>yes</td></tr>
 * </table>
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public enum Isolation {
    /**
     * Every statement (get, query, OQL) sees the latest committed state when it starts, plus the transaction's own
     * writes (the default of PostgreSQL, Oracle and SQL Server). A write conflicts only with commits after the
     * statement that read the object, so long transactions abort less; repeated reads may differ.
     */
    READ_COMMITTED,
    /**
     * Snapshot isolation: reads see the database as of {@code begin()} plus the transaction's own writes; a write
     * to an object another transaction committed after the snapshot fails the commit (PostgreSQL's REPEATABLE READ,
     * MongoDB / SQL Server SNAPSHOT). Write skew is possible.
     */
    SNAPSHOT,
    /**
     * SNAPSHOT plus read validation: at commit, a transaction that writes fails with {@link ConflictException} if
     * a concurrent commit changed anything it read (objects read by OID, and objects matching or no longer matching
     * the conditions of its queries, phantoms included). The result equals some serial order of the transactions.
     * Read-only transactions never fail.
     */
    SERIALIZABLE
}
