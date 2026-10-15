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

    private static final TxOptions DEFAULTS = new TxOptions(Isolation.SNAPSHOT, false, null);

    private final Isolation isolation;
    private final boolean readOnly;
    private final Duration timeout;

    private TxOptions(Isolation isolation, boolean readOnly, Duration timeout) {
        if (isolation == null) {
            throw new IllegalArgumentException("isolation must not be null");
        }
        if (timeout != null && timeout.isNegative()) {
            throw new IllegalArgumentException("negative timeout");
        }
        this.isolation = isolation;
        this.readOnly = readOnly;
        this.timeout = timeout;
    }

    /** SNAPSHOT, read-write, timeout from {@code kookaburra.tx-timeout}. */
    public static TxOptions defaults() {
        return DEFAULTS;
    }

    /** A read-only SNAPSHOT transaction: writes fail, commit has nothing to validate. */
    public static TxOptions readOnly() {
        return DEFAULTS.readOnly(true);
    }

    public TxOptions isolation(Isolation v) {
        return new TxOptions(v, readOnly, timeout);
    }

    public TxOptions readOnly(boolean v) {
        return new TxOptions(isolation, v, timeout);
    }

    /** Overrides {@code kookaburra.tx-timeout}; {@link Duration#ZERO} = no limit. */
    public TxOptions timeout(Duration v) {
        return new TxOptions(isolation, readOnly, v);
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

    @Override
    public String toString() {
        return isolation + (readOnly ? " READ ONLY" : "") + (timeout == null ? "" : " timeout=" + timeout);
    }
}
