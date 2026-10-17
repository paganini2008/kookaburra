package com.github.kookaburra.face.model;

import java.util.List;

/**
 * Result of a console statement: a table (SELECT), a plan (EXPLAIN), an affected count (UPDATE / DELETE) or a
 * note (BEGIN / COMMIT / ROLLBACK).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record QueryOutcome(List<String> columns, List<List<Object>> rows, String plan, long affected, long millis,
                           long offset, int pageSize, String note) {

    /** Unpaged outcome. */
    public QueryOutcome(List<String> columns, List<List<Object>> rows, String plan, long affected, long millis) {
        this(columns, rows, plan, affected, millis, 0, -1);
    }

    public QueryOutcome(List<String> columns, List<List<Object>> rows, String plan, long affected, long millis,
                        long offset, int pageSize) {
        this(columns, rows, plan, affected, millis, offset, pageSize, null);
    }

    /** Outcome of a command without a result, e.g. {@code COMMIT}. */
    public static QueryOutcome note(String text, long millis) {
        return new QueryOutcome(List.of(), List.of(), null, -1, millis, 0, -1, text);
    }

    /** True when the result is one page of a larger result (more pages may follow). */
    public boolean paged() {
        return pageSize > 0;
    }

    /** True if a following page may exist (this page is full). */
    public boolean hasNext() {
        return paged() && rows.size() == pageSize;
    }

    public boolean isTable() {
        return plan == null && affected < 0 && note == null;
    }

    /** One-line summary for the status bar. */
    public String summary() {
        if (note != null) {
            return note + " · " + millis + " ms";
        }
        if (plan != null) {
            return "plan · " + millis + " ms";
        }
        if (affected >= 0) {
            return affected + " object" + (affected == 1 ? "" : "s") + " affected · " + millis + " ms";
        }
        if (paged()) {
            return rows.isEmpty() ? "no rows · " + millis + " ms"
                    : "rows " + (offset + 1) + "–" + (offset + rows.size()) + (hasNext() ? "+" : "") + " · " + millis + " ms";
        }
        return rows.size() + " row" + (rows.size() == 1 ? "" : "s") + " · " + millis + " ms";
    }
}
