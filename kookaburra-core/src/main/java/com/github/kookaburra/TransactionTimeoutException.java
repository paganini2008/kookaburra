package com.github.kookaburra;

/**
 * The transaction was open longer than its timeout ({@code kookaburra.tx-timeout}) and has been rolled back.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public class TransactionTimeoutException extends KookaburraException {

    public TransactionTimeoutException(String message) {
        super(message);
    }
}
