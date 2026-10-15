package com.github.kookaburra;

/**
 * The version of an object does not match: it was changed (or deleted) since it was read.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class OptimisticLockException extends ConflictException {

    public OptimisticLockException(long oid, String message) {
        super(oid, message);
    }
}
