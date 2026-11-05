package com.github.kookaburra.exec;

import java.util.ArrayList;
import java.util.List;

/**
 * Measurements of one query run for {@code explainAnalyze()}: per segment view the objects it holds, how many
 * passed the filter and how long that took, and per condition (in evaluation order, with its nesting depth) how
 * many documents went in and came out.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public final class Profile {

    /** One evaluated condition (or AND / OR / NOT) of one view. */
    public record Condition(String text, String access, int depth, long in, long out, long nanos) {
    }

    /** The filter of one view. */
    public record View(String label, long size, long candidates, long matched, long nanos, List<Condition> conditions) {
    }

    private final List<View> views = new ArrayList<>();
    List<Condition> current;
    int depth;

    public List<View> views() {
        return views;
    }

    void add(View v) {
        views.add(v);
    }

    /** Total filter time over all views. */
    public long filterNanos() {
        return views.stream().mapToLong(View::nanos).sum();
    }

    /** Documents that passed the filter over all views. */
    public long matched() {
        return views.stream().mapToLong(View::matched).sum();
    }
}
