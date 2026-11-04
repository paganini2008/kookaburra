package com.github.kookaburra.face.view;

import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Databases given at start ({@code --data=DIR}, plain paths, {@code --demo}) at the edges: a directory in use by
 * another process, a file, a directory without permission, a non-empty directory that is not a database, a path
 * that does not exist yet, and a bad path between good ones. Errors are dialogs; the window stays usable.
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioStartupEdgeTest {

    private MainView view;

    @Start
    void start(Stage stage) {
        view = new MainView();
        Scene scene = StudioApplication.scene(view);
        stage.setScene(scene);
        stage.setWidth(1200);
        stage.setHeight(760);
        stage.show();
    }

    @AfterEach
    void close() {
        Platform.runLater(view::close);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean await(BooleanSupplier c) {
        for (int i = 0; i < 250 && !c.getAsBoolean(); i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        return c.getAsBoolean();
    }

    /** Opens like the application does at start (later on the FX thread: error dialogs wait for an answer). */
    private void startWith(String... args) {
        Platform.runLater(() -> StudioApplicationAccess.open(view, List.of(args)));
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Text of the error dialog, closed with OK (null if none appeared). */
    private String errorDialog(FxRobot robot) {
        if (!await(() -> robot.lookup(".dialog-pane").tryQuery().isPresent())) {
            return null;
        }
        DialogPane pane = robot.lookup(".dialog-pane").queryAs(DialogPane.class);
        String text = pane.getContentText();
        robot.interact(() -> ((Button) pane.lookupButton(ButtonType.OK)).fire());
        WaitForAsyncUtils.waitForFxEvents();
        return text;
    }

    @Test
    void databaseInUse(FxRobot robot, @TempDir Path tmp) {
        Path dir = tmp.resolve("shared db 中文");
        try (Session other = Session.open(dir)) {                  // holds the LOCK, like another Studio would
            startWith("--data=" + dir);
            assertThat(errorDialog(robot)).contains("is in use by another program");
            assertThat(view.sessions()).isEmpty();
            assertThat(view.getCenter()).isSameAs(view.welcome);   // still usable: the welcome screen
            assertThat(view.status.getText()).startsWith("error:");
        }
        startWith("--data=" + dir);                               // free again
        assertThat(await(() -> view.sessions().size() == 1)).isTrue();
        assertThat(view.status.getText()).isEqualTo("opened shared db 中文");
    }

    @Test
    void fileAndNoPermission(FxRobot robot, @TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("notes.txt"), "not a database");
        startWith("--data=" + file);
        assertThat(errorDialog(robot)).contains("is a file, not a directory");
        assertThat(view.sessions()).isEmpty();

        Path locked = Files.createDirectory(tmp.resolve("readonly"));
        File f = locked.toFile();
        assumeTrue(f.setWritable(false, false) && !Files.isWritable(locked), "needs POSIX permissions");
        try {
            startWith("--data=" + locked);
            assertThat(errorDialog(robot)).contains("permission denied");
            assertThat(view.sessions()).isEmpty();
        } finally {
            f.setWritable(true, false);
        }
    }

    @Test
    void foreignDirectoryNeedsConfirmation(FxRobot robot, @TempDir Path tmp) throws Exception {
        Path foreign = Files.createDirectory(tmp.resolve("photos"));
        Files.writeString(foreign.resolve("holiday.txt"), "keep me");
        view.foreignConfirm = d -> false;
        startWith("--data=" + foreign);
        assertThat(view.sessions()).isEmpty();
        assertThat(view.status.getText()).endsWith("not opened");
        try (var files = Files.list(foreign)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("holiday.txt");   // untouched
        }
        view.foreignConfirm = d -> true;
        startWith("--data=" + foreign);
        assertThat(await(() -> view.sessions().size() == 1)).isTrue();
        assertThat(errorDialog(robot)).isNull();
    }

    @Test
    void severalWithABadOneInBetween(FxRobot robot, @TempDir Path tmp) throws Exception {
        Path first = tmp.resolve("new/nested/first");                       // does not exist yet: created
        Path bad = Files.writeString(tmp.resolve("bad.txt"), "x");
        Path second = tmp.resolve("second");
        startWith("--data=" + first, bad.toString(), second.toString(), "--demo");
        assertThat(errorDialog(robot)).contains("is a file");
        assertThat(await(() -> view.sessions().size() == 3)).isTrue();
        assertThat(view.sessions()).extracting(Session::dir).containsExactly(first, second, null);
        assertThat(Files.isDirectory(first.resolve("wal"))).isTrue();

        startWith(second.toString());                                        // already open: focused, not opened twice
        assertThat(view.sessions()).hasSize(3);
        assertThat(view.status.getText()).contains("is already open");
    }
}
