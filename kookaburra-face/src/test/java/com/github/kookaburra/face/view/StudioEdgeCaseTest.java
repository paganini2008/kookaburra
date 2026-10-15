package com.github.kookaburra.face.view;

import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.model.Export;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary and abuse cases driven through the UI: odd input, invalid values, empty and last pages, failing
 * statements, unusable directories. Each case asserts what the user sees and what is stored.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioEdgeCaseTest {

    private MainView view;
    private Scene scene;

    @Start
    void start(Stage stage) {
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

    /** Pause after each step when watching the run on a real display ({@code -Dstudio.pause=ms}, profile {@code visible}). */
    private static final int PAUSE = Integer.getInteger("studio.pause", 0);

    private void settle() {
        for (int i = 0; i < 500 && view.tasks.pending() > 0; i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
        }
        WaitForAsyncUtils.waitForFxEvents();
        if (PAUSE > 0) {
            WaitForAsyncUtils.sleep(PAUSE, TimeUnit.MILLISECONDS);
        }
    }

    private boolean await(BooleanSupplier c, int ms) {
        for (int i = 0; i < ms / 20 && !c.getAsBoolean(); i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        return c.getAsBoolean();
    }

    /** Text of an open alert (then closes it with its first button), or null if none appears. */
    private String alert(FxRobot robot) {
        if (!await(() -> robot.lookup(".dialog-pane").tryQuery().isPresent(), 1500)) {
            return null;
        }
        DialogPane pane = robot.lookup(".dialog-pane").queryAs(DialogPane.class);
        String text = pane.getContentText();
        if (PAUSE > 0) {
            WaitForAsyncUtils.sleep(PAUSE * 2L, TimeUnit.MILLISECONDS);         // let a watcher read the dialog
        }
        robot.interact(() -> pane.getButtonTypes().stream().findFirst()
                .ifPresent(b -> ((Button) pane.lookupButton(b)).fire()));
        WaitForAsyncUtils.waitForFxEvents();
        return text;
    }

    private void select(FxRobot robot, String simple) {
        robot.interact(() -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, simple)));
        settle();
    }

    private void filter(FxRobot robot, String f) {
        robot.interact(() -> view.data.filter.setText(f));
        robot.interact(() -> view.data.apply.fire());
        settle();
    }

    /** Opens the editor for a new Customer, applies {@code edit}, presses OK; returns the editor error or "saved". */
    private String newCustomer(FxRobot robot, Consumer<FxRobot> edit) {
        select(robot, "Customer");
        Platform.runLater(() -> view.add.fire());
        if (!await(() -> robot.lookup("#json-editor").tryQuery().isPresent(), 3000)) {
            return "no editor";
        }
        edit.accept(robot);
        robot.interact(() -> ((Button) robot.lookup(".dialog-pane").queryAs(DialogPane.class)
                .lookupButton(ButtonType.OK)).fire());
        WaitForAsyncUtils.waitForFxEvents();
        if (robot.lookup("#editor-error").tryQuery().isPresent()) {
            String err = robot.lookup("#editor-error").queryLabeled().getText();
            robot.interact(() -> ((Button) robot.lookup(".dialog-pane").queryAs(DialogPane.class)
                    .lookupButton(ButtonType.CANCEL)).fire());
            settle();
            return "editor error: " + err;
        }
        settle();
        String a = alert(robot);
        return a != null ? "alert: " + a : "saved; status=" + view.status.getText();
    }

    private void set(FxRobot robot, String path, String text) {
        robot.interact(() -> robot.lookup("#field-" + path).queryAs(TextField.class).setText(text));
    }

    private void json(FxRobot robot, String text) {
        robot.interact(() -> ((TabPane) robot.lookup(".k-editor .tab-pane").query()).getSelectionModel().select(1));
        robot.interact(() -> robot.lookup("#json-editor").queryAs(CodeArea.class).replaceText(text));
    }

    @Test
    void editorInput(FxRobot robot) {
        open();
        // type errors keep the editor open with a field-qualified message, nothing is stored
        assertThat(newCustomer(robot, r -> { set(r, "name", "Ovf"); set(r, "age", "99999999999"); }))
                .isEqualTo("editor error: Customer.age: 99999999999 (Long) is not a valid INT32");
        assertThat(rows("SELECT c FROM Customer c WHERE c.name = 'Ovf'")).isEmpty();
        assertThat(newCustomer(robot, r -> { set(r, "name", "Spaces"); set(r, "age", "  42 "); })).startsWith("saved");
        assertThat(rows("SELECT c.age FROM Customer c WHERE c.name = 'Spaces'")).containsExactly(42);
        assertThat(newCustomer(robot, r -> { set(r, "name", "NaNy"); set(r, "credit", "NaN"); })).startsWith("saved");
        assertThat(newCustomer(robot, r -> { set(r, "name", "Inf"); set(r, "credit", "1e309"); })).startsWith("saved");
        assertThat(rows("SELECT c.credit FROM Customer c WHERE c.name = 'Inf'")).containsExactly(Double.POSITIVE_INFINITY);
        String longName = "长名字😀".repeat(2000);
        assertThat(newCustomer(robot, r -> set(r, "name", longName))).startsWith("saved");
        assertThat(rows("SELECT c.name FROM Customer c WHERE c.name LIKE '长名字%'")).containsExactly(longName);
        String tricky = "O'Brien \"Q\" \\ ; --";
        assertThat(newCustomer(robot, r -> set(r, "name", tricky))).startsWith("saved");
        filter(robot, "x.name = 'O''Brien \"Q\" \\ ; --'");
        assertThat(view.data.table.getItems()).singleElement().extracting(o -> o.get("name")).isEqualTo(tricky);
        assertThat(newCustomer(robot, r -> { set(r, "name", "Tagz"); set(r, "tags", " a, ,b,, "); })).startsWith("saved");
        assertThat(rows("SELECT c.tags FROM Customer c WHERE c.name = 'Tagz'")).containsExactly(List.of("a", "b"));

        assertThat(newCustomer(robot, r -> json(r, "{"))).startsWith("editor error: unexpected end of input");
        assertThat(newCustomer(robot, r -> json(r, "{\"name\":\"J\",\"age\":\"abc\"}"))).contains("Customer.age: abc").contains("INT32");
        assertThat(newCustomer(robot, r -> json(r, "{\"name\":\"J\",\"age\":1.5}"))).contains("Customer.age: 1.5");
        assertThat(newCustomer(robot, r -> json(r, "{\"name\":\"J\",\"bogus\":1}"))).contains("no field 'bogus'");
        assertThat(newCustomer(robot, r -> json(r, "{\"name\":\"J\",\"tier\":\"PLATINUM\"}"))).contains("Customer.tier: PLATINUM");
        assertThat(newCustomer(robot, r -> json(r, "[1,2]"))).contains("must be a JSON object");
        assertThat(newCustomer(robot, r -> json(r, "{\"name\":\"J\",\"since\":\"2024-13-45\"}")))
                .isEqualTo("editor error: Customer.since: 2024-13-45 (String) is not a valid DATE");
        assertThat(newCustomer(robot, r -> json(r, "{}"))).startsWith("saved");
        assertThat(rows("SELECT COUNT(*) FROM Customer c WHERE c.name = 'J'")).containsExactly(0L);
    }

    @Test
    void dataTabEdges(FxRobot robot, @TempDir Path tmp) throws IOException {
        open();
        select(robot, "Customer");
        filter(robot, "x.age > 1000");
        assertThat(view.data.table.getItems()).isEmpty();
        assertThat(view.data.pageLabel.getText()).isEqualTo("1 / 1");
        assertThat(view.data.prev.isDisabled() && view.data.next.isDisabled()).isTrue();
        Path empty = tmp.resolve("empty.csv");
        view.data.exportChooser = f -> empty.toFile();
        robot.interact(() -> view.data.exportAs(Export.Format.CSV));
        settle();
        assertThat(Files.readAllLines(empty)).singleElement().asString().startsWith("oid,version,name");

        filter(robot, "x.name = 'abc");
        assertThat(alert(robot)).contains("unterminated string literal");
        filter(robot, "x.age > 0 LIMIT 2");
        assertThat(alert(robot)).contains("syntax error");                       // a filter is a condition only
        filter(robot, "x.age > 0 ORDER BY x.name");
        assertThat(alert(robot)).contains("syntax error");
        filter(robot, "   ");
        assertThat(view.data.pageLabel.getText()).isEqualTo("1 / 5");

        robot.interact(() -> view.data.pageSize.setValue(500));
        settle();
        assertThat(view.data.pageLabel.getText()).isEqualTo("1 / 1");
        assertThat(view.data.next.isDisabled()).isTrue();
        robot.interact(() -> view.data.pageSize.setValue(25));
        settle();
        for (int i = 0; i < 20 && !view.data.next.isDisabled(); i++) {
            robot.interact(() -> view.data.next.fire());
            settle();
        }
        assertThat(view.data.pageLabel.getText()).isEqualTo("10 / 10");
        robot.interact(() -> view.data.table.getSelectionModel().selectAll());
        Platform.runLater(() -> view.delete.fire());
        assertThat(alert(robot)).isEqualTo("Delete 25 objects?");
        settle();
        assertThat(view.data.pageLabel.getText()).isEqualTo("9 / 9");              // moved back to the new last page
        assertThat(view.data.table.getItems()).hasSize(25);

        robot.interact(() -> view.data.table.getSelectionModel().clearSelection());
        robot.interact(() -> view.delete.fire());
        assertThat(alert(robot)).isNull();
        assertThat(view.status.getText()).isEqualTo("select one or more rows to delete");

        Path nested = tmp.resolve("new/sub/dir/x.jsonl");
        view.data.exportChooser = f -> nested.toFile();
        robot.interact(() -> view.data.exportAs(Export.Format.JSONL));
        settle();
        assertThat(Files.readAllLines(nested)).hasSize(225);                       // missing directories are created
    }

    @Test
    void consoleEdges(FxRobot robot, @TempDir Path tmp) throws IOException {
        open();
        robot.interact(() -> view.tabs.getSelectionModel().select(1));
        assertThat(run(robot, "SELECT c FROM Customer c LIMIT 0")).startsWith("rows=0");
        assertThat(run(robot, "SELECT c FROM Customer c OFFSET 100000")).startsWith("rows=0");
        assertThat(run(robot, "SELECT c.age / 0 FROM Customer c")).contains("division by zero");
        assertThat(run(robot, "SELECT c FROM Customer c WHERE c.name = 'abc")).contains("unterminated string literal");
        assertThat(run(robot, "SELECT c FROM Nope c")).contains("no stored class 'Nope'");
        assertThat(run(robot, "SELECT c FROM Customer c LIMIT -1")).contains("LIMIT must be a non-negative integer");
        assertThat(run(robot, "SELECT c.name FROM Customer c WHERE c.name = '" + "x".repeat(5000) + "'")).startsWith("rows=0");
        assertThat(run(robot, "SELECT")).contains("(unexpected here)").doesNotContain("expecting {");

        // EXPLAIN shows the plan and changes nothing
        assertThat(run(robot, "EXPLAIN DELETE FROM Purchase p")).contains("msg=plan");
        assertThat(view.query.plan.getText()).contains("DELETE");
        assertThat(rows("SELECT COUNT(*) FROM Purchase p")).containsExactly(750L);

        // DML refreshes the class tree and counts
        assertThat(run(robot, "DELETE FROM Purchase p WHERE p.quantity > 0")).contains("750 objects affected");
        settle();
        assertThat(StudioUiTest.findClass(view, "Purchase").getValue().cls().count()).isZero();

        // export the console result: every row, not just the visible page
        Path exported = tmp.resolve("console/customers.jsonl");
        view.query.exportChooser = f -> f == Export.Format.JSONL ? exported.toFile() : null;
        robot.interact(() -> view.query.setText("SELECT c FROM Customer c"));
        robot.interact(() -> view.query.export.show());                               // the menu opens on screen
        settle();
        robot.interact(() -> view.query.export.getItems().stream()
                .filter(i -> "console-export-jsonl".equals(i.getId())).findFirst().orElseThrow().fire());
        robot.interact(() -> view.query.export.hide());
        settle();
        assertThat(view.query.message.getText()).isEqualTo("exported 250 rows → customers.jsonl");
        assertThat(Files.readAllLines(exported)).hasSize(250);
        robot.interact(() -> view.query.exportAs(Export.Format.CSV));                 // chooser cancelled: nothing
        robot.interact(() -> view.query.setText("DELETE FROM Customer c"));
        view.query.exportChooser = f -> tmp.resolve("console/x." + f.extension()).toFile();
        robot.interact(() -> view.query.exportAs(Export.Format.JSON));
        settle();
        assertThat(view.query.message.getText()).contains("only SELECT");
        assertThat(rows("SELECT COUNT(*) FROM Customer c")).containsExactly(250L);      // nothing was deleted
        robot.interact(() -> view.query.setText("   "));
        robot.interact(() -> view.query.exportAs(Export.Format.JSON));                // empty editor: nothing

        run(robot, "SELECT c FROM Customer c");
        for (int i = 0; i < 10 && !view.query.next.isDisabled(); i++) {
            robot.interact(() -> view.query.next.fire());
            settle();
        }
        assertThat(view.query.pageLabel.getText()).isEqualTo("201–250");
    }

    @Test
    void unusableDirectories(FxRobot robot, @TempDir Path tmp) throws IOException {
        open();
        Path file = Files.writeString(tmp.resolve("plain.txt"), "hello");
        view.directoryChooser = file::toFile;
        robot.interact(() -> view.open.fire());
        settle();
        assertThat(alert(robot)).endsWith("is a file, not a directory");
        assertThat(view.session().title()).contains("demo");                      // the open session is kept

        Path junk = Files.createDirectories(tmp.resolve("junk"));
        Files.writeString(junk.resolve("notes.txt"), "x");
        view.directoryChooser = junk::toFile;
        Platform.runLater(() -> view.open.fire());
        String question = alertCancel(robot);
        assertThat(question).contains("is not empty and is not a Kookaburra database");
        settle();
        assertThat(view.session().title()).contains("demo");
        try (Stream<Path> files = Files.list(junk)) {
            assertThat(files.toList()).hasSize(1);                                 // nothing created
        }

        Path db = tmp.resolve("db");
        Session other = Session.open(db);
        view.directoryChooser = db::toFile;
        robot.interact(() -> view.open.fire());
        settle();
        assertThat(alert(robot)).contains("already open");
        other.close();

        Path ro = Files.createDirectories(tmp.resolve("ro"));
        assertThat(ro.toFile().setWritable(false)).isTrue();
        view.directoryChooser = () -> ro.resolve("db").toFile();
        robot.interact(() -> view.open.fire());
        settle();
        assertThat(alert(robot)).contains("cannot open").contains("permission denied");
        assertThat(ro.toFile().setWritable(true)).isTrue();
        assertThat(view.session().title()).contains("demo");
    }

    // ---------------------------------------------------------------- helpers

    private void open() {
        Session s = Session.demo();
        Platform.runLater(() -> view.open(s));
        settle();
        await(() -> view.tree.getRoot() != null && StudioUiTest.findClass(view, "Customer") != null, 5000);
    }

    private List<Object> rows(String oql) {
        return view.session().db().oql(oql).list();
    }

    private String run(FxRobot robot, String oql) {
        robot.interact(() -> view.query.setText(oql));
        robot.interact(() -> view.query.run.fire());
        settle();
        return "rows=" + view.query.results.getItems().size() + " msg=" + view.query.message.getText();
    }

    /** Closes an open alert with Cancel (the last button) and returns its text. */
    private String alertCancel(FxRobot robot) {
        await(() -> robot.lookup(".dialog-pane").tryQuery().isPresent(), 3000);
        DialogPane pane = robot.lookup(".dialog-pane").queryAs(DialogPane.class);
        String text = pane.getContentText();
        if (PAUSE > 0) {
            WaitForAsyncUtils.sleep(PAUSE * 2L, TimeUnit.MILLISECONDS);         // let a watcher read the dialog
        }
        robot.interact(() -> ((Button) pane.lookupButton(ButtonType.CANCEL)).fire());
        WaitForAsyncUtils.waitForFxEvents();
        return text;
    }
}
