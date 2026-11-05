package com.github.kookaburra.field;

import com.github.kookaburra.annotation.FullText;
import com.github.kookaburra.query.Pred;
import com.github.kookaburra.text.Analyzer;
import com.github.kookaburra.text.Analyzers;
import com.github.kookaburra.text.TextQuery;

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

    /** Full-text match: the text contains every word of {@code query} ({@code bre*} = a word starting with "bre"). */
    public Pred<T> matches(String query) {
        return new Pred.Match<>(this, TextQuery.parse(query, analyzer()), true);
    }

    /** The analyzer of the field's {@code @FullText}, else the default one. */
    private Analyzer analyzer() {
        List<Property> chain = chain();
        FullText ft = chain.isEmpty() ? null : chain.get(chain.size() - 1).javaField().getAnnotation(FullText.class);
        return Analyzers.get(ft == null ? null : ft.analyzer());
    }

    /** Full-text match: the text contains at least one word of {@code query}. */
    public Pred<T> matchesAny(String query) {
        return new Pred.Match<>(this, TextQuery.parse(query, analyzer()), false);
    }
}
