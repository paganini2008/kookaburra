package com.github.kookaburra.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits text into the words of the full-text index. The same analyzer indexes values and reads queries, so both
 * always agree. Built in: {@code english} (default) and {@code standard} (any language, no English rules), see
 * {@link Analyzers}; register your own (for example a dictionary-based Chinese word segmenter) with
 * {@link Analyzers#register} and name it in {@code @FullText(analyzer = "…")}.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
@FunctionalInterface
public interface Analyzer {

    /** The words of {@code text}, in order (empty for null or a text without words). */
    List<String> words(String text);

    /**
     * The words with their positions and offsets in {@code text}; analyzers that only implement {@link #words}
     * number the words in order and give no offsets (no highlighting).
     */
    default List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        List<String> w = words(text);
        for (int i = 0; i < w.size(); i++) {
            out.add(new Token(w.get(i), i, -1, -1));
        }
        return out;
    }

    /** Word → number of occurrences in {@code text}. */
    default Map<String, Integer> frequencies(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String w : words(text)) {
            tf.merge(w, 1, Integer::sum);
        }
        return tf;
    }
}
