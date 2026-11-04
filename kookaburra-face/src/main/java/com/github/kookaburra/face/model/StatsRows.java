package com.github.kookaburra.face.model;

import com.github.kookaburra.DatabaseStats;
import com.github.kookaburra.Isolation;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link DatabaseStats} for the Stats tab (dashboard): key figures, per-second rates between two samples, one
 * row per collection, and the counters as (section, metric, value) rows.
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
public final class StatsRows {

    /** One counter. */
    public record Metric(String section, String name, String value) {
    }

    /** One collection: simple name and formatted values. */
    public record Collection(String name, String objects, String segments, String size, String deleted,
                             String unflushed, String unflushedSize) {
    }

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private StatsRows() {
    }

    public static List<Collection> collections(DatabaseStats s) {
        List<Collection> out = new ArrayList<>();
        for (DatabaseStats.CollectionStats c : s.collections()) {
            out.add(new Collection(c.name().substring(c.name().lastIndexOf('.') + 1), n(c.objects()), n(c.segments()),
                    Display.size(c.segmentBytes(), "bytes"), n(c.deletedRows()), n(c.memRows()),
                    Display.size(c.memBytes(), "bytes")));
        }
        return out;
    }

    public static List<Metric> metrics(DatabaseStats s) {
        List<Metric> out = new ArrayList<>();
        String db = "Database";
        out.add(new Metric(db, "objects", n(s.objects())));
        out.add(new Metric(db, "segment files", Display.size(s.segmentBytes(), "bytes")));
        out.add(new Metric(db, "snapshot seq", n(s.seq())));
        out.add(new Metric(db, "opened", UTC.format(s.openedAt())));
        out.add(new Metric(db, "uptime", uptime(Duration.between(s.openedAt(), s.takenAt()))));

        DatabaseStats.Writes w = s.writes();
        String wr = "Writes";
        out.add(new Metric(wr, "batches committed", n(w.batches())));
        out.add(new Metric(wr, "batches rejected", n(w.rejected())));
        out.add(new Metric(wr, "inserts / updates / deletes", n(w.inserts()) + " / " + n(w.updates()) + " / " + n(w.deletes())));
        out.add(new Metric(wr, "group commits", n(w.groupCommits()) + String.format(Locale.ROOT, " (%.1f batches each)", w.batchesPerGroup())));
        out.add(new Metric(wr, "log records replayed at open", n(w.walReplayed())));
        out.add(new Metric(wr, "back-pressure waits", n(w.backPressureWaits()) + " (" + n(w.backPressureMillis()) + " ms)"));

        DatabaseStats.Transactions t = s.transactions();
        String tx = "Transactions";
        out.add(new Metric(tx, "active", n(t.active())));
        out.add(new Metric(tx, "retries", n(t.retries())));
        for (Isolation i : Isolation.values()) {
            DatabaseStats.Outcomes o = t.byIsolation().get(i);
            out.add(new Metric(tx, i.name().replace('_', ' '), n(o.committed()) + " committed · " + n(o.rolledBack())
                    + " rolled back · " + n(o.conflicts()) + " conflicts · " + n(o.timeouts()) + " timeouts"));
        }

        DatabaseStats.Constraints c = s.constraints();
        String cs = "Constraints";
        out.add(new Metric(cs, "unique violations", n(c.uniqueViolations())));
        out.add(new Metric(cs, "foreign key violations", n(c.foreignKeyViolations())));
        out.add(new Metric(cs, "cascade deletes", n(c.cascadeDeletes())));
        out.add(new Metric(cs, "set null updates", n(c.setNulls())));

        DatabaseStats.Maintenance m = s.maintenance();
        String mt = "Maintenance";
        out.add(new Metric(mt, "flushes", n(m.flushes()) + " (" + n(m.flushedRows()) + " rows, "
                + Display.size(m.flushedBytes(), "bytes") + ", " + n(m.flushMillis()) + " ms)"));
        out.add(new Metric(mt, "compactions", n(m.compactions()) + " (" + n(m.compactedSegments()) + " segments, "
                + Display.size(m.compactedBytes(), "bytes") + ", " + n(m.compactionMillis()) + " ms)"));
        out.add(new Metric(mt, "pending flushes and merges", n(m.pending())));
        out.add(new Metric(mt, "backups", n(m.backups())));
        return out;
    }

    /** Per-second rates between two statistics of one open database (zeros without an earlier one). */
    public record Rates(double inserts, double updates, double deletes, double commits, double txCommits, double conflicts) {

        public static final Rates NONE = new Rates(0, 0, 0, 0, 0, 0);
    }

    /** Rates from {@code prev} to {@code cur}; NONE when {@code prev} is null, of another opening or not earlier. */
    public static Rates rates(DatabaseStats prev, DatabaseStats cur) {
        if (prev == null || !prev.openedAt().equals(cur.openedAt())) {
            return Rates.NONE;
        }
        double secs = Duration.between(prev.takenAt(), cur.takenAt()).toNanos() / 1e9;
        if (secs <= 0) {
            return Rates.NONE;
        }
        DatabaseStats.Writes a = prev.writes();
        DatabaseStats.Writes b = cur.writes();
        DatabaseStats.Outcomes x = prev.transactions().total();
        DatabaseStats.Outcomes y = cur.transactions().total();
        return new Rates(rate(a.inserts(), b.inserts(), secs), rate(a.updates(), b.updates(), secs), rate(a.deletes(), b.deletes(), secs),
                rate(a.batches(), b.batches(), secs), rate(x.committed(), y.committed(), secs), rate(x.conflicts(), y.conflicts(), secs));
    }

    private static double rate(long from, long to, double secs) {
        return Math.max(0, to - from) / secs;
    }

    /** One key figure of the dashboard: caption, big value and a detail line. */
    public record Tile(String id, String caption, String value, String detail) {
    }

    /** The key figures: objects, storage, commit rate, transactions, conflicts, background work. */
    public static List<Tile> tiles(DatabaseStats s, Rates r) {
        long unflushed = s.collections().stream().mapToLong(DatabaseStats.CollectionStats::memBytes).sum();
        long unflushedRows = s.collections().stream().mapToLong(DatabaseStats.CollectionStats::memRows).sum();
        int segments = s.collections().stream().mapToInt(DatabaseStats.CollectionStats::segments).sum();
        DatabaseStats.Outcomes t = s.transactions().total();
        long ended = t.committed() + t.rolledBack();
        DatabaseStats.Maintenance m = s.maintenance();
        return List.of(
                new Tile("objects", "Objects", n(s.objects()), s.collections().size() + " collections"),
                new Tile("storage", "Storage", Display.size(s.segmentBytes() + unflushed, "bytes"),
                        segments + " segment files · " + n(unflushedRows) + " rows unflushed"),
                new Tile("commits", "Commits / s", rate(r.commits()), n(s.writes().batches()) + " since open"),
                new Tile("transactions", "Open transactions", n(s.transactions().active()),
                        n(t.committed()) + " committed · " + n(t.rolledBack()) + " rolled back"),
                new Tile("conflicts", "Conflicts", n(t.conflicts()),
                        ended == 0 ? "no transaction ended yet" : String.format(Locale.ROOT, "%.1f %% of ended transactions", 100.0 * t.conflicts() / ended)),
                new Tile("maintenance", "Pending flushes / merges", n(m.pending()),
                        n(m.flushes()) + " flushes · " + n(m.compactions()) + " compactions"));
    }

    /** A rate with one decimal below 100, whole numbers above. */
    static String rate(double v) {
        return v < 100 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%,.0f", v);
    }

    private static String n(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    static String uptime(Duration d) {
        long s = d.toSeconds();
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return s / 60 + " min " + s % 60 + " s";
        }
        return s / 3600 + " h " + s % 3600 / 60 + " min";
    }
}
