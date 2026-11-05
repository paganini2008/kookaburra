package com.github.kookaburra.face.view;

import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The menu bar, in memory and on disk: menus and items are there, each item is enabled exactly when its toolbar
 * button is (before and after opening a database, in and out of a transaction), items run the same actions (tabs,
 * query, export, theme kept in step with the toolbar, help links, quit), formatting has no shortcut.
 *
 * @Author: Fred Feng
 * @Date: 06/11/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioMenuTest {

    private MainView view;

    @Start
    void start(Stage stage) {
        view = new MainView();
        Scene scene = StudioApplication.scene(view);
        stage.setScene(scene);
        stage.setWidth(1320);
        stage.setHeight(820);
        stage.show();
    }

    @AfterEach
    void close() {
        Platform.runLater(view::close);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void settle() {
        for (int i = 0; i < 500 && view.tasks.pending() > 0; i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    private MenuItem item(String id) {
        for (Menu m : view.menuBar.getMenus()) {
            MenuItem found = find(m, id);
            if (found != null) {
                return found;
            }
        }
        throw new AssertionError(id);
    }

    private static MenuItem find(Menu m, String id) {
        for (MenuItem i : m.getItems()) {
            if (id.equals(i.getId())) {
                return i;
            }
            if (i instanceof Menu sub) {
                MenuItem f = find(sub, id);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void menus(boolean onDisk, @TempDir Path tmp, FxRobot robot) {
        assertThat(view.menuBar.getMenus()).extracting(Menu::getText)
                .containsExactly("File", "Edit", "Query", "Transaction", "View", "Help");
        assertThat(view.menuBar.isUseSystemMenuBar()).isEqualTo(StudioMenu.systemMenuBar());   // headless: in the window
        // no database: database actions are off, like their buttons
        for (String id : List.of("menu-close", "menu-flush", "menu-backup", "menu-export-java", "menu-new", "menu-begin")) {
            assertThat(item(id).isDisable()).as(id).isTrue();
        }
        assertThat(item("menu-open").isDisable()).isFalse();
        assertThat(item("menu-open").getAccelerator().getName()).isEqualTo("Shortcut+O");
        assertThat(item("menu-format").getAccelerator()).isNull();           // formatting has no shortcut

        Session s = onDisk ? Session.open(tmp.resolve("db")) : Session.demo();
        robot.interact(() -> view.open(s));
        settle();
        for (String id : List.of("menu-close", "menu-backup", "menu-export-java", "menu-begin")) {
            assertThat(item(id).isDisable()).as(id).isFalse();
        }
        assertThat(item("menu-flush").isDisable()).isEqualTo(view.flush.isDisable());
        assertThat(item("menu-commit").isDisable()).isTrue();
        robot.interact(() -> item("menu-begin").fire());
        settle();
        assertThat(item("menu-commit").isDisable()).isFalse();
        assertThat(item("menu-rollback").isDisable()).isFalse();
        robot.interact(() -> item("menu-commit").fire());
        settle();
        assertThat(item("menu-commit").isDisable()).isTrue();

        robot.interact(() -> item("menu-tab-schema").fire());
        assertThat(view.tabs.getSelectionModel().getSelectedIndex()).isEqualTo(2);
        robot.interact(() -> {
            view.query.editor.replaceText("select p from Product p");
            item("menu-format").fire();
        });
        assertThat(view.tabs.getSelectionModel().getSelectedIndex()).isEqualTo(1);
        assertThat(view.query.editor.getText()).startsWith("SELECT");
        robot.interact(() -> item("menu-tab-data").fire());
        assertThat(view.tabs.getSelectionModel().getSelectedIndex()).isZero();

        // theme in step with the toolbar menu, both ways
        RadioMenuItem light = (RadioMenuItem) item("menu-theme-PRIMER_LIGHT");
        robot.interact(light::fire);
        assertThat(view.theme()).isEqualTo(Theme.PRIMER_LIGHT);
        robot.interact(() -> view.themes.getItems().stream().filter(i -> "theme-NEON".equals(i.getId())).findFirst().orElseThrow().fire());
        assertThat(((RadioMenuItem) item("menu-theme-NEON")).isSelected()).isTrue();
        assertThat(light.isSelected()).isFalse();

        List<String> urls = new ArrayList<>();
        int[] quits = {0};
        robot.interact(() -> {
            view.browser = urls::add;
            view.quit = () -> quits[0]++;
            item("menu-docs").fire();
            item("menu-github").fire();
            item("menu-quit").fire();
        });
        assertThat(urls).containsExactly(StudioMenu.DOCS, "https://github.com/paganini2008/kookaburra");
        assertThat(quits[0]).isEqualTo(1);

        robot.interact(() -> item("menu-export-java").fire());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup(".k-java-export").tryQuery()).isPresent();
        Shots.save(robot.lookup(".k-java-export").query().getScene(), "menu-export-java");
        robot.interact(() -> robot.lookup(".k-java-export").query().getScene().getWindow().hide());
        if (!view.menuBar.isUseSystemMenuBar()) {                             // macOS on screen: the system menu bar
            robot.clickOn("#menu-file");
            WaitForAsyncUtils.waitForFxEvents();
            Shots.save(view.getScene(), "menu-file");
            robot.interact(() -> ((Menu) view.menuBar.getMenus().get(0)).hide());
        }
    }
}
