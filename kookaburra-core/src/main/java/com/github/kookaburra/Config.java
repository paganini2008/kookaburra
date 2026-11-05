package com.github.kookaburra;

import com.github.kookaburra.embed.Embedder;
import com.github.kookaburra.embed.Reranker;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Database settings (design §16). Defaults come from {@code kookaburra.properties}; immutable, use {@code with*}.
 *
 * @param memSegmentBytes   size at which the active in-memory segment is frozen and flushed
 * @param segmentMaxBytes   upper bound of an on-disk segment
 * @param compactionFanIn   number of similar-size segments merged at once
 * @param maxSegments       segments per collection above which writers are throttled
 * @param bloomFpp          bloom filter false-positive rate
 * @param durability        write durability
 * @param groupCommitMicros time the sequencer waits to gather more writes into one fsync
 * @param sequencerMaxGroup most write batches applied per WAL fsync
 * @param autoPostingsRatio AUTO index: postings when cardinality &lt;= documents / ratio
 * @param postingsOrMax     range lookups OR at most this many postings, else scan ordinals
 * @param writeBacklog      pending flushes + merges above which writers wait
 * @param transactions      enables multi-statement transactions; off (default) = every write commits directly
 * @param txTimeoutMillis   open transactions older than this are rolled back (0 = no limit)
 * @param txMaxBytes        encoded bytes in one transaction's write set
 * @param txRetries         retries of {@code inTransaction} on a conflict
 * @param txIsolation       isolation level of {@code begin()} / {@code inTransaction} without explicit options
 * @param txConflictTableMax conflict-table entries before the oldest transaction is evicted
 * @param flushThreads      threads for flushes and compactions
 * @param compactionGarbageRatio a segment whose deleted / total rows reaches this ratio is rewritten on its own (0 &lt; r &lt;= 1)
 * @param vectorM           HNSW graph: neighbours per node on the upper layers (twice as many on the bottom layer)
 * @param vectorEfConstruction HNSW graph: candidate list size while inserting a vector
 * @param vectorEfSearch    approximate nearest-neighbour search: candidates per segment (at least k)
 * @param vectorIndexMinRows segments with fewer vectors than this get no graph (an exact scan is as fast)
 * @param embedder          turns text into vectors ({@code kookaburra.embedder}), or null
 * @param reranker          re-scores search candidates against the query text ({@code kookaburra.reranker}), or null
 * @param classLoader       loader for entity classes found in the schema, or null for the default
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record Config(long memSegmentBytes, long segmentMaxBytes, int compactionFanIn, int maxSegments, double bloomFpp, Durability durability, long groupCommitMicros, int sequencerMaxGroup, int autoPostingsRatio, int postingsOrMax, int writeBacklog, boolean transactions, long txTimeoutMillis, long txMaxBytes, int txRetries, Isolation txIsolation, int txConflictTableMax, int flushThreads, double compactionGarbageRatio, int vectorM, int vectorEfConstruction, int vectorEfSearch, int vectorIndexMinRows, Embedder embedder, Reranker reranker, ClassLoader classLoader) {

    /** Keys of {@code kookaburra.properties} (the only place their defaults are defined). */
    public static final String MEM_SEGMENT_BYTES = "kookaburra.mem-segment-bytes";
    public static final String SEGMENT_MAX_BYTES = "kookaburra.segment-max-bytes";
    public static final String COMPACTION_FAN_IN = "kookaburra.compaction-fan-in";
    public static final String MAX_SEGMENTS = "kookaburra.max-segments";
    public static final String BLOOM_FPP = "kookaburra.bloom-fpp";
    public static final String DURABILITY = "kookaburra.durability";
    public static final String GROUP_COMMIT_MICROS = "kookaburra.group-commit-micros";
    public static final String SEQUENCER_MAX_GROUP = "kookaburra.sequencer-max-group";
    public static final String AUTO_POSTINGS_RATIO = "kookaburra.auto-postings-ratio";
    public static final String POSTINGS_OR_MAX = "kookaburra.postings-or-max";
    public static final String WRITE_BACKLOG = "kookaburra.write-backlog";
    public static final String TRANSACTIONS = "kookaburra.transactions";
    public static final String TX_TIMEOUT = "kookaburra.tx-timeout";
    public static final String TX_MAX_BYTES = "kookaburra.tx-max-bytes";
    public static final String TX_RETRIES = "kookaburra.tx-retries";
    public static final String TX_ISOLATION = "kookaburra.tx-isolation";
    public static final String TX_CONFLICT_TABLE_MAX = "kookaburra.tx-conflict-table-max";
    public static final String FLUSH_THREADS = "kookaburra.flush-threads";
    public static final String COMPACTION_GARBAGE_RATIO = "kookaburra.compaction-garbage-ratio";
    public static final String VECTOR_M = "kookaburra.vector-m";
    public static final String VECTOR_EF_CONSTRUCTION = "kookaburra.vector-ef-construction";
    public static final String VECTOR_EF_SEARCH = "kookaburra.vector-ef-search";
    public static final String VECTOR_INDEX_MIN_ROWS = "kookaburra.vector-index-min-rows";

    public Config {
        if (memSegmentBytes < 1024 || segmentMaxBytes < memSegmentBytes || segmentMaxBytes > 1_500_000_000L) {
            throw new IllegalArgumentException("invalid segment sizes");
        }
        if (compactionFanIn < 2 || maxSegments < compactionFanIn || bloomFpp <= 0 || bloomFpp >= 1
                || groupCommitMicros < 0 || durability == null || sequencerMaxGroup < 1 || autoPostingsRatio < 1
                || postingsOrMax < 1 || writeBacklog < 1 || txTimeoutMillis < 0 || txMaxBytes < 1 || txRetries < 0
                || txIsolation == null || txConflictTableMax < 1 || flushThreads < 1 || !(compactionGarbageRatio > 0 && compactionGarbageRatio <= 1)
                || vectorM < 2 || vectorEfConstruction < 1 || vectorEfSearch < 1 || vectorIndexMinRows < 0) {
            throw new IllegalArgumentException("invalid config");
        }
    }

    /** The bundled defaults of {@code kookaburra.properties}. */
    public static Config defaults() {
        return fromSettings(Map.of());
    }

    /** Bundled defaults, then {@code ./kookaburra.properties}, then {@code -Dkookaburra.*} system properties. */
    public static Config load() {
        return load(Path.of(Settings.FILE));
    }

    /** Bundled defaults, then the given settings file, then {@code -Dkookaburra.*} system properties. */
    public static Config load(Path file) {
        return fromSettings(Settings.load(file));
    }

    /** Bundled defaults overridden by {@code settings}; sizes accept KB / MB / GB suffixes. */
    public static Config fromSettings(Map<String, String> settings) {
        return new Config(
                bytes(Settings.get(settings, MEM_SEGMENT_BYTES), MEM_SEGMENT_BYTES),
                bytes(Settings.get(settings, SEGMENT_MAX_BYTES), SEGMENT_MAX_BYTES),
                Settings.integer(settings, COMPACTION_FAN_IN),
                Settings.integer(settings, MAX_SEGMENTS),
                number(Settings.get(settings, BLOOM_FPP), BLOOM_FPP),
                durability(Settings.get(settings, DURABILITY)),
                Settings.integer(settings, GROUP_COMMIT_MICROS),
                Settings.integer(settings, SEQUENCER_MAX_GROUP),
                Settings.integer(settings, AUTO_POSTINGS_RATIO),
                Settings.integer(settings, POSTINGS_OR_MAX),
                Settings.integer(settings, WRITE_BACKLOG),
                bool(Settings.get(settings, TRANSACTIONS), TRANSACTIONS),
                millis(Settings.get(settings, TX_TIMEOUT), TX_TIMEOUT),
                bytes(Settings.get(settings, TX_MAX_BYTES), TX_MAX_BYTES),
                Settings.integer(settings, TX_RETRIES),
                isolation(Settings.get(settings, TX_ISOLATION)),
                Settings.integer(settings, TX_CONFLICT_TABLE_MAX),
                Settings.integer(settings, FLUSH_THREADS),
                number(Settings.get(settings, COMPACTION_GARBAGE_RATIO), COMPACTION_GARBAGE_RATIO),
                Settings.integer(settings, VECTOR_M),
                Settings.integer(settings, VECTOR_EF_CONSTRUCTION),
                Settings.integer(settings, VECTOR_EF_SEARCH),
                Settings.integer(settings, VECTOR_INDEX_MIN_ROWS),
                Embedder.fromSettings(settings),
                Reranker.fromSettings(settings),
                null);
    }

    static long bytes(String v, String key) {
        String s = v.strip().toUpperCase(Locale.ROOT).replace(" ", "");
        long unit = 1;
        if (s.endsWith("KB")) {
            unit = 1L << 10;
        } else if (s.endsWith("MB")) {
            unit = 1L << 20;
        } else if (s.endsWith("GB")) {
            unit = 1L << 30;
        }
        String digits = unit == 1 ? (s.endsWith("B") ? s.substring(0, s.length() - 1) : s) : s.substring(0, s.length() - 2);
        try {
            long n = Math.round(Double.parseDouble(digits) * unit);
            return key.equals(SEGMENT_MAX_BYTES) ? Math.min(n, 1_500_000_000L) : n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + ": invalid size '" + v + "'");
        }
    }

    static boolean bool(String v, String key) {
        String s = v.strip().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("false")) {
            return Boolean.parseBoolean(s);
        }
        throw new IllegalArgumentException(key + ": expected true or false, got '" + v + "'");
    }

    /** A duration setting in milliseconds: {@code 250ms}, {@code 30s}, {@code 5m}, {@code 1h} or plain milliseconds. */
    public static long millis(String v, String key) {
        String s = v.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        long unit = 1;
        String digits = s;
        if (s.endsWith("ms")) {
            digits = s.substring(0, s.length() - 2);
        } else if (s.endsWith("s")) {
            unit = 1_000;
            digits = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            unit = 60_000;
            digits = s.substring(0, s.length() - 1);
        } else if (s.endsWith("h")) {
            unit = 3_600_000;
            digits = s.substring(0, s.length() - 1);
        }
        try {
            return Math.round(Double.parseDouble(digits) * unit);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + ": invalid duration '" + v + "'");
        }
    }

    private static double number(String v, String key) {
        try {
            return Double.parseDouble(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + ": invalid number '" + v + "'");
        }
    }

    private static Isolation isolation(String v) {
        try {
            return Isolation.valueOf(v.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(TX_ISOLATION + ": expected READ_COMMITTED, SNAPSHOT or SERIALIZABLE, got '" + v + "'");
        }
    }

    private static Durability durability(String v) {
        try {
            return Durability.valueOf(v.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(DURABILITY + ": expected SYNC or ASYNC, got '" + v + "'");
        }
    }

    public Config withMemSegmentBytes(long v) {
        return new Config(v, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withSegmentMaxBytes(long v) {
        return new Config(memSegmentBytes, v, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withCompactionFanIn(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, v, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withMaxSegments(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, v, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withBloomFpp(double v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, v, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withDurability(Durability v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, v, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withGroupCommitMicros(long v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, v, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withSequencerMaxGroup(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, v, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withAutoPostingsRatio(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, v, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withPostingsOrMax(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, v, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withWriteBacklog(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, v, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withTransactions(boolean v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, v, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withTxTimeoutMillis(long v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, v, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withTxMaxBytes(long v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, v, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withTxIsolation(Isolation v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, v, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withTxRetries(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, v, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withTxConflictTableMax(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, v, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withFlushThreads(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, v, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withCompactionGarbageRatio(double v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, v, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withClassLoader(ClassLoader v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, v);
    }

    public Config withVectorM(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, v, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withVectorEfConstruction(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, v, vectorEfSearch, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withVectorEfSearch(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, v, vectorIndexMinRows, embedder, reranker, classLoader);
    }

    public Config withVectorIndexMinRows(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, v, embedder, reranker, classLoader);
    }

    /** Text embeddings with {@code v} (null = none), e.g. {@code Embedder.ollama(null, "nomic-embed-text")}. */
    public Config withEmbedder(Embedder v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, v, reranker, classLoader);
    }

    /** Reranking with {@code v} (null = none), e.g. {@code Reranker.ollama(null, "qwen3:4b")}. */
    public Config withReranker(Reranker v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, transactions, txTimeoutMillis, txMaxBytes, txRetries, txIsolation, txConflictTableMax, flushThreads, compactionGarbageRatio, vectorM, vectorEfConstruction, vectorEfSearch, vectorIndexMinRows, embedder, v, classLoader);
    }
}
