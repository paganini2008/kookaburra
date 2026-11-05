package com.github.kookaburra.exec;

import org.roaringbitmap.RoaringBitmap;

import java.util.List;

/**
 * Full-text index of one {@code @FullText} path in one segment view: words → documents and frequencies, document
 * lengths and the statistics BM25 needs. Counts include deleted documents (as Lucene's).
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public interface TextIndex {

    /** Documents containing {@code word} (a new bitmap; empty when absent). */
    RoaringBitmap docs(String word);

    /** Words of the dictionary starting with {@code prefix}, at most {@code max}. */
    List<String> words(String prefix, int max);

    /** Occurrences of {@code word} in {@code doc} (0 when absent). */
    int frequency(String word, int doc);

    /** Number of words of {@code doc}. */
    int length(int doc);

    /** Documents with at least one word. */
    long docCount();

    /** Words of all documents. */
    long totalWords();

    /** Documents containing {@code word}. */
    long docFrequency(String word);

    /** Short description for explain output. */
    String describe();
}
