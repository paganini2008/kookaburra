package com.github.kookaburra.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Open transactions and their snapshots (docs/transaction-design.md §8, §11.2). Transactions register before
 * taking their snapshot, so the sequencer never prunes a change a registered transaction may conflict with.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class TxRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TxRegistry.class);

    /** Snapshot value of an entry that has registered but not read {@code visibleSeq} yet. */
    static final long PENDING = -1;

    /** One open transaction as seen by the engine. */
    static final class Entry {
        final long id;
        final long startNanos;
        /** Timeout in nanoseconds, 0 = none. */
        final long timeoutNanos;
        final String thread;
        volatile long snapshot = PENDING;
        /** Non-null once the transaction can no longer commit: timed out or evicted. */
        volatile String abortReason;
        volatile boolean timedOut;
        volatile int writes;
        private boolean warned;

        Entry(long id, long timeoutNanos) {
            this.id = id;
            this.startNanos = System.nanoTime();
            this.timeoutNanos = timeoutNanos;
            this.thread = Thread.currentThread().getName();
        }

        boolean expired(long now) {
            return timeoutNanos > 0 && now - startNanos > timeoutNanos;
        }

        long ageMillis() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }
    }

    private final ConcurrentHashMap<Long, Entry> active = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    Entry register(long timeoutNanos) {
        Entry e = new Entry(ids.incrementAndGet(), timeoutNanos);
        active.put(e.id, e);
        return e;
    }

    void deregister(Entry e) {
        active.remove(e.id);
    }

    int size() {
        return active.size();
    }

    /**
     * Oldest snapshot any live transaction may still read ({@code fallback} when none): changes up to it can be
     * forgotten. Marks timed-out transactions as aborted (they no longer hold history back).
     */
    long horizon(long fallback) {
        long now = System.nanoTime();
        long min = fallback;
        for (Entry e : active.values()) {
            if (e.abortReason != null) {
                continue;
            }
            if (e.expired(now)) {
                e.timedOut = true;
                e.abortReason = "transaction " + e.id + " timed out after " + e.ageMillis() + " ms";
                LOG.warn("transaction {} ({}) rolled back: open longer than its timeout", e.id, e.thread);
                continue;
            }
            if (!e.warned && e.timeoutNanos > 0 && now - e.startNanos > e.timeoutNanos / 2) {
                e.warned = true;
                LOG.warn("long transaction {} on thread {}: open {} ms, {} writes", e.id, e.thread, e.ageMillis(), e.writes);
            }
            min = Math.min(min, e.snapshot == PENDING ? 0 : e.snapshot);
        }
        return min;
    }

    /** Aborts the live transaction with the oldest snapshot (conflict-table overflow); false if none. */
    boolean evictOldest(String reason) {
        Entry oldest = null;
        for (Entry e : active.values()) {
            if (e.abortReason == null && e.snapshot != PENDING && (oldest == null || e.snapshot < oldest.snapshot)) {
                oldest = e;
            }
        }
        if (oldest == null) {
            return false;
        }
        oldest.abortReason = "transaction " + oldest.id + " evicted: " + reason;
        LOG.warn("transaction {} ({}) evicted: {}", oldest.id, oldest.thread, reason);
        return true;
    }
}
