package com.github.kookaburra;

/**
 * A write would break a {@link com.github.kookaburra.annotation.Unique} or
 * {@link com.github.kookaburra.annotation.ForeignKey} constraint; nothing of the batch or transaction was written.
 * Not retried by {@link ObjectDB#inTransaction}: retrying the same writes would fail the same way.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
public class ConstraintViolationException extends KookaburraException {

    /** Constraint kinds. */
    public enum Kind { UNIQUE, FOREIGN_KEY }

    private final Kind kind;
    private final String className;
    private final String field;
    private final long oid;

    /**
     * @param className class declaring the constrained field
     * @param field     dotted path of the field
     * @param oid       the object that violates it (UNIQUE: the existing holder of the value; FOREIGN_KEY on
     *                  delete: a referencing object; on insert / update: the missing target)
     */
    public ConstraintViolationException(Kind kind, String className, String field, long oid, String message) {
        super(message);
        this.kind = kind;
        this.className = className;
        this.field = field;
        this.oid = oid;
    }

    public Kind kind() {
        return kind;
    }

    public String className() {
        return className;
    }

    public String field() {
        return field;
    }

    public long oid() {
        return oid;
    }
}
