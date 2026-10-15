package com.github.kookaburra.model;

import com.github.kookaburra.annotation.Embeddable;

/**
 * Embedded counters of an {@link Account} (increment paths such as {@code stats.views}).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Embeddable
public class Stats {
    long views;
    int likes;

    public Stats() {
    }

    public Stats(long views, int likes) {
        this.views = views;
        this.likes = likes;
    }

    public long getViews() {
        return views;
    }

    public int getLikes() {
        return likes;
    }
}
