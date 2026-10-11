package com.github.kookaburra.face;

import com.github.kookaburra.Settings;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Studio settings and paths. All settings come from {@code kookaburra.properties} (bundled defaults, the file
 * next to the Studio jar, {@code -Dkookaburra.*}); the data directory can also be given as {@code --data=DIR}.
 * An empty {@code kookaburra.data} means {@code data/} next to the jar (the working directory outside a jar).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class StudioPaths {

    public static final String ARG = "--data=";
    public static final String DATA = "kookaburra.data";
    public static final String THEME = "kookaburra.theme";
    public static final String PAGE_SIZE = "kookaburra.page-size";
    public static final String CONSOLE_PAGE_SIZE = "kookaburra.console-page-size";
    public static final String DEMO_CUSTOMERS = "kookaburra.demo-customers";
    public static final String LOG_DIR = "kookaburra.log-dir";
    public static final String LOG_LEVEL = "kookaburra.log-level";

    private static Map<String, String> settings;

    private StudioPaths() {
    }

    /** Settings: bundled defaults, then {@code kookaburra.properties} next to the jar, then system properties. */
    public static synchronized Map<String, String> settings() {
        if (settings == null) {
            settings = Settings.load(home().resolve(Settings.FILE));
        }
        return settings;
    }

    public static int integer(String key) {
        return Settings.integer(settings(), key);
    }

    /** Log directory: {@code kookaburra.log-dir} (relative to the jar) or {@code logs} next to the jar. */
    public static Path logDir() {
        return logDir(settings(), home());
    }

    static Path logDir(Map<String, String> settings, Path home) {
        String configured = settings.get(LOG_DIR);
        return home.resolve(configured == null || configured.isBlank() ? "logs" : configured.strip()).toAbsolutePath().normalize();
    }

    public static Path dataDir(List<String> args) {
        return dataDir(args, settings(), home());
    }

    static Path dataDir(List<String> args, Map<String, String> settings, Path home) {
        for (String a : args) {
            if (a.startsWith(ARG) && a.length() > ARG.length()) {
                return Path.of(a.substring(ARG.length())).toAbsolutePath();
            }
        }
        String configured = settings.get(DATA);
        if (configured != null && !configured.isBlank()) {
            // relative paths are relative to the jar, not to the working directory (a double-click starts in "/")
            return home.resolve(configured.strip()).toAbsolutePath().normalize();
        }
        return home.resolve("data").toAbsolutePath();
    }

    /** True when the directory holds a Kookaburra database. */
    public static boolean isDatabase(Path dir) {
        // CURRENT appears with the first commit; LOCK + schema/ + wal/ as soon as a database was opened there
        return Files.exists(dir.resolve("CURRENT"))
                || Files.exists(dir.resolve("LOCK")) && Files.isDirectory(dir.resolve("schema")) && Files.isDirectory(dir.resolve("wal"));
    }

    /** Directory of the Studio jar, or the working directory. */
    static Path home() {
        try {
            Path code = Path.of(StudioPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return code.toString().endsWith(".jar") ? code.getParent() : Path.of("").toAbsolutePath();
        } catch (URISyntaxException | RuntimeException e) {
            return Path.of("").toAbsolutePath();
        }
    }
}
