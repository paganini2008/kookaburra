package com.github.kookaburra.face;

import javafx.application.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Entry point that does not extend {@link Application}, so the app starts from a plain class path.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Launcher {

    private Launcher() {
    }

    /**
     * Entry point of the fat jar ({@code java -jar} or a double-click). Logging (logback) goes to
     * {@code kookaburra.log-dir} at {@code kookaburra.log-level}, both from {@code kookaburra.properties};
     * they are passed to {@code logback.xml} as system properties before the first logger is created.
     */
    public static void main(String[] args) {
        System.setProperty("kookaburra.log.dir", StudioPaths.logDir().toString());
        System.setProperty("kookaburra.log.level", StudioPaths.settings().getOrDefault(StudioPaths.LOG_LEVEL, "INFO"));
        Logger log = LoggerFactory.getLogger(Launcher.class);
        log.info("Kookaburra Studio starting (Java {}, {} {}), data {}", System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"), StudioPaths.dataDir(List.of(args)));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.error("uncaught exception in {}", t.getName(), e));
        Application.launch(StudioApplication.class, args);
    }

}
