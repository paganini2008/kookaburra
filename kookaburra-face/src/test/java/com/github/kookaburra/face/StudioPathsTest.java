package com.github.kookaburra.face;

import com.github.kookaburra.face.model.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data directory resolution: --data= &gt; kookaburra.data (relative to the jar) &gt; data next to the jar.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class StudioPathsTest {

    @TempDir
    Path tmp;

    @Test
    void dataDirectoryPrecedence() {
        Path home = tmp.resolve("bin");
        assertThat(StudioPaths.dataDir(List.of(), Map.of(), home)).isEqualTo(home.resolve("data"));
        assertThat(StudioPaths.dataDir(List.of(), Map.of(StudioPaths.DATA, "  "), home)).isEqualTo(home.resolve("data"));
        assertThat(StudioPaths.dataDir(List.of(), Map.of(StudioPaths.DATA, "../shared-db"), home))
                .isEqualTo(tmp.resolve("shared-db"));
        Path abs = tmp.resolve("elsewhere").toAbsolutePath();
        assertThat(StudioPaths.dataDir(List.of(), Map.of(StudioPaths.DATA, abs.toString()), home)).isEqualTo(abs);
        Path cli = tmp.resolve("cli");
        assertThat(StudioPaths.dataDir(List.of("--demo", "--data=" + cli), Map.of(StudioPaths.DATA, abs.toString()), home))
                .isEqualTo(cli.toAbsolutePath());
        assertThat(StudioPaths.dataDir(List.of("--data="), Map.of(), home)).isEqualTo(home.resolve("data"));
        assertThat(StudioPaths.isDatabase(tmp)).isFalse();
        Session.open(tmp.resolve("fresh")).close();                         // opened once, nothing committed
        assertThat(StudioPaths.isDatabase(tmp.resolve("fresh"))).isTrue();
        assertThat(Session.isForeignDirectory(tmp.resolve("fresh"))).isFalse();
        assertThat(Session.isForeignDirectory(tmp.resolve("missing"))).isFalse();

        assertThat(StudioPaths.logDir(Map.of(), home)).isEqualTo(home.resolve("logs"));
        assertThat(StudioPaths.logDir(Map.of(StudioPaths.LOG_DIR, "../var/log"), home)).isEqualTo(tmp.resolve("var/log"));
        assertThat(StudioPaths.logDir(Map.of(StudioPaths.LOG_DIR, abs.toString()), home)).isEqualTo(abs);
        assertThat(StudioPaths.logDir()).isAbsolute();
    }
}
