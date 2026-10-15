package com.github.kookaburra;

/**
 * Body of {@link ObjectDB#inTransactionResult(TxFunction)} returning a value; may run more than once.
 *
 * @param <R> result type
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@FunctionalInterface
public interface TxFunction<R> {
    R apply(Transaction tx);
}
