package com.github.kookaburra.engine;

import com.github.kookaburra.exec.SegmentView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable set of segment views per collection, newest first (design §7). Replaced, never modified.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Version {

    static final Version EMPTY = new Version(Map.of());

    private final Map<String, List<SegmentView>> views;

    Version(Map<String, List<SegmentView>> views) {
        this.views = views;
    }

    List<SegmentView> views(String collection) {
        return views.getOrDefault(collection, List.of());
    }

    Map<String, List<SegmentView>> all() {
        return views;
    }

    /** A version with {@code old} replaced by {@code replacement} (or removed when it is null). */
    Version replace(String collection, SegmentView old, SegmentView replacement) {
        Map<String, List<SegmentView>> m = new HashMap<>(views);
        List<SegmentView> l = new ArrayList<>(views(collection));
        int i = l.indexOf(old);
        if (i < 0) {
            throw new IllegalStateException(old.label() + " is not part of " + collection);
        }
        if (replacement == null) {
            l.remove(i);
        } else {
            l.set(i, replacement);
        }
        m.put(collection, List.copyOf(l));
        return new Version(Map.copyOf(m));
    }

    /**
     * A version where the adjacent views {@code olds} are replaced by {@code replacement} at the position of
     * the newest of them (or removed when it is null).
     */
    Version replaceAll(String collection, List<? extends SegmentView> olds, SegmentView replacement) {
        Map<String, List<SegmentView>> m = new HashMap<>(views);
        List<SegmentView> l = new ArrayList<>(views(collection));
        int at = Integer.MAX_VALUE;
        for (SegmentView o : olds) {
            int i = l.indexOf(o);
            if (i < 0) {
                throw new IllegalStateException(o.label() + " is not part of " + collection);
            }
            at = Math.min(at, i);
        }
        l.removeAll(olds);
        if (replacement != null) {
            l.add(at, replacement);
        }
        m.put(collection, List.copyOf(l));
        return new Version(Map.copyOf(m));
    }

    /** A version with {@code view} added as the newest view of {@code collection}. */
    Version withNewest(String collection, SegmentView view) {
        Map<String, List<SegmentView>> m = new HashMap<>(views);
        List<SegmentView> l = new ArrayList<>(views(collection));
        l.add(0, view);
        m.put(collection, List.copyOf(l));
        return new Version(Map.copyOf(m));
    }
}
