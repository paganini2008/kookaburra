package com.github.kookaburra.text;

/**
 * One word of an analyzed text: the word as indexed, its position (words removed by the analyzer, such as stop
 * words, still take a position, as in Lucene, so phrases keep their gaps) and where it was in the text
 * ({@code start} inclusive, {@code end} exclusive; -1 when the analyzer does not know).
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public record Token(String word, int position, int start, int end) {
}
