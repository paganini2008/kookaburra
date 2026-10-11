package com.github.kookaburra.field;

import com.github.kookaburra.query.Pred;

import java.util.List;

/**
 * Field of a {@code String}; ordered by {@link String#compareTo} (UTF-16 code units).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class StringField<T> extends ComparableField<T, String> {

    StringField(Class<T> entity, String path, List<Property> chain) {
        super(entity, path, chain);
    }

    public Pred<T> startsWith(String prefix) {
        return new Pred.StartsWith<>(this, prefix);
    }
}
