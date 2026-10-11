package com.github.kookaburra;

/**
 * An update or delete found a different version (or no object) than expected. Reload and retry.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class OptimisticLockException extends KookaburraException {

    private final long oid;

    public OptimisticLockException(long oid, String message) {
        super(message);
        this.oid = oid;
    }

    public long oid() {
        return oid;
    }
}
