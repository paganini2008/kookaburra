package com.github.kookaburra.text;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyzers by name, as named in {@code @FullText(analyzer = …)} and stored in the schema: {@code english} (the
 * default) and {@code standard} are built in; register others before opening a database that uses them.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public final class Analyzers {

    public static final String ENGLISH = "english";
    public static final String STANDARD = "standard";
    public static final String DEFAULT = ENGLISH;

    private static final Map<String, Analyzer> BY_NAME = new ConcurrentHashMap<>(Map.of(
            ENGLISH, new EnglishAnalyzer(), STANDARD, new StandardAnalyzer()));

    private Analyzers() {
    }

    /** Registers (or replaces) an analyzer; {@code english} and {@code standard} cannot be replaced. */
    public static void register(String name, Analyzer analyzer) {
        if (name == null || name.isBlank() || analyzer == null) {
            throw new IllegalArgumentException("an analyzer needs a name and an implementation");
        }
        if (name.equals(ENGLISH) || name.equals(STANDARD)) {
            throw new IllegalArgumentException("the built-in analyzer '" + name + "' cannot be replaced");
        }
        BY_NAME.put(name, analyzer);
    }

    /** The analyzer named {@code name} (null = the default); fails for a name that is not registered. */
    public static Analyzer get(String name) {
        Analyzer a = BY_NAME.get(name == null ? DEFAULT : name);
        if (a == null) {
            throw new IllegalStateException("full-text analyzer '" + name + "' is not registered: call Analyzers.register(\""
                    + name + "\", …) before opening the database");
        }
        return a;
    }
}
