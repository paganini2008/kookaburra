package com.github.kookaburra;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Project settings. {@code kookaburra.properties} is the only place defaults are defined: it is bundled in the
 * core jar ({@link #defaults()}); an external {@code kookaburra.properties} overrides it, and JVM system
 * properties with the same {@code kookaburra.*} keys override both (command-line arguments of the tools win).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Settings {

    /** Default file name. */
    public static final String FILE = "kookaburra.properties";
    /** Key prefix of all settings. */
    public static final String PREFIX = "kookaburra.";

    private Settings() {
    }

    private static Map<String, String> bundled;

    /** Bundled defaults (the project's {@code kookaburra.properties}). */
    public static synchronized Map<String, String> defaults() {
        if (bundled == null) {
            Properties p = new Properties();
            try (InputStream in = Settings.class.getResourceAsStream("/" + FILE)) {
                if (in == null) {
                    throw new IllegalStateException(FILE + " is missing from the class path");
                }
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            bundled = Collections.unmodifiableMap(filter(p));
        }
        return bundled;
    }

    /** Required setting from {@link #defaults()} merged with {@code overrides}. */
    public static String get(Map<String, String> overrides, String key) {
        String v = overrides.get(key);
        if (v == null) {
            v = defaults().get(key);
        }
        if (v == null) {
            throw new IllegalStateException("setting " + key + " is not defined in " + FILE);
        }
        return v;
    }

    /** Integer setting. */
    public static int integer(Map<String, String> settings, String key) {
        String v = get(settings, key);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + ": invalid integer '" + v + "'");
        }
    }

    private static Map<String, String> filter(Properties p) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : p.stringPropertyNames()) {
            String v = p.getProperty(k).strip();
            if (k.startsWith(PREFIX) && !v.isEmpty()) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** {@code kookaburra.*} entries of a properties file (UTF-8); empty if it does not exist. */
    public static Map<String, String> read(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        out.putAll(filter(p));
        return out;
    }

    /** Bundled defaults, overridden by an external file, overridden by {@code -Dkookaburra.*} system properties. */
    public static Map<String, String> load(Path file) {
        Map<String, String> merged = new LinkedHashMap<>(defaults());
        merged.putAll(read(file));
        for (String k : System.getProperties().stringPropertyNames()) {
            String v = System.getProperty(k);
            if (k.startsWith(PREFIX) && v != null && !v.isBlank()) {
                merged.put(k, v.strip());
            }
        }
        return merged;
    }
}
