package com.github.kookaburra;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Databases for the shared test suites. {@code -Dkookaburra.test.storage=disk} (the {@code disk-suite} surefire
 * execution) turns every one of them into an on-disk database in a fresh directory (segment files, WAL, mmap),
 * deleted when the JVM exits; otherwise they are in memory.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public class TestDbs {

    public static final String STORAGE = "kookaburra.test.storage";

    private TestDbs() {
    }

    public static boolean onDisk() {
        return "disk".equals(System.getProperty(STORAGE));
    }

    public static ObjectDB create() {
        return create(Config.defaults());
    }

    public static ObjectDB create(Config config) {
        if (!onDisk()) {
            return ObjectDB.inMemory(config);
        }
        try {
            Path dir = Files.createTempDirectory("kookaburra-suite-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(dir)));
            return ObjectDB.open(dir.resolve("db"), config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort at shutdown
        }
    }
}
