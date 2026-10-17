package com.github.kookaburra.engine;

import com.github.kookaburra.DatabaseStats;
import com.github.kookaburra.Isolation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cumulative counters behind {@link DatabaseStats}. Incremented on the write paths without locks
 * ({@link LongAdder}); read only when statistics are taken.
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
class Metrics {

    final LongAdder batches = new LongAdder();
    final LongAdder rejected = new LongAdder();
    final LongAdder inserts = new LongAdder();
    final LongAdder updates = new LongAdder();
    final LongAdder deletes = new LongAdder();
    final LongAdder groupCommits = new LongAdder();
    final LongAdder backPressureWaits = new LongAdder();
    final LongAdder backPressureMillis = new LongAdder();

    final LongAdder retries = new LongAdder();
    private final Map<Isolation, LongAdder[]> tx = new EnumMap<>(Isolation.class);

    final LongAdder uniqueViolations = new LongAdder();
    final LongAdder foreignKeyViolations = new LongAdder();
    final LongAdder cascadeDeletes = new LongAdder();
    final LongAdder setNulls = new LongAdder();

    final LongAdder flushes = new LongAdder();
    final LongAdder flushedRows = new LongAdder();
    final LongAdder flushedBytes = new LongAdder();
    final LongAdder flushNanos = new LongAdder();
    final LongAdder compactions = new LongAdder();
    final LongAdder compactedSegments = new LongAdder();
    final LongAdder compactedBytes = new LongAdder();
    final LongAdder compactionNanos = new LongAdder();
    final LongAdder backups = new LongAdder();

    /** Transaction outcome indexes. */
    static final int COMMITTED = 0;
    static final int ROLLED_BACK = 1;
    static final int CONFLICT = 2;
    static final int TIMEOUT = 3;

    Metrics() {
        for (Isolation i : Isolation.values()) {
            tx.put(i, new LongAdder[]{new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()});
        }
    }

    void tx(Isolation isolation, int outcome) {
        tx.get(isolation)[outcome].increment();
    }

    DatabaseStats.Writes writes(long walReplayed) {
        return new DatabaseStats.Writes(batches.sum(), rejected.sum(), inserts.sum(), updates.sum(), deletes.sum(),
                groupCommits.sum(), walReplayed, backPressureWaits.sum(), backPressureMillis.sum());
    }

    DatabaseStats.Transactions transactions(int active) {
        Map<Isolation, DatabaseStats.Outcomes> out = new EnumMap<>(Isolation.class);
        tx.forEach((i, c) -> out.put(i, new DatabaseStats.Outcomes(c[COMMITTED].sum(), c[ROLLED_BACK].sum(),
                c[CONFLICT].sum(), c[TIMEOUT].sum())));
        return new DatabaseStats.Transactions(active, retries.sum(), Collections.unmodifiableMap(out));
    }

    DatabaseStats.Constraints constraints() {
        return new DatabaseStats.Constraints(uniqueViolations.sum(), foreignKeyViolations.sum(), cascadeDeletes.sum(),
                setNulls.sum());
    }

    DatabaseStats.Maintenance maintenance(int pending) {
        return new DatabaseStats.Maintenance(flushes.sum(), flushedRows.sum(), flushedBytes.sum(),
                flushNanos.sum() / 1_000_000, compactions.sum(), compactedSegments.sum(), compactedBytes.sum(),
                compactionNanos.sum() / 1_000_000, pending, backups.sum());
    }
}
