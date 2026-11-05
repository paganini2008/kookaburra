package com.github.kookaburra.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full-text index on a {@code String} field: the text is split into words by the named analyzer and each segment
 * keeps, per word, the documents that contain it and how often (an inverted index, as in Lucene). Queried with
 * {@code where(field).matches("words")} (all words), {@code search(field, "words", k)} (BM25 relevance) and OQL
 * {@code MATCH} / {@code SCORE}; a trailing {@code *} matches word prefixes. {@code @Index(IndexKind.FULLTEXT)} is
 * the same with the default analyzer.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FullText {

    /**
     * Analyzer name: {@code english} (default: any language split correctly, English stop words and plurals),
     * {@code standard} (any language, no English rules) or one registered with {@code Analyzers.register}.
     */
    String analyzer() default "english";
}
