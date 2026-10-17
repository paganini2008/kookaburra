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
 * {@link DatabaseStats} as rows for the Stats tab: one row per collection, and the counters as
 * (section, metric, value) rows.
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
