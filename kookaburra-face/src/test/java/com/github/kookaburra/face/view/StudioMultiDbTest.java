package com.github.kookaburra.face.view;

import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several open databases in one window, and Studio transactions: create / edit / delete / console DML inside a
 * transaction, commit, rollback, conflicts, confirmations; transactions are per database.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioMultiDbTest {

    /** Pause after each step when watching on a real display ({@code -Dstudio.pause=ms}). */
    private static final int PAUSE = Integer.getInteger("studio.pause", 0);
    private static final String CUSTOMER = "com.github.kookaburra.face.demo.Customer";

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

    private void settle() {
        for (int i = 0; i < 500 && view.tasks.pending() > 0; i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
        }
        WaitForAsyncUtils.waitForFxEvents();
        if (PAUSE > 0) {
            WaitForAsyncUtils.sleep(PAUSE, TimeUnit.MILLISECONDS);
        }
    }

    private boolean await(BooleanSupplier c) {
        for (int i = 0; i < 250 && !c.getAsBoolean(); i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        return c.getAsBoolean();
    }

    /** Answers the open dialog with {@code button} and returns its text (null if none appeared). */
    private String answer(FxRobot robot, ButtonType button) {
        if (!await(() -> robot.lookup(".dialog-pane").tryQuery().isPresent())) {
            return null;
        }
        DialogPane pane = robot.lookup(".dialog-pane").queryAs(DialogPane.class);
        String text = pane.getContentText();
        if (PAUSE > 0) {
            WaitForAsyncUtils.sleep(PAUSE * 2L, TimeUnit.MILLISECONDS);
        }
        robot.interact(() -> ((Button) pane.lookupButton(button)).fire());
        WaitForAsyncUtils.waitForFxEvents();
        return text;
    }

    private void openDemo(FxRobot robot) {
        Session s = Session.demo();
        robot.interact(() -> view.open(s));
        settle();
    }

    /** The demo data set in memory, or on disk (flushed into segment files) under {@code dir}. */
    private void openData(FxRobot robot, boolean onDisk, Path dir) {
        if (!onDisk) {
            openDemo(robot);
            return;
        }
        Session s = Session.open(dir);
        DemoData.fill(s.db(), 250, 42);
        s.db().flush();
        robot.interact(() -> view.open(s));
        settle();
    }

    private void select(FxRobot robot, TreeItem<NavNode> item) {
        robot.interact(() -> view.tree.getSelectionModel().select(item));
        settle();
    }

    private TreeItem<NavNode> dbNode(Session s) {
        return view.tree.getRoot().getChildren().stream().filter(i -> i.getValue().session() == s).findFirst().orElseThrow();
    }

    private long outside(Session s, String oql) {
        return (Long) s.db().oql(oql).list().get(0);
    }

    /** Saves a new Customer through the editor flow (the dialog is replaced by a hook). */
    private void createCustomer(FxRobot robot, String name) {
        view.editorDialog = editor -> {
            DynamicObject o = editor.original();
            o.set("name", name);
            o.set("age", 33);
            return Optional.of(o);
        };
        robot.interact(() -> view.add.fire());
        settle();
    }

    @Test
    void severalDatabasesInOneWindow(FxRobot robot, @TempDir Path tmp) {
        openDemo(robot);
        Session first = view.session();
        Path dir = tmp.resolve("shop");
        view.directoryChooser = dir::toFile;
        robot.interact(() -> view.open.fire());
        settle();
        Session disk = view.session();
        openDemo(robot);
        Session second = view.session();
        assertThat(view.sessions()).containsExactly(first, disk, second);
        assertThat(view.tree.getRoot().getChildren()).extracting(i -> i.getValue().label())
                .containsExactly("demo", "shop", "demo (2)");
        assertThat(dbNode(disk).getValue().text()).isEqualTo("shop");
        Shots.save(scene, "12-multi-db");

        // a class of another database makes that database current
        TreeItem<NavNode> customers = dbNode(first).getChildren().stream()
                .filter(i -> i.getValue().cls().simpleName().equals("Customer")).findFirst().orElseThrow();
        select(robot, customers);
        assertThat(view.session()).isSameAs(first);
        assertThat(view.data.className()).isEqualTo(CUSTOMER);
        assertThat(view.data.table.getItems()).isNotEmpty();
        assertThat(((Stage) scene.getWindow()).getTitle()).contains("demo", "3 databases");

        // opening an open directory again just switches to it
        robot.interact(() -> view.open.fire());
        settle();
        assertThat(view.sessions()).hasSize(3);
        assertThat(view.session()).isSameAs(disk);
        assertThat(view.status.getText()).isEqualTo("shop is already open");

        // a database node: current database, empty grid
        select(robot, dbNode(second));
        assertThat(view.session()).isSameAs(second);
        assertThat(view.data.className()).isNull();
        assertThat(view.data.table.getItems()).isEmpty();

        robot.interact(() -> view.closeDb.fire());
        settle();
        assertThat(view.sessions()).containsExactly(first, disk);
        assertThat(view.session()).isSameAs(disk);
        robot.interact(() -> view.closeDb.fire());
        robot.interact(() -> view.closeDb.fire());
        settle();
        assertThat(view.sessions()).isEmpty();
        assertThat(view.session()).isNull();
        assertThat(view.status.getText()).isEqualTo("No database open");
        assertThat(view.closeDb.isDisabled()).isTrue();
        robot.interact(() -> view.closeDb.fire());                 // nothing open: no-op
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void createEditDeleteAndConsoleInsideATransaction(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        openData(robot, onDisk, tmp.resolve("db"));
        Session s = view.session();
        robot.interact(() -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, "Customer")));
        settle();
        assertThat(view.txBegin.isDisabled()).isFalse();
        assertThat(view.txCommit.isDisabled()).isTrue();
        robot.interact(() -> view.txBegin.fire());
        settle();
        assertThat(view.txStatus.isVisible()).isTrue();
        assertThat(view.txStatus.getText()).startsWith("TX #").contains("SNAPSHOT", "0 pending changes");
        assertThat(view.txBegin.isDisabled()).isTrue();
        assertThat(view.txCommit.isDisabled()).isFalse();
        assertThat(dbNode(s).getValue().text()).endsWith("· TX");

        createCustomer(robot, "TxNew");
        assertThat(view.txStatus.getText()).contains("1 pending change").doesNotContain("changes");
        assertThat(StudioUiTest.findClass(view, "Customer").getValue().cls().count()).isEqualTo(251);   // sees its own insert
        assertThat(outside(s, "SELECT COUNT(*) FROM Customer c")).isEqualTo(250);                        // others do not

        int victim = 0;
        while ("TxNew".equals(view.data.table.getItems().get(victim).get("name"))) {
            victim++;                                             // the transaction's own insert is listed first
        }
        int row = victim;
        robot.interact(() -> view.data.table.getSelectionModel().select(row));
        Platform.runLater(() -> view.delete.fire());
        assertThat(answer(robot, ButtonType.OK)).isEqualTo("Delete 1 object?");
        settle();
        robot.interact(() -> view.tabs.getSelectionModel().select(1));
        robot.interact(() -> view.query.setText("UPDATE Customer c SET c.age = 77 WHERE c.name = 'TxNew'"));
        robot.interact(() -> view.query.run.fire());
        settle();
        assertThat(view.query.message.getText()).contains("1 object affected");
        assertThat(view.txStatus.getText()).contains("2 pending changes");
        assertThat(outside(s, "SELECT COUNT(*) FROM Customer c WHERE c.name = 'TxNew'")).isZero();
        Shots.save(scene, "13-transaction");

        robot.interact(() -> view.txCommit.fire());
        settle();
        assertThat(view.txStatus.isVisible()).isFalse();
        assertThat(outside(s, "SELECT COUNT(*) FROM Customer c")).isEqualTo(250);
        assertThat(outside(s, "SELECT COUNT(*) FROM Customer c WHERE c.name = 'TxNew' AND c.age = 77")).isEqualTo(1);

        // rollback discards everything
        robot.interact(() -> view.txBegin.fire());
        settle();
        createCustomer(robot, "Gone");
        Platform.runLater(() -> view.txRollback.fire());
        assertThat(answer(robot, ButtonType.OK)).startsWith("Discard the changes of TX #");
        settle();
        assertThat(view.txStatus.isVisible()).isFalse();
        assertThat(outside(s, "SELECT COUNT(*) FROM Customer c WHERE c.name = 'Gone'")).isZero();
        robot.interact(() -> view.txBegin.fire());
        settle();
        Platform.runLater(() -> view.txRollback.fire());
        answer(robot, ButtonType.CANCEL);                        // keep it
        settle();
        assertThat(view.txStatus.isVisible()).isTrue();
        robot.interact(() -> view.txCommit.fire());              // nothing pending: commits trivially
        settle();
        assertThat(view.txStatus.isVisible()).isFalse();
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void conflictsAndConfirmations(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        openData(robot, onDisk, tmp.resolve("db"));
        Session s = view.session();
        robot.interact(() -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, "Customer")));
        settle();
        robot.interact(() -> view.txBegin.fire());
        settle();
        DynamicObject target = view.data.table.getItems().get(0);
        view.editorDialog = editor -> {
            DynamicObject o = editor.original();
            o.set("age", 18);
            return Optional.of(o);
        };
        robot.interact(() -> view.editObject(target));
        settle();
        // someone else changes the same object and commits first
        DynamicObject theirs = s.db().dynamic("Customer").get(target.oid());
        theirs.set("age", 99);
        s.db().dynamic("Customer").update(theirs);
        Platform.runLater(() -> view.txCommit.fire());
        assertThat(answer(robot, ButtonType.OK)).contains("changed by a concurrent commit", "rolled back");
        settle();
        assertThat(view.txStatus.isVisible()).isFalse();
        assertThat(s.db().dynamic("Customer").get(target.oid()).get("age")).isEqualTo(99);

        // transactions are per database
        robot.interact(() -> view.txBegin.fire());
        settle();
        createCustomer(robot, "Pending");
        openDemo(robot);
        Session other = view.session();
        assertThat(view.txStatus.isVisible()).isFalse();          // the new database has no transaction
        assertThat(view.txBegin.isDisabled()).isFalse();
        select(robot, dbNode(s));
        assertThat(view.txStatus.getText()).contains("1 pending change");

        // closing a database with uncommitted changes asks first
        Platform.runLater(() -> view.closeDb.fire());
        assertThat(answer(robot, ButtonType.CANCEL)).contains("is still open. Discard its changes?");
        settle();
        assertThat(view.sessions()).contains(s);
        boolean[] quit = new boolean[1];
        Platform.runLater(() -> quit[0] = view.confirmDiscardAll());
        assertThat(answer(robot, ButtonType.OK)).startsWith("Open transactions:").contains(onDisk ? "db: TX #" : "demo: TX #");
        assertThat(quit[0]).isTrue();
        Platform.runLater(() -> view.closeDb.fire());
        answer(robot, ButtonType.OK);
        settle();
        assertThat(view.sessions()).containsExactly(other);
        boolean[] free = new boolean[1];
        robot.interact(() -> free[0] = view.confirmDiscardAll());   // no open transaction: no question
        assertThat(free[0]).isTrue();
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void nestedObjectCrudInTheEditor(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        openData(robot, onDisk, tmp.resolve("db"));
        Session s = view.session();
        robot.interact(() -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, "Customer")));
        settle();
        robot.interact(() -> view.txBegin.fire());
        settle();

        // create: nested fields in the form
        Platform.runLater(() -> view.add.fire());
        await(() -> robot.lookup("#json-editor").tryQuery().isPresent());
        robot.interact(() -> {
            text(robot, "field-name").setText("Nest");
            text(robot, "field-address.city").setText("Hobart");
            text(robot, "field-address.street").setText("1 Salamanca Pl");
        });
        if (PAUSE > 0) {
            WaitForAsyncUtils.sleep(PAUSE * 2L, TimeUnit.MILLISECONDS);
        }
        robot.interact(() -> ((Button) robot.lookup(".dialog-pane").queryAs(DialogPane.class).lookupButton(ButtonType.OK)).fire());
        settle();
        robot.interact(() -> view.txCommit.fire());
        settle();
        assertThat(outside(s, "SELECT COUNT(*) FROM Customer c WHERE c.address.city = 'Hobart' AND c.name = 'Nest'")).isEqualTo(1);

        // update: change the nested object in the JSON tab
        robot.interact(() -> view.data.filter.setText("x.name = 'Nest'"));
        robot.interact(() -> view.data.apply.fire());
        settle();
        DynamicObject nest = view.data.table.getItems().get(0);
        Platform.runLater(() -> view.editObject(nest));
        await(() -> robot.lookup("#json-editor").tryQuery().isPresent());
        robot.interact(() -> ((TabPane) robot.lookup(".k-editor .tab-pane").query()).getSelectionModel().select(1));
        robot.interact(() -> {
            CodeArea json = robot.lookup("#json-editor").queryAs(CodeArea.class);
            json.replaceText(json.getText().replace("\"Hobart\"", "\"Darwin\"").replace("1 Salamanca Pl", "5 Mitchell St"));
        });
        if (PAUSE > 0) {
            WaitForAsyncUtils.sleep(PAUSE * 2L, TimeUnit.MILLISECONDS);
        }
        robot.interact(() -> ((Button) robot.lookup(".dialog-pane").queryAs(DialogPane.class).lookupButton(ButtonType.OK)).fire());
        settle();
        DynamicObject updated = s.db().dynamic("Customer").get(nest.oid());
        assertThat(updated.get("address.city")).isEqualTo("Darwin");
        assertThat(updated.get("address.street")).isEqualTo("5 Mitchell St");
        assertThat(updated.version()).isEqualTo(nest.version() + 1);

        // a nested value the schema does not know is rejected in the editor (the dialog stays open)
        Platform.runLater(() -> view.editObject(updated));
        await(() -> robot.lookup("#json-editor").tryQuery().isPresent());
        robot.interact(() -> ((TabPane) robot.lookup(".k-editor .tab-pane").query()).getSelectionModel().select(1));
        robot.interact(() -> {
            CodeArea json = robot.lookup("#json-editor").queryAs(CodeArea.class);
            json.replaceText(json.getText().replace("\"city\": \"Darwin\"", "\"city\": \"Darwin\", \"planet\": \"Mars\""));
        });
        robot.interact(() -> ((Button) robot.lookup(".dialog-pane").queryAs(DialogPane.class).lookupButton(ButtonType.OK)).fire());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#editor-error").queryLabeled().getText()).contains("planet");
        robot.interact(() -> ((Button) robot.lookup(".dialog-pane").queryAs(DialogPane.class).lookupButton(ButtonType.CANCEL)).fire());
        settle();

        // delete: the object and its nested values are gone
        robot.interact(() -> view.data.table.getSelectionModel().select(0));
        Platform.runLater(() -> view.delete.fire());
        answer(robot, ButtonType.OK);
        settle();
        assertThat(outside(s, "SELECT COUNT(*) FROM Customer c WHERE c.address.city = 'Darwin' AND c.name = 'Nest'")).isZero();
        assertThat(view.data.table.getItems()).isEmpty();
    }

    private static TextField text(FxRobot robot, String id) {
        return robot.lookup(n -> id.equals(n.getId())).queryAs(TextField.class);
    }

}
