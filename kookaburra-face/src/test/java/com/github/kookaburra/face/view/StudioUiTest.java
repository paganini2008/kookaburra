package com.github.kookaburra.face.view;

import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.model.ClassInfo;
import com.github.kookaburra.face.model.Export;
import com.github.kookaburra.face.model.SegmentInfo;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives Studio headless (Monocle) through its flows; saves screenshots to target/screenshots.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioUiTest {

    private static final String CUSTOMER = "com.github.kookaburra.face.demo.Customer";

    private MainView view;
    private Scene scene;
    private Stage stage;

    @Start
    void start(Stage stage) {
        this.stage = stage;
        view = new MainView();
        scene = StudioApplication.scene(view);
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

    private void await(BooleanSupplier condition) {
        for (int i = 0; i < 500 && !condition.getAsBoolean(); i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void openDemo(FxRobot robot) {
        Session s = Session.demo();
        robot.interact(() -> view.open(s));
        settle();
    }

    private void selectClass(FxRobot robot, String simpleName) {
        robot.interact(() -> {
            for (TreeItem<ClassInfo> i : view.tree.getRoot().getChildren()) {
                if (i.getValue().simpleName().equals(simpleName)) {
                    view.tree.getSelectionModel().select(i);
                }
                for (TreeItem<ClassInfo> c : i.getChildren()) {
                    if (c.getValue().simpleName().equals(simpleName)) {
                        view.tree.getSelectionModel().select(c);
                    }
                }
            }
        });
        settle();
    }

    @Test
    void welcomeThenDemo(FxRobot robot) {
        Shots.save(scene, "01-welcome");
        robot.clickOn("#welcome-demo");
        settle();
        assertThat(view.session()).isNotNull();
        assertThat(stage.getTitle()).contains("demo");
        selectClass(robot, "Customer");
        assertThat(view.data.table.getItems()).isNotEmpty();
        assertThat(view.status.getText()).contains("objects");
        assertThat(view.schema.metadata.className.getText()).isEqualTo(CUSTOMER);
        assertThat(view.schema.metadata.fields.getItems()).extracting(f -> f.path()).contains("name", "address", "address.city", "tags");
        selectClass(robot, "Book");
        assertThat(view.schema.metadata.fields.getItems()).anyMatch(f -> f.path().equals("title") && f.declaredIn().equals("Product"));
        assertThat(view.schema.metadata.fields.getItems()).anyMatch(f -> f.path().equals("author") && f.declaredIn().equals("Book"));
        selectClass(robot, "Customer");
        Shots.save(scene, "02-data");
        robot.interact(() -> view.tabs.getSelectionModel().select(1));
        robot.interact(() -> view.query.setText("SELECT c.tier, COUNT(*) AS n, AVG(c.age) FROM Customer c GROUP BY c.tier ORDER BY n DESC"));
        robot.clickOn("#run");
        settle();
        assertThat(view.query.results.getItems()).hasSize(3);
        assertThat(view.query.results.getColumns().get(0).getText()).isEqualTo("#");
        Shots.save(scene, "03-query");
        robot.interact(() -> view.tabs.getSelectionModel().select(2));
        settle();
        assertThat(view.schema.segments.getItems()).extracting(SegmentInfo::collection).contains("Customer", "Product", "Purchase");
        assertThat(view.schema.summary.getText()).contains("segments", "live of");
        assertThat(view.schema.layouts.getText()).contains("Customer");
        // the field table fills the left column below the facts (no clipped rows)
        assertThat(view.schema.metadata.fields.getHeight()).isGreaterThan(view.schema.getHeight() / 2);
        Shots.save(scene, "04-schema");
    }

    @Test
    void filterSortAndPage(FxRobot robot, @TempDir Path tmp) throws IOException {
        openDemo(robot);
        selectClass(robot, "Customer");
        assertThat(view.data.pageLabel.getText()).isEqualTo("1 / 5");
        assertThat(rowNumbers(robot)).startsWith("1", "2");
        robot.clickOn("#next");
        settle();
        assertThat(view.data.pageLabel.getText()).isEqualTo("2 / 5");
        assertThat(rowNumbers(robot)).startsWith("51", "52");
        robot.clickOn("#data-refresh");                  // reload keeps the current page
        settle();
        assertThat(view.data.pageLabel.getText()).isEqualTo("2 / 5");
        robot.clickOn("#prev");
        settle();
        assertThat(view.data.pageLabel.getText()).isEqualTo("1 / 5");
        robot.interact(() -> view.data.pageSize.setValue(100));
        settle();
        assertThat(view.data.pageLabel.getText()).isEqualTo("1 / 3");

        robot.clickOn("#filter").write("x.age > 70");
        robot.clickOn(view.data.apply);
        settle();
        long total = view.data.page().total();
        assertThat(total).isPositive().isLessThan(250);
        assertThat(view.data.table.getItems()).allMatch(o -> (Integer) o.get("age") > 70);
        Path exported = tmp.resolve("customers.csv");
        robot.interact(() -> view.data.exportChooser = f -> f == Export.Format.CSV ? exported.toFile() : null);
        robot.clickOn("#export");
        robot.clickOn("#export-csv");
        settle();
        await(() -> view.status.getText().startsWith("exported"));
        assertThat(view.status.getText()).isEqualTo("exported " + total + " objects → customers.csv");
        assertThat(Files.readAllLines(exported)).hasSize((int) total + 1);
        robot.interact(() -> view.data.exportAs(Export.Format.JSON));      // chooser cancelled: nothing happens

        robot.interact(() -> {
            var age = view.data.table.getColumns().stream().filter(c -> "age".equals(c.getUserData())).findFirst().orElseThrow();
            age.setSortType(TableColumn.SortType.DESCENDING);
            view.data.table.getSortOrder().setAll(List.of(age));
        });
        settle();
        List<DynamicObject> rows = view.data.table.getItems();
        assertThat((Integer) rows.get(0).get("age")).isGreaterThanOrEqualTo((Integer) rows.get(rows.size() - 1).get("age"));
        robot.interact(() -> view.data.table.getSortOrder().clear());
        settle();

        robot.interact(() -> view.data.filter.setText("x.nope = 1"));
        robot.interact(() -> view.data.apply.fire());
        await(() -> robot.lookup("OK").tryQuery().isPresent());
        Shots.save(robot.lookup(".dialog-pane").query().getScene(), "05-error");
        robot.clickOn("OK");
        assertThat(view.status.getText()).contains("unknown field");
        robot.interact(() -> view.refresh.fire());
        settle();
    }

    @Test
    void consoleFeatures(FxRobot robot) {
        openDemo(robot);
        robot.interact(() -> view.tabs.getSelectionModel().select(1));
        robot.interact(() -> view.query.setText("SELECT c FROM Customer c WHERE c.nope = 1"));
        robot.interact(() -> view.query.run.fire());
        settle();
        assertThat(view.query.message.getStyleClass()).contains("k-error");
        assertThat(view.query.message.getText()).contains("unknown field");

        robot.interact(() -> view.query.setText("SELECT c.name FROM Customer c ORDER BY c.name LIMIT 3"));
        robot.interact(() -> view.query.editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                false, true, false, false)));
        settle();
        assertThat(view.query.results.getItems()).hasSize(3);
        robot.interact(() -> view.query.explain.fire());
        settle();
        assertThat(view.query.plan.getText()).contains("QUERY Customer");
        robot.interact(() -> view.query.setText("EXPLAIN SELECT c FROM Customer c"));
        robot.interact(view.query::runQuery);
        settle();
        assertThat(view.query.output.getSelectionModel().getSelectedIndex()).isEqualTo(1);
        robot.interact(() -> view.query.setText("UPDATE Purchase p SET p.quantity = p.quantity WHERE p.quantity > 1000"));
        robot.interact(() -> view.run.fire());
        settle();
        assertThat(view.query.message.getText()).contains("0 objects affected");
        assertThat(view.query.history.getItems()).hasSizeGreaterThanOrEqualTo(4);
        robot.interact(() -> view.query.setText("  "));
        robot.interact(view.query::runQuery);
        robot.interact(view.query::explainQuery);
        robot.interact(() -> view.query.history.getSelectionModel().select(1));
        robot.doubleClickOn(view.query.history);
        assertThat(view.query.editor.getText()).isNotBlank();

        robot.interact(() -> {
            view.query.setText("SELECT c FROM Cu");
            view.query.editor.moveTo(view.query.editor.getLength());
            view.query.editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, true, false, false));
        });
        await(view.query.completions::isShowing);
        robot.interact(() -> view.query.completions.getItems().get(0).fire());
        assertThat(view.query.editor.getText()).isEqualTo("SELECT c FROM Customer");
        robot.interact(() -> view.query.completions.hide());

        robot.interact(() -> view.query.setText("SELECT c.name FROM Customer c\nWHERE c.age >> 1"));
        robot.interact(view.query::runQuery);
        settle();
        assertThat(view.query.editor.getCurrentParagraph()).isEqualTo(1);
        robot.interact(() -> {
            view.query.setText("zzz");
            view.query.editor.moveTo(3);
            view.query.complete();
        });
        assertThat(view.query.completions.isShowing()).isFalse();
    }

    @Test
    void createEditAndDelete(FxRobot robot) {
        openDemo(robot);
        selectClass(robot, "Customer");
        view.editorDialog = editor -> {
            robot.interact(() -> {
                set(editor, "name", "Zelda");
                set(editor, "age", "33");
                set(editor, "tags", "vip, new");
                set(editor, "credit", "12.5");
                set(editor, "address.city", "Perth");
                ((ComboBox<String>) control(editor, "tier")).setValue("GOLD");
                ((DatePicker) control(editor, "since")).setValue(LocalDate.of(2020, 2, 2));
                editor.tabs.getSelectionModel().select(1);
                editor.tabs.getSelectionModel().select(0);
            });
            return Optional.of(editor.result());
        };
        robot.interact(() -> view.add.fire());
        settle();
        assertThat(view.session().db().dynamic("Customer").from().count()).isEqualTo(251);
        robot.interact(() -> view.data.filter.setText("x.name = 'Zelda'"));
        robot.interact(() -> view.data.apply.fire());
        settle();
        assertThat(view.data.table.getItems()).hasSize(1);
        DynamicObject zelda = view.data.table.getItems().get(0);
        assertThat(zelda.get("tags")).isEqualTo(List.of("vip", "new"));
        assertThat(zelda.get("address.city")).isEqualTo("Perth");

        view.editorDialog = editor -> {
            robot.interact(() -> {
                editor.tabs.getSelectionModel().select(1);
                editor.json.replaceText(editor.json.getText().replace("\"Zelda\"", "\"Zelda II\""));
                editor.tabs.getSelectionModel().select(0);
            });
            assertThat(((TextField) control(editor, "name")).getText()).isEqualTo("Zelda II");
            robot.interact(() -> editor.tabs.getSelectionModel().select(1));
            return Optional.of(editor.result());
        };
        robot.interact(() -> view.data.table.getSelectionModel().select(0));
        robot.interact(() -> view.edit.fire());
        settle();
        robot.interact(() -> view.data.filter.setText("x.name = 'Zelda II'"));
        robot.interact(() -> view.data.apply.fire());
        settle();
        assertThat(view.data.table.getItems()).hasSize(1);
        assertThat(view.data.table.getItems().get(0).version()).isEqualTo(2);

        view.editorDialog = editor -> {
            robot.interact(() -> {
                editor.tabs.getSelectionModel().select(1);
                editor.json.replaceText("{ broken");
                editor.tabs.getSelectionModel().select(0);
            });
            assertThat(editor.error.getText()).contains("expected");
            robot.interact(() -> {
                set(editor, "age", "abc");
            });
            try {
                editor.result();
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains("not a valid");
            }
            return Optional.empty();
        };
        robot.interact(() -> view.data.table.getSelectionModel().select(0));
        robot.doubleClickOn(robot.lookup(".table-row-cell").nth(0).queryAs(Node.class));
        settle();

        robot.interact(() -> view.data.table.getSelectionModel().selectAll());
        Platform.runLater(() -> view.delete.fire());
        await(() -> robot.lookup("Cancel").tryQuery().isPresent());
        robot.clickOn("Cancel");
        settle();
        assertThat(view.data.table.getItems()).hasSize(1);
        Platform.runLater(() -> view.delete.fire());
        await(() -> robot.lookup(".dialog-pane").tryQuery().isPresent());
        Shots.save(robot.lookup(".dialog-pane").query().getScene(), "07-confirm");
        robot.clickOn("OK");
        settle();
        assertThat(view.data.table.getItems()).isEmpty();
        assertThat(view.session().db().dynamic("Customer").from().count()).isEqualTo(250);
    }

    @Test
    void realEditorDialog(FxRobot robot) {
        openDemo(robot);
        selectClass(robot, "Purchase");
        robot.interact(() -> view.data.table.getSelectionModel().select(0));
        Platform.runLater(() -> view.edit.fire());
        await(() -> robot.lookup("#json-editor").tryQuery().isPresent());
        Shots.save(robot.lookup("#json-editor").query().getScene(), "08-editor-form");
        robot.interact(() -> ((TabPane) robot.lookup(".k-editor .tab-pane").query()).getSelectionModel().select(1));
        Shots.save(robot.lookup("#json-editor").query().getScene(), "06-editor-json");
        robot.interact(() -> ((TabPane) robot.lookup(".k-editor .tab-pane").query()).getSelectionModel().select(0));
        robot.interact(() -> robot.lookup("#field-quantity").queryAs(TextField.class).setText("x"));
        robot.clickOn("OK");
        assertThat(robot.lookup("#editor-error").queryLabeled().getText()).contains("not a valid");
        robot.interact(() -> robot.lookup("#field-quantity").queryAs(TextField.class).setText("4"));
        long oid = view.data.table.getItems().get(0).oid();
        robot.clickOn("OK");
        settle();
        assertThat(view.session().db().dynamic("Purchase").get(oid).get("quantity")).isEqualTo(4);
        robot.interact(() -> view.data.table.getSelectionModel().select(0));
        Platform.runLater(() -> view.edit.fire());
        await(() -> robot.lookup("#json-editor").tryQuery().isPresent());
        robot.clickOn("Cancel");
        settle();
    }

    @Test
    void versionConflicts(FxRobot robot) {
        openDemo(robot);
        selectClass(robot, "Customer");
        Session s = view.session();
        for (Dialogs.Conflict choice : Dialogs.Conflict.values()) {
            DynamicObject mine = s.page(CUSTOMER, "", null, false, 0, 1).rows().get(0);
            DynamicObject theirs = s.reload(mine);
            theirs.set("age", 99);
            s.save(theirs);
            mine.set("age", 11);
            view.editorDialog = editor -> Optional.of(editor.original());
            Platform.runLater(() -> view.save(mine));
            await(() -> robot.lookup("Overwrite").tryQuery().isPresent());
            Shots.save(robot.lookup(".dialog-pane").query().getScene(), "09-conflict");
            robot.clickOn(choice == Dialogs.Conflict.RELOAD ? "Reload" : choice == Dialogs.Conflict.OVERWRITE ? "Overwrite" : "Cancel");
            settle();
            int age = (Integer) s.reload(mine).get("age");
            assertThat(age).isEqualTo(choice == Dialogs.Conflict.OVERWRITE ? 11 : 99);
        }
    }

    @Test
    void aboutAndThemes(FxRobot robot) {
        List<String> opened = new ArrayList<>();
        view.setBrowser(opened::add);
        Platform.runLater(() -> view.about.fire());
        await(() -> robot.lookup("#about-github").tryQuery().isPresent());
        assertThat(robot.lookup("#about-version").queryLabeled().getText()).isEqualTo("Version 1.0.0-SNAPSHOT");
        Shots.save(robot.lookup("#about-github").query().getScene(), "10-about");
        robot.clickOn("#about-github");
        robot.clickOn("#about-email");
        assertThat(opened).containsExactly("https://github.com/paganini2008/kookaburra", "mailto:paganini20080@gmail.com");
        robot.clickOn("Close");
        openDemo(robot);
        for (Theme t : Theme.values()) {
            robot.interact(() -> view.applyTheme(scene, t));
            assertThat(view.theme()).isEqualTo(t);
        }
        robot.interact(() -> view.applyTheme(scene, Theme.PRIMER_LIGHT));
        selectClass(robot, "Customer");
        Shots.save(scene, "11-theme-light");
        robot.interact(() -> ((RadioMenuItem) view.themes.getItems().get(0)).fire());
        assertThat(Theme.saved()).isEqualTo(Theme.NEON);
        assertThat(scene.getStylesheets()).anyMatch(u -> u.endsWith("neon.css"));
    }

    @Test
    void openDirectoryAndArguments(FxRobot robot, @TempDir Path dir) {
        view.directoryChooser = () -> dir.resolve("db").toFile();
        robot.interact(() -> view.open.fire());
        settle();
        assertThat(view.session().title()).endsWith("db");
        assertThat(view.tree.getRoot().getChildren()).isEmpty();
        robot.interact(() -> view.add.fire());
        robot.interact(() -> view.edit.fire());
        robot.interact(() -> view.delete.fire());
        view.directoryChooser = () -> null;
        robot.interact(() -> view.open.fire());
        robot.interact(() -> StudioApplicationAccess.open(view, List.of()));
        robot.interact(() -> StudioApplicationAccess.open(view, List.of(dir.resolve("db2").toString())));
        settle();
        assertThat(view.session().title()).endsWith("db2");
        robot.interact(() -> StudioApplicationAccess.open(view, List.of("--demo")));
        settle();
        assertThat(view.session().title()).contains("demo");
        StudioApplication app = new StudioApplication();
        Stage[] main = new Stage[1];
        robot.interact(() -> main[0] = StudioApplicationAccess.start(app));
        assertThat(robot.lookup("#splash-version").queryAs(Label.class).getText()).startsWith("Version ").contains("core");
        Shots.save(robot.lookup("#splash-version").query().getScene(), "00-splash");
        await(() -> main[0].isShowing());                       // splash finished, main window up
        robot.interact(() -> StudioApplicationAccess.stop(app, main[0]));
    }

    private static Node control(ObjectEditor editor, String path) {
        return editor.control(path);
    }

    private static void set(ObjectEditor editor, String path, String text) {
        Node n = control(editor, path);
        if (n instanceof TextField tf) {
            tf.setText(text);
        } else if (n instanceof CheckBox cb) {
            cb.setSelected(Boolean.parseBoolean(text));
        }
    }

    private List<String> rowNumbers(FxRobot robot) {
        Map<Integer, String> byRow = new TreeMap<>();
        for (TableCell<?, ?> c : robot.lookup(".table-cell.k-rownum-col").queryAllAs(TableCell.class)) {
            if (c.getTableView() == view.data.table && c.getIndex() >= 0 && c.isVisible() && c.getText() != null) {
                byRow.put(c.getIndex(), c.getText());
            }
        }
        return List.copyOf(byRow.values());
    }

}
