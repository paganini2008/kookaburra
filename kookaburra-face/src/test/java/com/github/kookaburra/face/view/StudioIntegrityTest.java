package com.github.kookaburra.face.view;

import com.github.kookaburra.Isolation;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.face.model.ClassMeta;
import com.github.kookaburra.face.model.Display;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.TreeItem;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Studio with constraints, decimals, isolation levels, backlinks and backups, in memory and on disk: unique
 * e-mails, ON DELETE RESTRICT / CASCADE through the delete button, the "referenced by" inspector node, decimal
 * editing, the isolation menu of Begin, the constraint column of the metadata panel and the Backup button.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioIntegrityTest {

    private static final int PAUSE = Integer.getInteger("studio.pause", 0);
    private static final String CUSTOMER = "com.github.kookaburra.face.demo.Customer";
    private static final String PURCHASE = "com.github.kookaburra.face.demo.Purchase";

    private MainView view;
    private Scene scene;

    @Start
    void start(Stage stage) {
        view = new MainView();
        scene = StudioApplication.scene(view);
        stage.setScene(scene);
        stage.setWidth(1400);
        stage.setHeight(860);
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

    /** Answers the open dialog with {@code button}; returns its header + content text (null if none appeared). */
    private String answer(FxRobot robot, ButtonType button) {
        if (!await(() -> robot.lookup(".dialog-pane").tryQuery().isPresent())) {
            return null;
        }
        DialogPane pane = robot.lookup(".dialog-pane").queryAs(DialogPane.class);
        String text = pane.getHeaderText() + " | " + pane.getContentText();
        robot.interact(() -> ((Button) pane.lookupButton(button)).fire());
        WaitForAsyncUtils.waitForFxEvents();
        return text;
    }

    /** The demo data in memory, or written to a directory, flushed and reopened (served from segment files). */
    private Session open(FxRobot robot, boolean onDisk, Path tmp) {
        Session s;
        if (onDisk) {
            Session fill = Session.open(tmp.resolve("db"));
            DemoData.fill(fill.db(), 60, 42);
            fill.db().flush();
            fill.close();
            s = Session.open(tmp.resolve("db"));
        } else {
            s = Session.demo();
        }
        Session opened = s;
        robot.interact(() -> view.open(opened));
        settle();
        return s;
    }

    private void showClass(FxRobot robot, String simpleName) {
        robot.interact(() -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, simpleName)));
        settle();
    }

    private long count(Session s, String oql) {
        return (Long) s.db().oql(oql).list().get(0);
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void constraintsThroughTheUi(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        Session s = open(robot, onDisk, tmp);
        showClass(robot, "Customer");
        DynamicObject first = view.data.table.getItems().get(0);
        String taken = (String) first.get("email");

        // unique e-mail: the editor's save fails with a "Constraint violated" dialog, nothing is stored
        long before = count(s, "SELECT COUNT(*) FROM Customer c");
        view.editorDialog = editor -> {
            DynamicObject o = editor.original();
            o.set("name", "Dup");
            o.set("email", taken);
            return Optional.of(o);
        };
        Platform.runLater(() -> view.add.fire());
        assertThat(answer(robot, ButtonType.OK)).startsWith("Constraint violated").contains("duplicate value '" + taken + "'");
        settle();
        assertThat(count(s, "SELECT COUNT(*) FROM Customer c")).isEqualTo(before);

        // backlinks of the selected customer in the inspector; a double-click opens one
        robot.interact(() -> view.data.table.getSelectionModel().clearAndSelect(0));
        settle();
        TreeItem<ObjectInspector.Entry> links = view.data.inspector.tree.getRoot().getChildren().stream()
                .filter(i -> i.getValue().key().equals(ObjectInspector.REFERENCED_BY)).findFirst().orElseThrow();
        assertThat(links.getValue().value()).isEqualTo("expand to look up");
        robot.interact(() -> links.setExpanded(true));
        settle();
        long purchases = count(s, "SELECT COUNT(*) FROM Purchase p WHERE p.customer = " + first.oid());
        assertThat(links.getChildren()).hasSize((int) purchases);
        assertThat(links.getValue().value()).isEqualTo("[ " + purchases + " objects ]");
        ObjectInspector.Entry link = links.getChildren().get(0).getValue();
        assertThat(link.refClass()).isEqualTo(PURCHASE);
        assertThat(link.type()).isEqualTo("→ Purchase");
        DynamicObject[] opened = new DynamicObject[1];
        view.editorDialog = editor -> {
            opened[0] = editor.original();
            return Optional.empty();
        };
        robot.interact(() -> view.openRef(link.refClass(), link.refOid()));
        settle();
        assertThat(opened[0].oid()).isEqualTo(link.refOid());
        robot.interact(() -> {
            view.data.inspector.tree.getRoot().getChildren().forEach(i -> i.setExpanded(i == links));
            view.data.inspector.tree.getSelectionModel().select(links);
            view.data.inspector.tree.scrollTo(view.data.inspector.tree.getRow(links) - 6);
        });
        settle();
        Shots.save(scene, "17-backlinks");

        // ON DELETE CASCADE: the customer's purchases go with it
        Platform.runLater(() -> view.delete.fire());
        assertThat(answer(robot, ButtonType.OK)).contains("Delete 1 object?");
        settle();
        assertThat(count(s, "SELECT COUNT(*) FROM Purchase p WHERE p.customer = " + first.oid())).isZero();
        assertThat(count(s, "SELECT COUNT(*) FROM Customer c")).isEqualTo(before - 1);

        // ON DELETE RESTRICT: a purchased product cannot be deleted
        showClass(robot, "Product");
        int row = 0;
        while (count(s, "SELECT COUNT(*) FROM Purchase p WHERE p.product = " + view.data.table.getItems().get(row).oid()) == 0) {
            row++;
        }
        int purchased = row;
        long product = view.data.table.getItems().get(row).oid();
        robot.interact(() -> view.data.table.getSelectionModel().clearAndSelect(purchased));
        Platform.runLater(() -> view.delete.fire());
        assertThat(answer(robot, ButtonType.OK)).contains("Delete 1 object?");
        assertThat(answer(robot, ButtonType.OK)).startsWith("Constraint violated").contains("ON DELETE RESTRICT");
        settle();
        assertThat(s.get("com.github.kookaburra.face.demo.Product", product)).isNotNull();

        // constraint column of the metadata panel
        showClass(robot, "Purchase");
        assertThat(view.schema.metadata.fields.getItems()).extracting(ClassMeta.FieldMeta::constraint)
                .contains("FK → Customer · CASCADE", "FK → Product · RESTRICT");
        robot.interact(() -> {
            view.tabs.getSelectionModel().select(2);
            view.schema.split.setDividerPositions(0.62);
        });
        settle();
        Shots.save(scene, "18-constraints");
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void decimalsIsolationAndBackup(boolean onDisk, FxRobot robot, @TempDir Path tmp) throws Exception {
        Session s = open(robot, onDisk, tmp);
        showClass(robot, "Product");

        // decimal field: typed text is parsed exactly, stored without trailing zeros, shown as plain text
        DynamicObject product = view.data.table.getItems().get(0);
        String[] error = new String[1];
        view.editorDialog = editor -> {
            ((TextField) editor.control("price")).setText("abc");
            try {
                editor.result();
            } catch (IllegalArgumentException e) {
                error[0] = e.getMessage();
            }
            ((TextField) editor.control("price")).setText("1234567890.123450");
            return Optional.of(editor.result());
        };
        robot.interact(() -> view.editObject(product));
        settle();
        assertThat(error[0]).contains("price").contains("not a valid decimal");
        DynamicObject saved = s.get(product.className(), product.oid());
        assertThat(saved.get("price")).isEqualTo(new BigDecimal("1234567890.12345"));
        assertThat(Display.text(saved.get("price"))).isEqualTo("1234567890.12345");
        assertThat(s.run("SELECT p.title FROM Product p WHERE p.price = 1234567890.12345").rows()).hasSize(1);

        // Begin's menu picks the isolation level
        Toggle serializable = view.isolation.getToggles().stream()
                .filter(t -> t.getUserData() == Isolation.SERIALIZABLE).findFirst().orElseThrow();
        robot.interact(() -> view.isolation.selectToggle(serializable));
        assertThat(view.txBegin.getTooltip().getText()).startsWith("Begin a SERIALIZABLE transaction");
        assertThat(view.txBegin.getItems()).hasSize(3);
        robot.interact(() -> view.txBegin.fire());
        settle();
        assertThat(view.txStatus.getText()).contains("SERIALIZABLE");
        robot.interact(() -> view.tabs.getSelectionModel().select(1));
        robot.interact(() -> view.query.setText("ROLLBACK"));
        robot.interact(() -> view.query.run.fire());
        settle();
        assertThat(view.txStatus.isVisible()).isFalse();

        // Backup: an empty folder is used as is, a non-empty one gets a time-stamped sub-folder
        long objects = count(s, "SELECT COUNT(*) FROM Customer c") + count(s, "SELECT COUNT(*) FROM Product p")
                + count(s, "SELECT COUNT(*) FROM Purchase p");
        Path target = tmp.resolve("backups");
        view.backupChooser = target::toFile;
        robot.interact(() -> view.backup.fire());
        settle();
        assertThat(view.status.getText()).startsWith("backup of").contains(objects + " objects").contains(target.toString());
        try (Session restored = Session.open(target)) {
            assertThat(count(restored, "SELECT COUNT(*) FROM Purchase p")).isEqualTo(count(s, "SELECT COUNT(*) FROM Purchase p"));
            assertThat(restored.get(product.className(), product.oid()).get("price")).isEqualTo(new BigDecimal("1234567890.12345"));
        }
        robot.interact(() -> view.backup.fire());
        settle();
        try (Stream<Path> files = Files.list(target)) {
            assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith(onDisk ? "db-backup-" : "demo-backup-"));
        }
        view.backupChooser = () -> null;
        robot.interact(() -> view.backup.fire());                        // cancelled: nothing happens
        settle();
        assertThat(view.backup.isDisabled()).isFalse();
    }
}
