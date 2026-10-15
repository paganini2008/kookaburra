package com.github.kookaburra;

/**
 * A write lost against a concurrent commit (first committer wins); retrying the whole unit of work usually
 * succeeds. {@link ObjectDB#inTransaction(TxWork)} retries automatically.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public class ConflictException extends KookaburraException {

    private final long oid;

    public ConflictException(String message) {
        this(-1, message);
    }

    public ConflictException(long oid, String message) {
        super(message);
        this.oid = oid;
    }

    /** The object that conflicted, or -1 when the conflict is not about one object. */
    public long oid() {
        return oid;
    }
}
