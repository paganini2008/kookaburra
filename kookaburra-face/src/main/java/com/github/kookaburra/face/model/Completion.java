package com.github.kookaburra.face.model;

import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OQL auto-completion for the console: classes after FROM / JOIN / UPDATE, fields after {@code alias.},
 * otherwise keywords, functions and aliases.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Completion {

    static final List<String> KEYWORDS = List.of("SELECT", "DISTINCT", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY",
            "ASC", "DESC", "NULLS FIRST", "NULLS LAST", "LIMIT", "OFFSET", "AS", "NEW", "JOIN", "LEFT JOIN", "UPDATE", "SET",
            "DELETE", "EXPLAIN", "AND", "OR", "NOT", "BETWEEN", "IN", "LIKE", "ESCAPE", "IS NULL", "IS NOT NULL", "IS EMPTY",
            "MEMBER OF", "TYPE", "CASE", "WHEN", "THEN", "ELSE", "END", "TRUE", "FALSE", "NULL", "DATE", "TIMESTAMP", "INSTANT",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "UPPER", "LOWER", "TRIM", "LENGTH", "SUBSTRING", "ABS", "MOD", "COALESCE",
            "NULLIF", "SIZE", "CONCAT", "BEGIN", "COMMIT", "ROLLBACK", "ISOLATION LEVEL SNAPSHOT");

    private static final Pattern ALIAS = Pattern.compile(
            "(?i)\\b(?:from|join|update)\\s+([\\w.$]+)(?:\\s+(?:as\\s+)?(?!where\\b|join\\b|left\\b|inner\\b|set\\b|order\\b|group\\b)(\\w+))?");

    private Completion() {
    }

    /** Word being typed at {@code caret} (letters, digits, {@code _ $ .}). */
    public static String prefix(String text, int caret) {
        int i = caret;
        while (i > 0 && (Character.isLetterOrDigit(text.charAt(i - 1)) || "_$.".indexOf(text.charAt(i - 1)) >= 0)) {
            i--;
        }
        return text.substring(i, caret);
    }

    public static List<String> suggest(String text, int caret, Schema schema) {
        String prefix = prefix(text, caret);
        Map<String, ClassDef> aliases = aliases(text, schema);
        TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        int dot = prefix.indexOf('.');
        if (dot > 0) {
            String alias = prefix.substring(0, dot);
            ClassDef c = aliases.get(alias);
            if (c != null) {
                String partial = prefix.substring(dot + 1);
                for (String path : paths(c, schema)) {
                    if (path.startsWith(partial)) {
                        out.add(alias + "." + path);
                    }
                }
            }
            return new ArrayList<>(out);
        }
        String before = text.substring(0, caret - prefix.length()).stripTrailing().toUpperCase(Locale.ROOT);
        String lower = prefix.toLowerCase(Locale.ROOT);
        if (before.endsWith("FROM") || before.endsWith("JOIN") || before.endsWith("UPDATE")) {
            for (ClassDef c : schema.classes()) {
                if (c.simpleName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    out.add(c.simpleName());
                }
            }
            return new ArrayList<>(out);
        }
        for (String k : KEYWORDS) {
            if (k.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(k);
            }
        }
        for (String a : aliases.keySet()) {
            if (a.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(a);
            }
        }
        return new ArrayList<>(out);
    }

    /** Aliases declared in the statement, mapped to their classes. */
    static Map<String, ClassDef> aliases(String text, Schema schema) {
        Map<String, ClassDef> out = new TreeMap<>();
        Matcher m = ALIAS.matcher(text);
        while (m.find()) {
            ClassDef c = find(schema, m.group(1));
            if (c != null && m.group(2) != null) {
                out.put(m.group(2), c);
            }
        }
        return out;
    }

    private static ClassDef find(Schema schema, String name) {
        ClassDef c = schema.byName(name);
        if (c != null) {
            return c;
        }
        for (ClassDef x : schema.classes()) {
            if (x.simpleName().equals(name)) {
                return x;
            }
        }
        return null;
    }

    /** All queryable paths of a class and its subclasses (embedded paths flattened). */
    static List<String> paths(ClassDef c, Schema schema) {
        TreeSet<String> out = new TreeSet<>();
        ClassDef root = schema.byName(c.collection());
        out.add(root.idField());
        if (root.versionField() != null) {
            out.add(root.versionField());
        }
        for (Integer id : schema.subclassIds(c.name())) {
            collect(schema.byId(id).current().fields(), "", out);
        }
        return new ArrayList<>(out);
    }

    private static void collect(List<FieldDef> fields, String prefix, TreeSet<String> out) {
        for (FieldDef f : fields) {
            out.add(prefix + f.name());
            if (f.type().kind() == ValueType.EMBEDDED) {
                collect(f.type().fields(), prefix + f.name() + ".", out);
            }
        }
    }
}
