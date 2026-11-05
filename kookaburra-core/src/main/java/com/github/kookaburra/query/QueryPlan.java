package com.github.kookaburra.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The plan of a query as a tree of steps, the root being the result (as in PostgreSQL's {@code EXPLAIN}): each step
 * says what it does, how it reads the data (an index, a scan of the rows, an in-memory segment …) and, after
 * {@code explainAnalyze()}, how many rows it produced and how long it took. {@link #render()} draws it as text with
 * tree lines; Studio draws it as a diagram.
 *
 * @param root     the first step
 * @param analyzed whether the query was run and the steps carry actual rows and times
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public record QueryPlan(Step root, boolean analyzed) {

    /** How a step reads data; Studio colours steps by it. */
    public enum Access {
        /** answered by an index (postings, ordinals, OIDs, the type bitmap) */
        INDEX,
        /** answered by a full-text index */
        FULLTEXT,
        /** answered by a vector index (HNSW graph) or vector column */
        VECTOR,
        /** reads each row (no index for the condition) */
        SCAN,
        /** works on rows already found: sort, slice, re-order, fetch */
        PROCESS,
        /** a container of other steps */
        NONE
    }

    /**
     * One step.
     *
     * @param operation short name, e.g. {@code Filter}, {@code Top-N sort}, {@code seg#3}
     * @param detail    what it does, e.g. the condition
     * @param access    how it reads data
     * @param inputRows rows it started from, or -1
     * @param rows      rows it produced (actual after analyze, else an estimate), or -1 when unknown
     * @param nanos     time spent (analyze only), or -1
     * @param children  the steps feeding it
     */
    public record Step(String operation, String detail, Access access, long inputRows, long rows, long nanos, List<Step> children) {

        public Step {
            children = List.copyOf(children);
        }

        public Step(String operation, String detail, Access access, List<Step> children) {
            this(operation, detail, access, -1, -1, -1, children);
        }

        /** This step with actual figures. */
        public Step with(long input, long out, long time) {
            return new Step(operation, detail, access, input, out, time, children);
        }

        /** This step with other children. */
        public Step withChildren(List<Step> c) {
            return new Step(operation, detail, access, inputRows, rows, nanos, c);
        }
    }

    /** The plan as text: one line per step, tree lines, rows and time right-aligned. */
    public String render() {
        List<String[]> lines = new ArrayList<>();
        walk(root, "", "", lines);
        int width = 0;
        for (String[] l : lines) {
            width = Math.max(width, l[0].length());
        }
        width = Math.min(width, 100);
        StringBuilder sb = new StringBuilder();
        for (String[] l : lines) {
            sb.append(l[0]);
            if (!l[1].isEmpty()) {
                sb.append(" ".repeat(Math.max(2, width - l[0].length() + 2))).append(l[1]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private void walk(Step s, String lead, String childLead, List<String[]> out) {
        String text = lead + s.operation() + (s.detail() == null || s.detail().isEmpty() ? "" : "  " + s.detail());
        out.add(new String[]{text, figures(s)});
        for (int i = 0; i < s.children().size(); i++) {
            boolean last = i == s.children().size() - 1;
            walk(s.children().get(i), childLead + (last ? "└─ " : "├─ "), childLead + (last ? "   " : "│  "), out);
        }
    }

    private String figures(Step s) {
        StringBuilder f = new StringBuilder();
        if (s.rows() >= 0) {
            if (s.inputRows() >= 0) {
                f.append(String.format(Locale.ROOT, "%,d → ", s.inputRows()));
            }
            f.append(String.format(Locale.ROOT, "%,d %s", s.rows(), s.rows() == 1 ? "row" : "rows"));
        }
        if (analyzed && s.nanos() >= 0) {
            f.append(f.length() == 0 ? "" : "   ").append(time(s.nanos()));
        }
        return f.toString();
    }

    /** {@code 0.42 ms}, {@code 12.3 ms}, {@code 1.25 s}. */
    public static String time(long nanos) {
        double ms = nanos / 1e6;
        return ms < 10 ? String.format(Locale.ROOT, "%.2f ms", ms) : ms < 1000 ? String.format(Locale.ROOT, "%.1f ms", ms)
                : String.format(Locale.ROOT, "%.2f s", ms / 1000);
    }

    @Override
    public String toString() {
        return render();
    }
}
