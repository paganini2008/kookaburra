package com.github.kookaburra;

import java.time.Duration;

/**
 * Options of {@link ObjectDB#begin(TxOptions)}; immutable.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public final class TxOptions {

    private static final TxOptions DEFAULTS = new TxOptions(Isolation.SNAPSHOT, false, null, null);

    private final Isolation isolation;
    private final boolean readOnly;
    private final Duration timeout;
    private final Durability durability;

    private TxOptions(Isolation isolation, boolean readOnly, Duration timeout, Durability durability) {
        if (isolation == null) {
            throw new IllegalArgumentException("isolation must not be null");
        }
        if (timeout != null && timeout.isNegative()) {
            throw new IllegalArgumentException("negative timeout");
        }
        this.isolation = isolation;
        this.readOnly = readOnly;
        this.timeout = timeout;
        this.durability = durability;
    }

    /** SNAPSHOT, read-write, timeout from {@code kookaburra.tx-timeout} ({@code db.begin()} uses {@code kookaburra.tx-isolation}). */
    public static TxOptions defaults() {
        return DEFAULTS;
    }

    /** A read-only SNAPSHOT transaction: writes fail, commit has nothing to validate. */
    public static TxOptions readOnly() {
        return DEFAULTS.readOnly(true);
    }

    public TxOptions isolation(Isolation v) {
        return new TxOptions(v, readOnly, timeout, durability);
    }

    public TxOptions readOnly(boolean v) {
        return new TxOptions(isolation, v, timeout, durability);
    }

    /** Overrides {@code kookaburra.tx-timeout}; {@link Duration#ZERO} = no limit. */
    public TxOptions timeout(Duration v) {
        return new TxOptions(isolation, readOnly, v, durability);
    }

    /**
     * Durability of this transaction's commit, overriding {@code kookaburra.durability} (null = the database's), as
     * PostgreSQL's per-transaction {@code synchronous_commit}: {@code ASYNC} returns once the commit is written to the
     * operating system (a power failure may lose the last such commits, never a part of one); {@code SYNC} waits for
     * the disk even in an {@code ASYNC} database.
     */
    public TxOptions durability(Durability v) {
        return new TxOptions(isolation, readOnly, timeout, v);
    }

    public Isolation isolation() {
        return isolation;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /** The timeout, or null for the configured default. */
    public Duration timeout() {
        return timeout;
    }

    /** The commit durability, or null for the database's ({@code kookaburra.durability}). */
    public Durability durability() {
        return durability;
    }

    @Override
    public String toString() {
        return isolation + (readOnly ? " READ ONLY" : "") + (timeout == null ? "" : " timeout=" + timeout)
                + (durability == null ? "" : " " + durability);
    }
}
