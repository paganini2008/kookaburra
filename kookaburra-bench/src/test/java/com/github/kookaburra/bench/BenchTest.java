package com.github.kookaburra.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke run of the whole benchmark on a small data set: every phase runs and the data stays consistent.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class BenchTest {

    @TempDir
    Path tmp;

    @Test
    void smallRunProducesReportWithoutProblems() throws IOException {
        Path out = tmp.resolve("out").resolve("report.md");
        Bench.main(new String[]{"--n=20_000", "--threads=4", "--seconds=1", "--iterations=3",
                "--dir=" + tmp.resolve("db"), "--out=" + out});
        String report = Files.readString(out);
        assertThat(tmp.resolve("db")).doesNotExist();            // test data removed after the run
        assertThat(report).contains("| objects | 20,000 |", "bulk insert", "get(oid)", "GROUP BY city", "Mixed",
                "compact()", "reopen", "heap used", "Transactions", "transfer", "hot counter",
                "OQL DELETE of all", "after deleting everything", "**Problems**: none");

        Bench again = new Bench(Bench.Options.parse("--n=5000", "--threads=2", "--seconds=1", "--iterations=3",
                "--dir=" + tmp.resolve("db"), "--out=" + out, "--keep"));
        again.run();
        assertThat(tmp.resolve("db")).isDirectory();             // --keep
        new Bench(Bench.Options.parse("--n=1000", "--threads=1", "--seconds=1", "--iterations=3",
                "--dir=" + tmp.resolve("db"), "--out=" + out)).run();   // wipes the kept directory first
        assertThat(again.problems()).isEmpty();
        assertThat(again.results()).extracting(Bench.Result::section).contains("Write", "Read", "Query", "Storage");
    }

    @Test
    void optionsAndFormatting() {
        Bench.Options o = Bench.Options.parse();
        assertThat(o.n()).isEqualTo(1_000_000);
        assertThat(o.threads()).isEqualTo(16);
        assertThatThrownBy(() -> Bench.Options.parse("--bogus=1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Bench.Result("A", "m", "v", "t", true).row()).endsWith("✅ |");
        assertThat(new Bench.Result("A", "m", "v", "t", false).row()).endsWith("❌ |");

        Stats s = new Stats();
        assertThat(s.percentile(50)).isZero();
        assertThat(s.mean()).isZero();
        for (long i = 1; i <= 2000; i++) {
            s.add(i * 1000);
        }
        assertThat(s.count()).isEqualTo(2000);
        assertThat(s.percentile(50)).isEqualTo(1_000_000);
        assertThat(s.percentile(100)).isEqualTo(2_000_000);
        assertThat(s.mean()).isEqualTo(1_000_500.0);
        assertThat(Stats.format(1500)).isEqualTo("1.50 µs");
        assertThat(Stats.format(2_500_000)).isEqualTo("2.500 ms");
        assertThat(Stats.format(25_000_000)).isEqualTo("25.0 ms");
        Bench.delete(Path.of("does-not-exist-" + System.nanoTime()));
        assertThatThrownBy(() -> Bench.io(() -> {
            throw new IOException("disk");
        })).isInstanceOf(UncheckedIOException.class);
        Thread.currentThread().interrupt();
        Bench.quietly(() -> Thread.sleep(10_000));     // interrupted: returns at once, flag kept
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void workerFailuresAreReported() {
        Bench b = new Bench(Bench.Options.parse("--threads=2"));
        Stats s = b.runThreads(2, 1, rnd -> {
            throw new IllegalStateException("boom");
        });
        assertThat(s.count()).isZero();
        assertThat(b.problems()).hasSize(2).allMatch(p -> p.contains("boom"));
        for (int i = 0; i < 60; i++) {
            b.problem("p" + i);
        }
        assertThat(b.problems()).hasSize(50);            // capped
    }
}
