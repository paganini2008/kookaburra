package com.github.kookaburra.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed full-text query: words to find, and word prefixes ({@code bre*}, as in SQLite FTS5 and Lucene's prefix
 * query). Words are read with the field's {@link Analyzer}, so a query matches exactly the words an indexed value
 * was split into; a {@code *} right after a word makes it a prefix, lower-cased but not otherwise analyzed (as
 * Lucene does for wildcard terms).
 *
 * @param text     the query as given
 * @param words    whole words, distinct, in order
 * @param prefixes word prefixes, distinct, in order
 * @param analyzer the analyzer of the field
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public record TextQuery(String text, List<String> words, List<String> prefixes, List<List<Token>> phrases, Analyzer analyzer) {

    private static final StandardAnalyzer PLAIN = new StandardAnalyzer();
    /** Highlight marks (Elasticsearch's defaults). */
    public static final String PRE = "<em>";
    public static final String POST = "</em>";

    public TextQuery {
        words = List.copyOf(words);
        prefixes = List.copyOf(prefixes);
        phrases = phrases.stream().map(List::copyOf).toList();
    }

    /** Words and prefixes only (no phrases). */
    public TextQuery(String text, List<String> words, List<String> prefixes, Analyzer analyzer) {
        this(text, words, prefixes, List.of(), analyzer);
    }

    /** Parses {@code text} with the default analyzer; fails when it holds no word. */
    public static TextQuery parse(String text) {
        return parse(text, Analyzers.get(null));
    }

    /**
     * Parses {@code text} with {@code analyzer}: {@code "quoted words"} are phrases (the words next to each other,
     * in order, gaps of removed stop words kept), {@code bak*} a prefix, the rest single words; fails when it holds
     * no word.
     */
    public static TextQuery parse(String text, Analyzer analyzer) {
        if (text == null) {
            throw new IllegalArgumentException("full-text query is null");
        }
        Set<String> words = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        List<List<Token>> phrases = new ArrayList<>();
        StringBuilder rest = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int q = text.indexOf('"', i);
            if (q < 0) {
                rest.append(text, i, text.length());
                break;
            }
            int end = text.indexOf('"', q + 1);
            if (end < 0) {
                rest.append(text, i, text.length());              // an unclosed quote: plain words
                break;
            }
            rest.append(text, i, q).append(' ');
            List<Token> phrase = analyzer.tokens(text.substring(q + 1, end));
            if (phrase.size() == 1) {
                words.add(phrase.get(0).word());
            } else if (!phrase.isEmpty()) {
                phrases.add(phrase);
            }
            i = end + 1;
        }
        String plain = rest.toString().replace('"', ' ');
        int start = 0;
        for (int k = 0; k <= plain.length(); k++) {
            boolean star = k < plain.length() && plain.charAt(k) == '*';
            if (k == plain.length() || star) {
                String part = plain.substring(start, k);
                boolean prefix = star && k > start && !Character.isWhitespace(plain.charAt(k - 1));
                if (prefix) {
                    // the last word before the star is a prefix: split off with the plain rules, the rest analyzed
                    List<String> p = PLAIN.words(part);
                    int cut = lastWordStart(part);
                    words.addAll(analyzer.words(part.substring(0, cut)));
                    if (!p.isEmpty()) {
                        prefixes.add(p.get(p.size() - 1));
                    }
                } else {
                    words.addAll(analyzer.words(part));
                }
                start = k + 1;
            }
        }
        if (words.isEmpty() && prefixes.isEmpty() && phrases.isEmpty()) {
            throw new IllegalArgumentException("full-text query '" + text + "' has no words to search for");
        }
        return new TextQuery(text, new ArrayList<>(words), new ArrayList<>(prefixes), phrases, analyzer);
    }

    /** Start of the last run of letters or digits in {@code s}. */
    private static int lastWordStart(String s) {
        int i = s.length();
        while (i > 0) {
            int cp = s.codePointBefore(i);
            if (!Character.isLetterOrDigit(cp)) {
                break;
            }
            i -= Character.charCount(cp);
        }
        return i;
    }

    /**
     * Whether a value with these word frequencies matches: every term ({@code all}) or any term. Phrases cannot be
     * checked from frequencies: they count as matched when all their words are there (a candidate check; use
     * {@link #matches(String, boolean)} for the exact answer).
     */
    public boolean matches(Map<String, Integer> tf, boolean all) {
        for (String w : words) {
            boolean has = tf.containsKey(w);
            if (has != all) {
                return has;
            }
        }
        for (String p : prefixes) {
            boolean has = tf.keySet().stream().anyMatch(w -> w.startsWith(p));
            if (has != all) {
                return has;
            }
        }
        for (List<Token> phrase : phrases) {
            boolean has = phrase.stream().allMatch(t -> tf.containsKey(t.word()));
            if (has != all) {
                return has;
            }
        }
        return all;
    }

    /** Whether {@code value} matches: words, prefixes and phrases (exact). */
    public boolean matches(String value, boolean all) {
        if (value == null) {
            return false;
        }
        List<Token> tokens = analyzer.tokens(value);
        Map<String, Integer> tf = new HashMap<>();
        tokens.forEach(t -> tf.merge(t.word(), 1, Integer::sum));
        for (String w : words) {
            boolean has = tf.containsKey(w);
            if (has != all) {
                return has;
            }
        }
        for (String p : prefixes) {
            boolean has = tf.keySet().stream().anyMatch(w -> w.startsWith(p));
            if (has != all) {
                return has;
            }
        }
        for (List<Token> phrase : phrases) {
            boolean has = phraseAt(tokens, phrase) >= 0;
            if (has != all) {
                return has;
            }
        }
        return all;
    }

    /** Index in {@code tokens} where {@code phrase} starts (same words, same relative positions), or -1. */
    static int phraseAt(List<Token> tokens, List<Token> phrase) {
        int base = phrase.get(0).position();
        for (int i = 0; i < tokens.size(); i++) {
            Token first = tokens.get(i);
            if (!first.word().equals(phrase.get(0).word())) {
                continue;
            }
            boolean ok = true;
            int j = i;
            for (int k = 1; k < phrase.size() && ok; k++) {
                int want = first.position() + phrase.get(k).position() - base;
                while (j + 1 < tokens.size() && tokens.get(j + 1).position() < want) {
                    j++;
                }
                ok = j + 1 < tokens.size() && tokens.get(j + 1).position() == want && tokens.get(j + 1).word().equals(phrase.get(k).word());
                j++;
            }
            if (ok) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code value} with the matched words marked {@code <em>…</em>} (as Elasticsearch's highlighter and PostgreSQL's
     * {@code ts_headline}): the whole text when it is short, else up to three fragments of about 100 characters
     * around the matches, joined by {@code " … "}; null for null, the start of the text when nothing matches.
     */
    public String highlight(String value) {
        return highlight(value, 100, 3);
    }

    /** {@link #highlight(String)} with the fragment size (characters) and the most fragments. */
    public String highlight(String value, int fragmentSize, int maxFragments) {
        if (value == null) {
            return null;
        }
        List<Token> tokens = analyzer.tokens(value);
        List<int[]> hits = new ArrayList<>();                       // [start, end) of matched words
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.start() >= 0 && (words.contains(t.word()) || prefixes.stream().anyMatch(t.word()::startsWith))) {
                hits.add(new int[]{t.start(), t.end()});
            }
        }
        for (List<Token> phrase : phrases) {
            for (int i = 0; i < tokens.size(); i++) {
                int at = phraseAt(tokens.subList(i, tokens.size()), phrase);
                if (at < 0) {
                    break;
                }
                int s = i + at;
                int lastPos = tokens.get(s).position() + phrase.get(phrase.size() - 1).position() - phrase.get(0).position();
                int e = s;
                while (e + 1 < tokens.size() && tokens.get(e + 1).position() <= lastPos) {
                    e++;
                }
                if (tokens.get(s).start() >= 0) {
                    hits.add(new int[]{tokens.get(s).start(), tokens.get(e).end()});
                }
                i = s;
            }
        }
        hits.sort((x, y) -> x[0] != y[0] ? Integer.compare(x[0], y[0]) : Integer.compare(y[1], x[1]));
        List<int[]> merged = new ArrayList<>();
        for (int[] h : hits) {
            if (!merged.isEmpty() && h[0] < merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], h[1]);
            } else {
                merged.add(h);
            }
        }
        if (value.length() <= fragmentSize * 2) {
            return mark(value, 0, value.length(), merged);
        }
        if (merged.isEmpty()) {
            return value.substring(0, cut(value, fragmentSize)) + " …";
        }
        // fragments: greedy windows around the hits, the windows with most hits first, shown in text order
        List<int[]> windows = new ArrayList<>();
        for (int[] h : merged) {
            int from = Math.max(0, wordBoundary(value, h[0] - fragmentSize / 3, false));
            int to = Math.min(value.length(), wordBoundary(value, from + fragmentSize, true));
            if (windows.isEmpty() || from >= windows.get(windows.size() - 1)[1]) {
                windows.add(new int[]{from, to, 0});
            }
            windows.get(windows.size() - 1)[2]++;
        }
        List<int[]> best = new ArrayList<>(windows);
        best.sort((x, y) -> Integer.compare(y[2], x[2]));
        best = new ArrayList<>(best.subList(0, Math.min(maxFragments, best.size())));
        best.sort((x, y) -> Integer.compare(x[0], y[0]));
        StringBuilder sb = new StringBuilder();
        for (int[] w : best) {
            if (sb.length() > 0 || w[0] > 0) {
                sb.append(sb.length() > 0 ? " … " : "… ");
            }
            sb.append(mark(value, w[0], w[1], merged).strip());
        }
        if (best.get(best.size() - 1)[1] < value.length()) {
            sb.append(" …");
        }
        return sb.toString();
    }

    private static String mark(String value, int from, int to, List<int[]> hits) {
        StringBuilder sb = new StringBuilder();
        int at = from;
        for (int[] h : hits) {
            if (h[1] <= from || h[0] >= to) {
                continue;
            }
            int s = Math.max(h[0], from);
            int e = Math.min(h[1], to);
            sb.append(value, at, s).append(PRE).append(value, s, e).append(POST);
            at = e;
        }
        return sb.append(value, at, to).toString();
    }

    /** The nearest space at or after ({@code forward}) / at or before {@code i}, within the text. */
    private static int wordBoundary(String value, int i, boolean forward) {
        int k = Math.max(0, Math.min(value.length(), i));
        while (k > 0 && k < value.length() && !Character.isWhitespace(value.charAt(k))) {
            k += forward ? 1 : -1;
        }
        return k;
    }

    private static int cut(String value, int size) {
        return Math.min(value.length(), wordBoundary(value, size, false) == 0 ? size : wordBoundary(value, size, false));
    }

    @Override
    public String toString() {
        List<String> all = new ArrayList<>(words);
        phrases.forEach(p -> all.add("\"" + String.join(" ", p.stream().map(Token::word).toList()) + "\""));
        prefixes.forEach(p -> all.add(p + "*"));
        return String.join(" ", all);
    }
}
