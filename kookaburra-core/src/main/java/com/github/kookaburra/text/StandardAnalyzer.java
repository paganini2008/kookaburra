package com.github.kookaburra.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code standard} analyzer, for text in any language: like SQLite FTS5's {@code unicode61} tokenizer plus
 * Lucene's CJK bigrams. Runs of Unicode letters, digits and combining marks are words (Latin, Cyrillic, Greek,
 * Arabic, Hebrew, Devanagari, Thai …), lower-cased; runs of Chinese, Japanese or Korean characters become
 * overlapping pairs ({@code 全文索引} → {@code 全文, 文索, 索引}), a single such character is a word of its own;
 * everything else separates words. Words longer than {@link #MAX_WORD} characters are cut.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public class StandardAnalyzer implements Analyzer {

    /** Longest word kept (characters); longer runs are cut. */
    public static final int MAX_WORD = 64;

    /** The words of {@code text}, in order (empty for null). */
    @Override
    public List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (Token t : tokens(text)) {
            out.add(t.word());
        }
        return out;
    }

    /** The words with positions and offsets. */
    @Override
    public List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int wordStart = -1;
        int wordEnd = -1;
        List<int[]> cjk = new ArrayList<>();                     // code point, start, end
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int at = i;
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                wordStart = flushWord(text, wordStart, wordEnd, out);
                cjk.add(new int[]{cp, at, i});
            } else if (Character.isLetterOrDigit(cp) || isMark(cp)) {
                flushCjk(cjk, out);
                if (wordStart < 0) {
                    wordStart = at;
                }
                wordEnd = i;
            } else {
                wordStart = flushWord(text, wordStart, wordEnd, out);
                flushCjk(cjk, out);
            }
        }
        flushWord(text, wordStart, wordEnd, out);
        flushCjk(cjk, out);
        return out;
    }

    /** Adds the word [start, end) if any; returns -1 (no word open). */
    private static int flushWord(String text, int start, int end, List<Token> out) {
        if (start >= 0) {
            String w = text.substring(start, end);
            if (w.length() > MAX_WORD) {
                w = w.substring(0, MAX_WORD);
            }
            out.add(new Token(w.toLowerCase(Locale.ROOT), out.size(), start, end));
        }
        return -1;
    }

    private static void flushCjk(List<int[]> run, List<Token> out) {
        if (run.size() == 1) {
            int[] c = run.get(0);
            out.add(new Token(new String(Character.toChars(c[0])), out.size(), c[1], c[2]));
        }
        for (int k = 0; k + 1 < run.size(); k++) {
            int[] a = run.get(k);
            int[] b = run.get(k + 1);
            out.add(new Token(new StringBuilder().appendCodePoint(a[0]).appendCodePoint(b[0]).toString(), out.size(), a[1], b[2]));
        }
        run.clear();
    }

    private static boolean isMark(int cp) {
        int t = Character.getType(cp);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK;
    }

    static boolean isCjk(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL;
    }
}
