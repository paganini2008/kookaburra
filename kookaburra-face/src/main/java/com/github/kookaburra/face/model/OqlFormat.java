package com.github.kookaburra.face.model;

import com.github.kookaburra.oql.grammar.OqlLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Formats OQL like the "Format SQL" of DBeaver or DataGrip: keywords upper-case, each clause on its own line
 * ({@code SELECT, FROM, JOIN, WHERE, GROUP BY, HAVING, ORDER BY, LIMIT, OFFSET, UPDATE, SET, DELETE}), {@code AND} /
 * {@code OR} of a condition on indented lines (not the {@code AND} of {@code BETWEEN}), long select lists one item
 * per line, sub-queries indented, statements separated by a blank line. Works on the OQL lexer's tokens, so strings,
 * quoted names, comments and identifiers are kept exactly; a text the lexer cannot read is returned unchanged.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public final class OqlFormat {

    /** Select lists longer than this (characters, on one line) get one item per line. */
    static final int LONG_LIST = 60;

    private static final String INDENT = "    ";
    private static final Set<Integer> CLAUSES = Set.of(OqlLexer.SELECT, OqlLexer.FROM, OqlLexer.WHERE, OqlLexer.GROUP,
            OqlLexer.HAVING, OqlLexer.ORDER, OqlLexer.LIMIT, OqlLexer.OFFSET, OqlLexer.UPDATE, OqlLexer.SET, OqlLexer.DELETE);
    private static final Set<Integer> JOINS = Set.of(OqlLexer.JOIN, OqlLexer.LEFT, OqlLexer.INNER);
    private static final Set<Integer> FUNCTIONS = Set.of(OqlLexer.COUNT, OqlLexer.SUM, OqlLexer.AVG, OqlLexer.MIN,
            OqlLexer.MAX, OqlLexer.TYPE, OqlLexer.KEY, OqlLexer.VALUE, OqlLexer.IDENT, OqlLexer.QUOTED_IDENT);
    private static final Set<String> TX_COMMANDS = Set.of("BEGIN", "COMMIT", "ROLLBACK", "START", "TRANSACTION", "WORK",
            "ISOLATION", "LEVEL", "READ", "COMMITTED", "REPEATABLE", "SNAPSHOT", "SERIALIZABLE");

    private OqlFormat() {
    }

    /** The formatted text; unchanged when it holds characters OQL does not know. */
    public static String format(String text) {
        OqlLexer lexer = new OqlLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        List<Token> tokens = new ArrayList<>();
        for (Token t : lexer.getAllTokens()) {
            if (t.getType() == OqlLexer.ERROR_CHAR) {
                return text;
            }
            if (t.getType() != OqlLexer.WS) {
                tokens.add(t);
            }
        }
        return new Writer(tokens).write();
    }

    /** One pass over the tokens. */
    private static final class Writer {
        private final List<Token> tokens;
        private final StringBuilder out = new StringBuilder();
        /** Parenthesis depth, and per open parenthesis whether it holds a sub-query (indented). */
        private final List<Boolean> parens = new ArrayList<>();
        private int queryDepth;
        /** Clause being written at the current query depth (a clause token type), for AND / OR and lists. */
        private int clause;
        private int clauseParens;
        private boolean between;
        private boolean listPerLine;

        Writer(List<Token> tokens) {
            this.tokens = tokens;
        }

        private String indent(int extra) {
            return INDENT.repeat(queryDepth + extra);
        }

        private void newline(int extra) {
            int end = out.length();
            while (end > 0 && out.charAt(end - 1) == ' ') {
                end--;
            }
            out.setLength(end);
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(indent(extra));
        }

        private boolean atLineStart() {
            int i = out.length();
            while (i > 0 && out.charAt(i - 1) == ' ') {
                i--;
            }
            return i == 0 || out.charAt(i - 1) == '\n';
        }

        String write() {
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                Token prev = i > 0 ? tokens.get(i - 1) : null;
                Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                int type = t.getType();
                String text = text(t);

                if (type == OqlLexer.LINE_COMMENT) {
                    space(prev, t);
                    out.append(text);
                    newline(clause == 0 ? 0 : 1);
                    continue;
                }
                if (text.equals(";")) {
                    out.append(';');
                    queryDepth = 0;
                    parens.clear();
                    clause = 0;
                    if (next != null) {
                        newline(0);
                        out.setLength(out.length());
                        out.append('\n');
                    }
                    continue;
                }
                if (CLAUSES.contains(type) && clauseStart(prev, type)) {
                    if (!(type == OqlLexer.SELECT && prev != null && (prev.getType() == OqlLexer.EXPLAIN || prev.getType() == OqlLexer.ANALYZE))
                            && prev != null && !text(prev).equals("(")) {
                        newline(0);
                    } else {
                        space(prev, t);
                    }
                    out.append(text);
                    clause = type;
                    clauseParens = parens.size();
                    between = false;
                    if (type == OqlLexer.SELECT) {
                        listPerLine = selectListLength(i + 1) > LONG_LIST;
                    }
                    continue;
                }
                if (JOINS.contains(type) && !(prev != null && JOINS.contains(prev.getType())) && clause == OqlLexer.FROM) {
                    newline(0);
                    out.append(text);
                    continue;
                }
                if ((type == OqlLexer.AND || type == OqlLexer.OR) && parens.size() == clauseParens
                        && (clause == OqlLexer.WHERE || clause == OqlLexer.HAVING)) {
                    if (type == OqlLexer.AND && between) {
                        between = false;
                        space(prev, t);
                        out.append(text);
                        continue;
                    }
                    newline(1);
                    out.append(text);
                    continue;
                }
                if (type == OqlLexer.BETWEEN) {
                    between = true;
                }
                if (text.equals("(")) {
                    boolean sub = next != null && next.getType() == OqlLexer.SELECT;
                    space(prev, t);
                    out.append('(');
                    parens.add(sub);
                    if (sub) {
                        queryDepth++;
                        newline(0);
                        clause = 0;
                    }
                    continue;
                }
                if (text.equals(")")) {
                    boolean sub = !parens.isEmpty() && parens.remove(parens.size() - 1);
                    if (sub) {
                        queryDepth--;
                        newline(0);
                        clause = OqlLexer.WHERE;              // back in the outer condition (best effort)
                        clauseParens = parens.size();
                    }
                    out.append(')');
                    continue;
                }
                if (text.equals(",")) {
                    out.append(',');
                    if (listPerLine && clause == OqlLexer.SELECT && parens.size() == clauseParens) {
                        newline(1);
                    } else {
                        out.append(' ');
                    }
                    continue;
                }
                if (clause == OqlLexer.SELECT && listPerLine && prev != null && prev.getType() == OqlLexer.SELECT
                        || (clause == OqlLexer.SELECT && listPerLine && prev != null && prev.getType() == OqlLexer.DISTINCT)) {
                    newline(1);
                    out.append(text);
                    continue;
                }
                space(prev, t);
                out.append(text);
            }
            String s = out.toString();
            int end = s.length();
            while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
                end--;
            }
            return s.substring(0, end);
        }

        /** A clause keyword that starts a clause ({@code ORDER} of {@code ORDER BY}, not of an expression). */
        private boolean clauseStart(Token prev, int type) {
            if (type == OqlLexer.SET && prev != null && prev.getType() == OqlLexer.TYPE) {
                return false;
            }
            return true;
        }

        /** Characters of the select list starting at token {@code from} on one line (to FROM at the same depth). */
        private int selectListLength(int from) {
            int depth = 0;
            int n = 0;
            for (int k = from; k < tokens.size(); k++) {
                Token t = tokens.get(k);
                String s = t.getText();
                if (s.equals("(")) {
                    depth++;
                } else if (s.equals(")")) {
                    depth--;
                } else if (depth == 0 && t.getType() == OqlLexer.FROM) {
                    break;
                }
                n += s.length() + 1;
            }
            return n;
        }

        /** A space before {@code t} unless the pair is written together. */
        private void space(Token prev, Token t) {
            if (prev == null || atLineStart() || out.charAt(out.length() - 1) == ' ') {
                return;
            }
            String p = prev.getText();
            String s = t.getText();
            if (s.equals(",") || s.equals(")") || s.equals(".") || s.equals(";") || p.equals(".") || p.equals("(")
                    || p.equals(":") || p.equals("?")) {
                return;
            }
            if (s.equals("(") && FUNCTIONS.contains(prev.getType())) {
                return;                                              // count(*), upper(x), cosine_distance(…)
            }
            if (p.equals("-") && unary(prev)) {
                return;
            }
            out.append(' ');
        }

        /** Whether the {@code -} token {@code minus} is a sign (after an operator, a keyword, '(' or ','). */
        private boolean unary(Token minus) {
            int i = tokens.indexOf(minus);
            if (i == 0) {
                return true;
            }
            Token before = tokens.get(i - 1);
            String b = before.getText();
            int bt = before.getType();
            boolean value = bt == OqlLexer.IDENT || bt == OqlLexer.QUOTED_IDENT || bt == OqlLexer.INTEGER || bt == OqlLexer.DECIMAL
                    || bt == OqlLexer.STRING || b.equals(")") || bt == OqlLexer.NULL || bt == OqlLexer.TRUE || bt == OqlLexer.FALSE;
            return !value;
        }

        /** Keywords upper-case; everything else as written. */
        private static String text(Token t) {
            String s = t.getText();
            int type = t.getType();
            if (type == OqlLexer.IDENT) {
                String u = s.toUpperCase(Locale.ROOT);
                return TX_COMMANDS.contains(u) ? u : s;
            }
            String literal = OqlLexer.VOCABULARY.getLiteralName(type);
            if (literal != null && literal.length() > 2 && Character.isLetter(literal.charAt(1))) {
                return s.toUpperCase(Locale.ROOT);
            }
            return s;
        }
    }
}
