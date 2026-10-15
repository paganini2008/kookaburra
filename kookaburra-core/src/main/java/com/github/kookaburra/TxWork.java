package com.github.kookaburra;

/**
 * Body of {@link ObjectDB#inTransaction(TxWork)}; may run more than once, so it must not have side effects
 * outside the transaction.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@FunctionalInterface
public interface TxWork {
    void run(Transaction tx);
}
