package com.github.kookaburra.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The {@code english} analyzer, the default: the chain of Lucene's and Elasticsearch's {@code english} analyzer on
 * top of the {@link StandardAnalyzer}, so text in other languages is still split correctly. Possessives are dropped
 * ({@code John's} → {@code john}), English stop words removed ({@code the, a, of, …}, Lucene's set) and words reduced
 * to their Porter stem ({@code baking, baked, bakes} → {@code bake}; {@code movie, movies} → {@code movi}); words
 * outside a to z are left as they are.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public class EnglishAnalyzer extends StandardAnalyzer {

    /** Lucene's English stop words. */
    public static final Set<String> STOP_WORDS = Set.of("a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if",
            "in", "into", "is", "it", "no", "not", "of", "on", "or", "such", "that", "the", "their", "then", "there",
            "these", "they", "this", "to", "was", "will", "with");

    @Override
    public List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (Token t : tokens(text)) {
            out.add(t.word());
        }
        return out;
    }

    /**
     * Standard words, then: a possessive {@code 's} is dropped ({@code John's} → {@code john}), stop words are
     * removed but keep their position (phrases keep their gaps, as Lucene's StopFilter), words get their Porter stem.
     */
    @Override
    public List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        Token prev = null;
        int position = 0;
        for (Token t : super.tokens(text)) {
            boolean possessive = t.word().equals("s") && prev != null && t.start() == prev.end() + 1
                    && (text.charAt(prev.end()) == '\'' || text.charAt(prev.end()) == '’');
            prev = t;
            if (possessive) {
                continue;                                        // part of the word before: no position of its own
            }
            if (!STOP_WORDS.contains(t.word())) {
                out.add(new Token(PorterStemmer.stem(t.word()), position, t.start(), t.end()));
            }
            position++;
        }
        return out;
    }
}
