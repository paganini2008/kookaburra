package com.github.kookaburra.face.model;

import com.github.kookaburra.oql.grammar.OqlLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Syntax highlighting style spans: OQL from the ANTLR lexer, JSON by pattern (design §17.2 colors in CSS).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Highlight {

    private static final Pattern JSON = Pattern.compile(
            "(?<key>\"(?:[^\"\\\\]|\\\\.)*\"(?=\\s*:))|(?<str>\"(?:[^\"\\\\]|\\\\.)*\")|(?<num>-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)"
                    + "|(?<lit>\\b(?:true|false|null)\\b)|(?<punct>[{}\\[\\],:])");

    private Highlight() {
    }

    /** Style class of an OQL token type. */
    public static String oqlStyle(int type) {
        if (type == OqlLexer.STRING) {
            return "str";
        }
        if (type == OqlLexer.INTEGER || type == OqlLexer.DECIMAL) {
            return "num";
        }
        if (type == OqlLexer.LINE_COMMENT || type == OqlLexer.BLOCK_COMMENT) {
            return "comment";
        }
        if (type == OqlLexer.IDENT || type == OqlLexer.QUOTED_IDENT) {
            return "ident";
        }
        if (type == OqlLexer.ERROR_CHAR) {
            return "error";
        }
        if (type == OqlLexer.WS) {
            return null;
        }
        String literal = OqlLexer.VOCABULARY.getLiteralName(type);
        if (literal != null && literal.length() > 2 && Character.isLetter(literal.charAt(1))) {
            return "kw";
        }
        return "op";
    }

    public static StyleSpans<Collection<String>> oql(String text) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        OqlLexer lexer = new OqlLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        int last = 0;
        for (Token t : lexer.getAllTokens()) {
            int start = t.getStartIndex();
            int end = t.getStopIndex() + 1;
            if (start > last) {
                b.add(Collections.emptyList(), start - last);
            }
            String style = oqlStyle(t.getType());
            b.add(style == null ? Collections.emptyList() : List.of(style), end - start);
            last = end;
        }
        b.add(Collections.emptyList(), Math.max(0, text.length() - last));
        return b.create();
    }

    public static StyleSpans<Collection<String>> json(String text) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        Matcher m = JSON.matcher(text);
        int last = 0;
        while (m.find()) {
            String style = m.group("key") != null ? "key" : m.group("str") != null ? "str" : m.group("num") != null ? "num"
                    : m.group("lit") != null ? "kw" : "op";
            b.add(Collections.emptyList(), m.start() - last);
            b.add(List.of(style), m.end() - m.start());
            last = m.end();
        }
        b.add(Collections.emptyList(), text.length() - last);
        return b.create();
    }
}
