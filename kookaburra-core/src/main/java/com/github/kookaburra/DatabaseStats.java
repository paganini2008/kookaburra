package com.github.kookaburra;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time statistics of a database ({@link ObjectDB#stats()}), for monitoring. Counters are cumulative since
 * the database was opened; sizes and object counts reflect one snapshot. No metrics library is needed: an
 * application reads this record periodically and exports what it wants (Micrometer, Dropwizard, JMX, logs).
 *
 * @param openedAt     when the database was opened (UTC)
 * @param takenAt      when these statistics were taken (UTC)
 * @param seq          sequence number of the last visible commit
 * @param collections  per collection (root class and its subclasses): objects and storage, by name
 * @param writes       write counters
 * @param transactions transaction counters
 * @param constraints  constraint counters
 * @param maintenance  flush, compaction and backup counters
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
public record DatabaseStats(Instant openedAt, Instant takenAt, long seq, List<CollectionStats> collections,
                            Writes writes, Transactions transactions, Constraints constraints, Maintenance maintenance) {

    /** Live objects of all collections. */
    public long objects() {
        return collections.stream().mapToLong(CollectionStats::objects).sum();
    }

    /** Bytes of all segment files (or in-memory segment images). */
    public long segmentBytes() {
        return collections.stream().mapToLong(CollectionStats::segmentBytes).sum();
    }

    /** Statistics of one collection, or null. */
    public CollectionStats collection(String name) {
        return collections.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * One collection.
     *
     * @param name         root class name
     * @param objects      live objects at the snapshot
     * @param segments     segment files
     * @param segmentBytes bytes of those files
     * @param deletedRows  deleted or superseded rows still stored in segments (reclaimed by compaction)
     * @param memRows      rows not yet flushed into a segment (all versions)
     * @param memBytes     encoded bytes of those rows
     */
    public record CollectionStats(String name, long objects, int segments, long segmentBytes, long deletedRows,
                                  long memRows, long memBytes) {
    }

    /**
     * Writes (autocommit, batches and transaction commits alike).
     *
     * @param batches            atomic batches committed
     * @param rejected           batches rejected (optimistic lock, conflict, constraint, invalid input)
     * @param inserts            objects inserted
     * @param updates            objects updated (including increments and SET_NULL rewrites)
     * @param deletes            objects deleted (including cascades)
     * @param groupCommits       write groups made durable together (one fsync each with {@code SYNC})
     * @param walReplayed        log records replayed when the database was opened
     * @param backPressureWaits  writes that waited for flushes or compactions to catch up
     * @param backPressureMillis total time those writes waited
     */
    public record Writes(long batches, long rejected, long inserts, long updates, long deletes, long groupCommits,
                         long walReplayed, long backPressureWaits, long backPressureMillis) {

        /** Average batches per durable group (the group commit effect). */
        public double batchesPerGroup() {
            return groupCommits == 0 ? 0 : (double) (batches + rejected) / groupCommits;
        }
    }

    /**
     * Transactions.
     *
     * @param active      open transactions
     * @param retries     retries made by {@code inTransaction} after conflicts
     * @param byIsolation outcomes per isolation level (every level present)
     */
    public record Transactions(int active, long retries, Map<Isolation, Outcomes> byIsolation) {

        /** Outcomes over all isolation levels. */
        public Outcomes total() {
            long c = 0;
            long r = 0;
            long x = 0;
            long t = 0;
            for (Outcomes o : byIsolation.values()) {
                c += o.committed();
                r += o.rolledBack();
                x += o.conflicts();
                t += o.timeouts();
            }
            return new Outcomes(c, r, x, t);
        }
    }

    /**
     * Ends of transactions of one isolation level.
     *
     * @param committed  committed
     * @param rolledBack rolled back, explicitly or because the commit failed
     * @param conflicts  commits that failed with a {@link ConflictException} (write conflict or serialization failure)
     * @param timeouts   transactions ended by their timeout ({@link TransactionTimeoutException})
     */
    public record Outcomes(long committed, long rolledBack, long conflicts, long timeouts) {
    }

    /**
     * Constraints.
     *
     * @param uniqueViolations     batches rejected by {@code @Unique}
     * @param foreignKeyViolations batches rejected by {@code @ForeignKey} (missing target or RESTRICT)
     * @param cascadeDeletes       deletes added by {@code CASCADE}
     * @param setNulls             updates added by {@code SET_NULL}
     */
    public record Constraints(long uniqueViolations, long foreignKeyViolations, long cascadeDeletes, long setNulls) {
    }

    /**
     * Background work.
     *
     * @param flushes           in-memory rows written into new segments
     * @param flushedRows       rows written by flushes
     * @param flushedBytes      bytes written by flushes
     * @param flushMillis       time spent in flushes
     * @param compactions       merges and rewrites finished
     * @param compactedSegments input segments of those merges (including dropped ones)
     * @param compactedBytes    bytes written by merges
     * @param compactionMillis  time spent in merges
     * @param pending           flushes and merges scheduled but not finished
     * @param backups           online backups taken
     */
    public record Maintenance(long flushes, long flushedRows, long flushedBytes, long flushMillis, long compactions,
                              long compactedSegments, long compactedBytes, long compactionMillis, int pending,
                              long backups) {
    }
}
