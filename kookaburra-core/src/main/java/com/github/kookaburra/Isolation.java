package com.github.kookaburra;

/**
 * Transaction isolation levels (docs/transaction-design.md §5).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public enum Isolation {
    /**
     * Snapshot isolation: reads see the database as of {@code begin()} plus the transaction's own writes; a write
     * to an object another transaction committed after the snapshot fails the commit. Write skew is possible.
     */
    SNAPSHOT
}
