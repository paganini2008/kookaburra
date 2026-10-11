package com.github.kookaburra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kookaburra.properties: bundled defaults, file and system-property overrides, value parsing.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class SettingsTest {

    @Test
    void bundledDefaultsAreTheBestPracticeValues() {
        Config c = Config.defaults();
        assertThat(c.memSegmentBytes()).isEqualTo(64L << 20);
        assertThat(c.segmentMaxBytes()).isEqualTo(512L << 20);
        assertThat(c.compactionFanIn()).isEqualTo(8);
        assertThat(c.maxSegments()).isEqualTo(64);
        assertThat(c.bloomFpp()).isEqualTo(0.01);
        assertThat(c.durability()).isEqualTo(Durability.SYNC);
        assertThat(c.groupCommitMicros()).isEqualTo(200);
        assertThat(c.sequencerMaxGroup()).isEqualTo(512);
        assertThat(c.autoPostingsRatio()).isEqualTo(16);
        assertThat(c.postingsOrMax()).isEqualTo(64);
        assertThat(c.writeBacklog()).isEqualTo(10);
        assertThat(Settings.defaults()).containsKeys("kookaburra.theme", "kookaburra.page-size").doesNotContainKey("kookaburra.data");
    }

    @Test
    void fileAndSystemPropertiesOverride(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(Settings.FILE);
        Files.writeString(file, "# comment\nkookaburra.mem-segment-bytes = 2MB\nkookaburra.durability=async\nother.key=1\nkookaburra.max-segments=\n");
        assertThat(Settings.read(file)).containsOnlyKeys("kookaburra.mem-segment-bytes", "kookaburra.durability");
        assertThat(Settings.read(dir.resolve("missing"))).isEmpty();
        assertThat(Settings.read(null)).isEmpty();
        System.setProperty("kookaburra.compaction-fan-in", "4");
        try {
            Config c = Config.load(file);
            assertThat(c.memSegmentBytes()).isEqualTo(2L << 20);
            assertThat(c.durability()).isEqualTo(Durability.ASYNC);
            assertThat(c.compactionFanIn()).isEqualTo(4);
            assertThat(c.maxSegments()).isEqualTo(64);
        } finally {
            System.clearProperty("kookaburra.compaction-fan-in");
        }
        assertThat(Config.load().memSegmentBytes()).isEqualTo(64L << 20);
    }

    @Test
    void parsing() {
        assertThat(Config.bytes("1KB", "k")).isEqualTo(1024);
        assertThat(Config.bytes("1.5gb", "k")).isEqualTo((long) (1.5 * (1L << 30)));
        assertThat(Config.bytes("4096", "k")).isEqualTo(4096);
        assertThat(Config.bytes("10B", "k")).isEqualTo(10);
        assertThat(Config.bytes("3GB", Config.SEGMENT_MAX_BYTES)).isEqualTo(1_500_000_000L);
        assertThatThrownBy(() -> Config.bytes("lots", "k")).hasMessageContaining("invalid size");
        assertThatThrownBy(() -> Config.fromSettings(Map.of(Config.MAX_SEGMENTS, "x"))).hasMessageContaining("invalid integer");
        assertThatThrownBy(() -> Config.fromSettings(Map.of(Config.BLOOM_FPP, "x"))).hasMessageContaining("invalid number");
        assertThatThrownBy(() -> Config.fromSettings(Map.of(Config.DURABILITY, "maybe"))).hasMessageContaining("SYNC or ASYNC");
        assertThatThrownBy(() -> Settings.get(Map.of(), "kookaburra.nope")).hasMessageContaining("not defined");
        assertThatThrownBy(() -> Config.defaults().withPostingsOrMax(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Config.defaults().withSequencerMaxGroup(3).withAutoPostingsRatio(2).withWriteBacklog(5).withBloomFpp(0.1)
                .withClassLoader(null).sequencerMaxGroup()).isEqualTo(3);
    }
}
