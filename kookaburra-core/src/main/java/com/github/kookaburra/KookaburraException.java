package com.github.kookaburra;

/**
 * Base class of database errors.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class KookaburraException extends RuntimeException {
    public KookaburraException(String message) {
        super(message);
    }

    public KookaburraException(String message, Throwable cause) {
        super(message, cause);
    }
}
