package com.github.kookaburra.face.view;

import com.github.kookaburra.face.model.Export;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.util.Locale;

/**
 * Studio's menu bar (File, Edit, Query, Transaction, View, Help), the system menu bar on macOS. Every item runs the
 * same action as its toolbar or tab button and is enabled exactly when that button is, so menu and toolbar never
 * disagree. Shortcuts follow desktop conventions (Shortcut = Cmd on macOS, Ctrl elsewhere); OQL formatting has none
 * on purpose.
 *
 * @Author: Fred Feng
 * @Date: 06/11/2026
 * @Version 1.0.0
 */
final class StudioMenu {

    static final String DOCS = "https://paganini2008.github.io/kookaburra/";

    private StudioMenu() {
    }

    static MenuBar build(MainView v) {
        Menu file = menu("File",
                item("menu-open", "Open database…", "mdi2f-folder-open-outline", v.open, shortcut(KeyCode.O)),
                item("menu-demo", "Open demo database", "mdi2d-database-eye-outline", v.demo, null),
                item("menu-close", "Close database", "mdi2d-database-remove-outline", v.closeDb, null),
                new SeparatorMenuItem(),
                item("menu-flush", "Flush to disk", "mdi2c-content-save-outline", v.flush, shortcut(KeyCode.S)),
                item("menu-backup", "Back up…", "mdi2b-backup-restore", v.backup, null),
                new SeparatorMenuItem(),
                exportData(v),
                item("menu-export-java", "Export schema as Java…", "mdi2l-language-java", v.exportJava, null),
                new SeparatorMenuItem(),
                action("menu-quit", "Quit", null, () -> v.quit.run(), shortcut(KeyCode.Q), null));

        Menu edit = menu("Edit",
                item("menu-new", "New object…", "mdi2p-plus", v.add, shortcut(KeyCode.N)),
                item("menu-edit", "Edit object…", "mdi2p-pencil-outline", v.edit, shortcut(KeyCode.E)),
                item("menu-delete", "Delete selected objects", "mdi2d-delete-outline", v.delete, null));

        Menu query = menu("Query",
                tabThen(v, "menu-run", "Run", "mdi2p-play", v.query.run, 1),
                tabThen(v, "menu-explain", "Explain", "mdi2f-file-tree-outline", v.query.explain, 1),
                tabThen(v, "menu-analyze", "Explain analyze", "mdi2t-timer-outline", v.query.analyze, 1),
                new SeparatorMenuItem(),
                tabThen(v, "menu-format", "Format OQL", "mdi2f-format-align-left", v.query.format, 1));

        Menu tx = menu("Transaction",
                item("menu-begin", "Begin", "mdi2p-play-circle-outline", v.txBegin, null),
                item("menu-commit", "Commit", "mdi2c-check-circle-outline", v.txCommit, null),
                item("menu-rollback", "Roll back", "mdi2u-undo-variant", v.txRollback, null));

        Menu view = menu("View",
                tab(v, "menu-tab-data", "Data", 0, KeyCode.DIGIT1),
                tab(v, "menu-tab-query", "Query", 1, KeyCode.DIGIT2),
                tab(v, "menu-tab-schema", "Schema", 2, KeyCode.DIGIT3),
                tab(v, "menu-tab-stats", "Stats", 3, KeyCode.DIGIT4),
                new SeparatorMenuItem(),
                item("menu-refresh", "Refresh", "mdi2r-refresh", v.refresh, new KeyCodeCombination(KeyCode.F5)),
                themes(v));

        Menu help = menu("Help",
                action("menu-docs", "Documentation", "mdi2b-book-open-variant", () -> v.browser.accept(DOCS), null, null),
                action("menu-github", "GitHub", "mdi2g-github", () -> v.browser.accept(About.info().getProperty("github")), null,
                        null),
                new SeparatorMenuItem(),
                item("menu-about", "About Kookaburra Studio", "mdi2i-information-outline", v.about, null));

        MenuBar bar = new MenuBar(file, edit, query, tx, view, help);
        bar.setId("menu-bar");
        bar.getStyleClass().add("k-menu-bar");
        bar.setUseSystemMenuBar(systemMenuBar());
        return bar;
    }

    /** macOS draws the menu at the top of the screen (not in headless tests). */
    static boolean systemMenuBar() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                && !"monocle".equalsIgnoreCase(System.getProperty("glass.platform"))
                && !Boolean.getBoolean("testfx.headless");
    }

    private static Menu menu(String text, MenuItem... items) {
        Menu m = new Menu(text, null, items);
        m.setId("menu-" + text.toLowerCase(Locale.ROOT));
        return m;
    }

    private static KeyCombination shortcut(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN);
    }

    /** An item that fires {@code button} and is enabled when it is. */
    private static MenuItem item(String id, String text, String icon, ButtonBase button, KeyCombination key) {
        return action(id, text, icon, button::fire, key, button.disableProperty());
    }

    private static MenuItem action(String id, String text, String icon, Runnable run, KeyCombination key,
                                   ObservableBooleanValue disabled) {
        MenuItem m = new MenuItem(text, icon == null ? null : (Node) Icons.of(icon));
        m.setId(id);
        m.setOnAction(e -> run.run());
        if (key != null) {
            m.setAccelerator(key);
        }
        if (disabled != null) {
            m.disableProperty().bind(disabled);
        }
        return m;
    }

    /** Shows the tab {@code index} first, then fires {@code button} (query actions act on the console). */
    private static MenuItem tabThen(MainView v, String id, String text, String icon, ButtonBase button, int index) {
        return action(id, text, icon, () -> {
            v.tabs.getSelectionModel().select(index);
            button.fire();
        }, null, button.disableProperty());
    }

    private static MenuItem tab(MainView v, String id, String text, int index, KeyCode key) {
        return action(id, text, null, () -> v.tabs.getSelectionModel().select(index), shortcut(key), null);
    }

    private static Menu exportData(MainView v) {
        Menu m = new Menu("Export data", Icons.of("mdi2e-export"));
        m.setId("menu-export-data");
        for (Export.Format f : Export.Format.values()) {
            MenuItem i = new MenuItem(f.name() + "…");
            i.setId("menu-export-" + f.name().toLowerCase(Locale.ROOT));
            i.setOnAction(e -> v.data.exportAs(f));
            m.getItems().add(i);
        }
        m.disableProperty().bind(v.data.export.disableProperty());
        return m;
    }

    /** The theme choices, kept in step with the toolbar's theme menu. */
    private static Menu themes(MainView v) {
        Menu m = new Menu("Theme", Icons.of("mdi2p-palette-outline"));
        m.setId("menu-theme");
        ToggleGroup group = new ToggleGroup();
        for (MenuItem source : v.themes.getItems()) {
            RadioMenuItem r = new RadioMenuItem(source.getText());
            r.setId("menu-" + source.getId());
            r.setToggleGroup(group);
            if (source instanceof RadioMenuItem s) {
                r.selectedProperty().bindBidirectional(s.selectedProperty());
            }
            r.setOnAction(e -> source.fire());
            m.getItems().add(r);
        }
        return m;
    }
}
