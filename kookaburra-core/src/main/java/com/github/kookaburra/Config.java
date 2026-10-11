package com.github.kookaburra;

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
 * @param classLoader       loader for entity classes found in the schema, or null for the default
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record Config(long memSegmentBytes, long segmentMaxBytes, int compactionFanIn, int maxSegments, double bloomFpp, Durability durability, long groupCommitMicros, int sequencerMaxGroup, int autoPostingsRatio, int postingsOrMax, int writeBacklog, ClassLoader classLoader) {

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

    public Config {
        if (memSegmentBytes < 1024 || segmentMaxBytes < memSegmentBytes || segmentMaxBytes > 1_500_000_000L) {
            throw new IllegalArgumentException("invalid segment sizes");
        }
        if (compactionFanIn < 2 || maxSegments < compactionFanIn || bloomFpp <= 0 || bloomFpp >= 1
                || groupCommitMicros < 0 || durability == null || sequencerMaxGroup < 1 || autoPostingsRatio < 1
                || postingsOrMax < 1 || writeBacklog < 1) {
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

    private static double number(String v, String key) {
        try {
            return Double.parseDouble(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + ": invalid number '" + v + "'");
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
        return new Config(v, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, classLoader);
    }

    public Config withSegmentMaxBytes(long v) {
        return new Config(memSegmentBytes, v, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, classLoader);
    }

    public Config withCompactionFanIn(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, v, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, classLoader);
    }

    public Config withMaxSegments(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, v, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, classLoader);
    }

    public Config withBloomFpp(double v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, v, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, classLoader);
    }

    public Config withDurability(Durability v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, v, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, classLoader);
    }

    public Config withGroupCommitMicros(long v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, v, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, classLoader);
    }

    public Config withSequencerMaxGroup(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, v, autoPostingsRatio, postingsOrMax, writeBacklog, classLoader);
    }

    public Config withAutoPostingsRatio(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, v, postingsOrMax, writeBacklog, classLoader);
    }

    public Config withPostingsOrMax(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, v, writeBacklog, classLoader);
    }

    public Config withWriteBacklog(int v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, v, classLoader);
    }

    public Config withClassLoader(ClassLoader v) {
        return new Config(memSegmentBytes, segmentMaxBytes, compactionFanIn, maxSegments, bloomFpp, durability, groupCommitMicros, sequencerMaxGroup, autoPostingsRatio, postingsOrMax, writeBacklog, v);
    }
}
