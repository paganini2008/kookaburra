package com.github.kookaburra.face.model;

import java.util.List;

/**
 * Result of a console statement: a table (SELECT), a plan (EXPLAIN) or an affected count (UPDATE / DELETE).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record QueryOutcome(List<String> columns, List<List<Object>> rows, String plan, long affected, long millis,
                           long offset, int pageSize) {

    /** Unpaged outcome. */
    public QueryOutcome(List<String> columns, List<List<Object>> rows, String plan, long affected, long millis) {
        this(columns, rows, plan, affected, millis, 0, -1);
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
        return plan == null && affected < 0;
    }

    /** One-line summary for the status bar. */
    public String summary() {
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
