package com.github.kookaburra.face.view;

import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nested objects and large values in Studio, in memory and on disk (the disk variant reads the demo data back from
 * {@code .odbf} segments after a reopen, and checks every edit again after closing and reopening the database):
 * grid summaries and tooltips, the Key / Value / Type inspector, reference navigation, the element-table editor
 * for lists of embedded objects and maps, binary fields with image preview, long texts.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioNestedTest {

    private static final int PAUSE = Integer.getInteger("studio.pause", 0);
    private static final String PURCHASE = "com.github.kookaburra.face.demo.Purchase";
    private static final String CUSTOMER = "com.github.kookaburra.face.demo.Customer";
    private static final String PRODUCT = "com.github.kookaburra.face.demo.Product";

    private MainView view;
    private Scene scene;
    private Path dir;

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

    /** Demo data in memory, or written to disk, flushed, compacted and reopened (served from segment files). */
    private Session open(FxRobot robot, boolean onDisk, Path tmp) {
        Session s;
        if (onDisk) {
            dir = tmp.resolve("db");
            Session fill = Session.open(dir);
            DemoData.fill(fill.db(), 120, 42);
            fill.db().flush();
            fill.db().compact();
            fill.close();
            s = Session.open(dir);
            try (var files = Files.walk(dir)) {
                assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.endsWith(".odbf"));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        } else {
            dir = null;
            s = Session.demo();
        }
        Session opened = s;
        robot.interact(() -> view.open(opened));
        settle();
        return s;
    }

    /** The database as a fresh reader sees it: on disk the view's database is closed and the directory reopened. */
    private void verifyStored(FxRobot robot, Consumer<Session> check) {
        if (dir == null) {
            check.accept(view.session());
            return;
        }
        robot.interact(() -> view.close());
        settle();
        Session reopened = Session.open(dir);
        try {
            check.accept(reopened);
        } finally {
            reopened.close();
        }
    }

    private void showClass(FxRobot robot, String simpleName) {
        robot.interact(() -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, simpleName)));
        settle();
    }

    private String cellText(String column, int row) {
        for (TableColumn<DynamicObject, ?> c : view.data.table.getColumns()) {
            if (column.equals(c.getUserData())) {
                return String.valueOf(c.getCellObservableValue(row).getValue());
            }
        }
        throw new AssertionError("no column " + column);
    }

    private int rowWhere(Predicate<DynamicObject> p) {
        List<DynamicObject> items = view.data.table.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (p.test(items.get(i))) {
                return i;
            }
        }
        throw new AssertionError("no matching row on this page");
    }

    /** Finds a node by id below {@code root} (ids with dots and brackets are not CSS selectors). */
    @SuppressWarnings("unchecked")
    static <T extends Node> T byId(Node root, String id) {
        if (id.equals(root.getId())) {
            return (T) root;
        }
        if (root instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                T found = byId(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static TreeItem<ObjectInspector.Entry> child(TreeItem<ObjectInspector.Entry> parent, String key) {
        return parent.getChildren().stream().filter(i -> i.getValue().key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no tree node " + key));
    }

    /** Opens the editor of {@code o}, lets {@code edit} work on it, then saves its result. */
    private ObjectEditor edit(FxRobot robot, DynamicObject o, Consumer<ObjectEditor> edit) {
        ObjectEditor[] captured = {null};
        view.editorDialog = editor -> {
            captured[0] = editor;
            edit.accept(editor);
            return editor.error.getText().isEmpty() ? Optional.of(editor.result()) : Optional.empty();
        };
        robot.interact(() -> view.editObject(o));
        settle();
        return captured[0];
    }

    /** Screenshot of an editor outside its dialog, styled like the window. */
    private void shot(ObjectEditor editor, String name) {
        Scene s = new Scene(editor, 820, 640);
        s.getStylesheets().setAll(scene.getStylesheets());
        s.getRoot().getStyleClass().setAll(scene.getRoot().getStyleClass());
        editor.applyCss();
        editor.layout();
        Shots.save(s, name);
    }

    // ------------------------------------------------------------------ tests

    @ParameterizedTest(name = "on disk: {0}")
    @ValueSource(booleans = {false, true})
    void gridSummariesInspectorAndReferences(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        open(robot, onDisk, tmp);
        showClass(robot, "Purchase");
        DynamicObject p = view.data.table.getItems().get(0);
        List<?> lines = (List<?>) p.get("lines");
        assertThat(cellText("lines", 0)).isEqualTo("[ " + lines.size() + (lines.size() == 1 ? " element ]" : " elements ]"));
        assertThat(cellText("customer", 0)).startsWith("→ ");

        // inspector: Key / Value / Type tree, lists expanded per element, nested price inside each line
        robot.interact(() -> view.data.table.getSelectionModel().select(0));
        settle();
        TreeItem<ObjectInspector.Entry> root = view.data.inspector.tree.getRoot();
        TreeItem<ObjectInspector.Entry> linesNode = child(root, "lines");
        assertThat(linesNode.getValue().type()).isEqualTo("List<Line>");
        assertThat(linesNode.isExpanded()).isTrue();
        assertThat(linesNode.getChildren()).hasSize(lines.size());
        TreeItem<ObjectInspector.Entry> price = child(child(linesNode, "[0]"), "price");
        assertThat(price.getValue().value()).isEqualTo("{ 2 fields }");
        assertThat(child(price, "currency").getValue().value()).isEqualTo("AUD");
        assertThat(child(price, "amount").getValue().type()).isEqualTo("Decimal");
        ObjectInspector.Entry product = child(child(linesNode, "[0]"), "product").getValue();
        assertThat(product.type()).isEqualTo("→ Product");
        assertThat(view.data.inspector.title.getText()).isEqualTo("Purchase  #" + p.oid());
        if (!onDisk) {
            robot.interact(() -> view.data.inspector.tree.getSelectionModel().select(price));
            Shots.save(scene, "14-nested-inspector");
        }

        // a reference opens the referenced object (subclass objects included); a dangling one is reported
        List<DynamicObject> opened = new ArrayList<>();
        view.editorDialog = editor -> {
            opened.add(editor.original());
            return Optional.empty();
        };
        robot.interact(() -> view.openRef(product.refClass(), product.refOid()));
        settle();
        assertThat(opened).hasSize(1);
        assertThat(opened.get(0).oid()).isEqualTo(product.refOid());
        robot.interact(() -> view.openRef(PRODUCT, 999_999L));
        settle();
        assertThat(view.status.getText()).contains("dangling reference", "#999999");

        // map of embedded objects, two-level embedding, long text
        showClass(robot, "Customer");
        robot.interact(() -> view.data.filter.setText("x.contacts IS NOT NULL AND x.notes IS NOT NULL"));
        robot.interact(() -> view.data.apply.fire());
        settle();
        int row = rowWhere(o -> ((Map<?, ?>) o.get("contacts")).size() >= 1);
        Map<?, ?> contacts = (Map<?, ?>) view.data.table.getItems().get(row).get("contacts");
        assertThat(cellText("contacts", row)).startsWith("{ " + contacts.size() + " entr");
        int r = row;
        robot.interact(() -> view.data.table.getSelectionModel().select(r));
        settle();
        TreeItem<ObjectInspector.Entry> contactsNode = child(view.data.inspector.tree.getRoot(), "contacts");
        assertThat(contactsNode.getValue().type()).isEqualTo("Map<String, Address>");
        assertThat(contactsNode.getChildren()).extracting(i -> i.getValue().key()).containsExactlyElementsOf(
                contacts.keySet().stream().map(String::valueOf).toList());
        assertThat(child(child(contactsNode.getChildren().get(0), "geo"), "lat").getValue().type()).isEqualTo("Double");
        assertThat(view.data.table.getColumns()).extracting(TableColumn::getUserData).contains("address.geo.lat", "address.geo.lon");

        robot.interact(() -> view.data.filter.setText("x.notes LIKE '%side door%'"));
        robot.interact(() -> view.data.apply.fire());
        settle();
        String notes = (String) view.data.table.getItems().get(0).get("notes");
        assertThat(notes.length()).isGreaterThan(200);
        assertThat(cellText("notes", 0)).hasSize(201).endsWith("…").doesNotContain("\n");

        // binary: format and size in the grid, type in the inspector
        showClass(robot, "Product");
        assertThat(cellText("photo", 0)).matches("PNG · [0-9.]+ (B|KiB)");
        robot.interact(() -> view.data.table.getSelectionModel().select(0));
        settle();
        assertThat(child(view.data.inspector.tree.getRoot(), "photo").getValue().type()).isEqualTo("Bytes");
        robot.interact(() -> view.data.table.getSelectionModel().clearSelection());
        settle();
        assertThat(view.data.inspector.title.getText()).isEqualTo("Select a row");
    }

    @ParameterizedTest(name = "on disk: {0}")
    @ValueSource(booleans = {false, true})
    void elementTableEditsListsOfEmbeddedObjects(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        Session s = open(robot, onDisk, tmp);
        long productOid = (Long) s.db().oql("SELECT p.oid FROM Product p ORDER BY p.oid LIMIT 1").list().get(0);
        showClass(robot, "Purchase");
        DynamicObject p = view.data.table.getItems().get(0);
        List<?> before = (List<?>) p.get("lines");

        ObjectEditor editor = edit(robot, p, e -> {
            Node table = e.control("lines");
            assertThat((Node) byId(table, "field-lines[" + (before.size() - 1) + "].sku")).isNotNull();
            assertThat((Node) byId(table, "field-lines[" + before.size() + "].sku")).isNull();
            // edit the first line, append a new one, move it to the top, remove the (old) last
            ((TextField) byId(table, "field-lines[0].qty")).setText("9");
            ((Button) byId(table, "lines-add")).fire();
            int n = before.size();
            ((TextField) byId(table, "field-lines[" + n + "].sku")).setText("SKU-NEW");
            ((TextField) byId(table, "field-lines[" + n + "].qty")).setText("2");
            ((TextField) byId(table, "field-lines[" + n + "].price.amount")).setText("12.5");
            ((TextField) byId(table, "field-lines[" + n + "].price.currency")).setText("NZD");
            ((TextField) byId(table, "field-lines[" + n + "].product")).setText(String.valueOf(productOid));
            for (int i = n; i > 0; i--) {
                ((Button) byId(table, "lines-up-" + i)).fire();
            }
            assertThat(((Button) byId(table, "lines-up-0")).isDisabled()).isTrue();
            assertThat(((TextField) byId(table, "field-lines[0].sku")).getText()).isEqualTo("SKU-NEW");
            ((Button) byId(table, "lines-down-0")).fire();
            ((Button) byId(table, "lines-up-1")).fire();
            if (!onDisk) {
                shot(e, "15-nested-editor");
            }
            ((Button) byId(table, "lines-remove-" + n)).fire();
        });
        assertThat(editor.error.getText()).isEmpty();
        settle();
        long oid = p.oid();
        int expected = before.size();          // + 1 added − 1 removed
        verifyStored(robot, db -> {
            DynamicObject saved = db.get(PURCHASE, oid);
            List<?> lines = (List<?>) saved.get("lines");
            assertThat(lines).hasSize(expected);
            Map<?, ?> first = (Map<?, ?>) lines.get(0);
            assertThat(first.get("sku")).isEqualTo("SKU-NEW");
            assertThat(((Number) first.get("qty")).intValue()).isEqualTo(2);
            assertThat(((Map<?, ?>) first.get("price")).get("currency")).isEqualTo("NZD");
            assertThat(((Map<?, ?>) first.get("price")).get("amount")).isEqualTo(new BigDecimal("12.5"));
            assertThat(((Ref<?>) first.get("product")).oid()).isEqualTo(productOid);
            if (expected > 1) {
                assertThat(((Number) ((Map<?, ?>) lines.get(1)).get("qty")).intValue()).isEqualTo(9);
            }
            assertThat(saved.version()).isEqualTo(p.version() + 1);
        });
    }

    @ParameterizedTest(name = "on disk: {0}")
    @ValueSource(booleans = {false, true})
    void mapEditorAndInputErrors(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        open(robot, onDisk, tmp);
        showClass(robot, "Customer");
        robot.interact(() -> view.data.filter.setText("x.contacts IS NULL"));
        robot.interact(() -> view.data.apply.fire());
        settle();
        DynamicObject c = view.data.table.getItems().get(0);

        // errors keep the dialog open: bad number inside an element, empty key, duplicate key
        ObjectEditor bad = edit(robot, c, e -> {
            Node map = e.control("contacts");
            assertThat(((Label) byId(map, "contacts-add").getParent().getChildrenUnmodifiable().get(1)).getText()).isEqualTo("null");
            ((Button) byId(map, "contacts-add")).fire();
            ((TextField) byId(map, "field-contacts[0].geo.lat")).setText("north");
            ((Button) byId(map, "contacts-add")).fire();          // refused: the row has an input error
            assertThat(e.error.getText()).contains("contacts[0].geo.lat", "north");
            e.error.setText("");
            ((TextField) byId(map, "field-contacts[0].geo.lat")).setText("-35.3");
            try {
                e.result();
                throw new AssertionError("empty key accepted");
            } catch (IllegalArgumentException ex) {
                e.error.setText(ex.getMessage());
            }
        });
        assertThat(bad.error.getText()).contains("key is empty");

        ObjectEditor good = edit(robot, c, e -> {
            Node map = e.control("contacts");
            ((Button) byId(map, "contacts-add")).fire();
            ((TextField) byId(map, "field-contacts[0].$key")).setText("work");
            ((TextField) byId(map, "field-contacts[0].city")).setText("Canberra");
            ((TextField) byId(map, "field-contacts[0].geo.lat")).setText("-35.3");
            ((TextField) byId(map, "field-contacts[0].geo.lon")).setText("149.13");
            ((Button) byId(map, "contacts-add")).fire();
            ((TextField) byId(map, "field-contacts[1].$key")).setText("work");
            try {
                e.result();
                throw new AssertionError("duplicate key accepted");
            } catch (IllegalArgumentException ex) {
                assertThat(ex.getMessage()).contains("duplicate key 'work'");
            }
            ((TextField) byId(map, "field-contacts[1].$key")).setText("billing");
            ((TextField) byId(map, "field-contacts[1].street")).setText("PO Box 7");
            // switching to JSON and back keeps the table in sync
            e.tabs.getSelectionModel().select(1);
            assertThat(e.json.getText()).contains("\"billing\"", "PO Box 7", "149.13");
            e.tabs.getSelectionModel().select(0);
            assertThat(((TextField) byId(e.control("contacts"), "field-contacts[1].street")).getText()).isEqualTo("PO Box 7");
        });
        assertThat(good.error.getText()).isEmpty();
        settle();
        long oid = c.oid();
        verifyStored(robot, db -> {
            Map<?, ?> contacts = (Map<?, ?>) db.get(CUSTOMER, oid).get("contacts");
            assertThat(contacts.keySet()).extracting(String::valueOf).containsExactly("work", "billing");
            Map<?, ?> work = (Map<?, ?>) contacts.get("work");
            assertThat(work.get("city")).isEqualTo("Canberra");
            assertThat(((Map<?, ?>) work.get("geo")).get("lon")).isEqualTo(149.13);
            assertThat(((Map<?, ?>) contacts.get("billing")).get("geo")).isNull();
            assertThat(db.run("SELECT COUNT(*) FROM Customer c WHERE c.contacts IS NOT NULL AND c.oid = " + oid).rows().get(0).get(0)).isEqualTo(1L);
        });
    }

    @ParameterizedTest(name = "on disk: {0}")
    @ValueSource(booleans = {false, true})
    void binaryFieldsAndLongTexts(boolean onDisk, FxRobot robot, @TempDir Path tmp) throws Exception {
        open(robot, onDisk, tmp);
        showClass(robot, "Product");
        DynamicObject p = view.data.table.getItems().get(0);
        byte[] original = (byte[]) p.get("photo");
        Path saved = tmp.resolve("photo.png");
        Path replacement = tmp.resolve("new.png");
        byte[] newPhoto = DemoData.photo(7, "Zed");
        Files.write(replacement, newPhoto);
        Path missing = tmp.resolve("missing.png");

        ObjectEditor editor = edit(robot, p, e -> {
            Node field = e.control("photo");
            Node box = e.widget("photo");
            assertThat(((Label) field).getText()).startsWith("PNG · ");
            assertThat(box.lookup(".image-view")).isNotNull();          // preview of the PNG
            e.files = (save, name) -> save ? saved.toFile() : missing.toFile();
            ((Button) byId(box, "field-photo-save")).fire();
            ((Button) byId(box, "field-photo-load")).fire();           // unreadable file: error, value kept
            assertThat(e.error.getText()).contains("cannot read", "missing.png");
            e.error.setText("");
            e.files = (save, name) -> save ? null : replacement.toFile();
            ((Button) byId(box, "field-photo-save")).fire();           // cancelled: nothing happens
            ((Button) byId(box, "field-photo-load")).fire();
            if (!onDisk) {
                shot(e, "16-binary-editor");
            }
            assertThat(((Label) field).getText()).isEqualTo("PNG · " + (newPhoto.length < 1024 ? newPhoto.length + " B"
                    : String.format(Locale.ROOT, "%.1f KiB", newPhoto.length / 1024.0)));
        });
        assertThat(editor.error.getText()).isEmpty();
        assertThat(Files.readAllBytes(saved)).isEqualTo(original);
        settle();

        // a large image: Base64 would be megabytes of JSON text, the JSON tab shows a placeholder that keeps the bytes
        byte[] big = new byte[3 << 20];
        new Random(5).nextBytes(big);
        System.arraycopy(newPhoto, 0, big, 0, 8);                                  // PNG signature
        Path bigFile = tmp.resolve("big.png");
        Files.write(bigFile, big);
        edit(robot, view.session().get(PRODUCT, p.oid()), e -> {
            e.files = (save, name) -> bigFile.toFile();
            ((Button) byId(e.widget("photo"), "field-photo-load")).fire();
            assertThat(((Label) e.control("photo")).getText()).isEqualTo("PNG · 3.0 MiB");
            assertThat(e.widget("photo").lookup(".image-view")).isNull();         // not a decodable image: no preview
        });
        settle();
        edit(robot, view.session().get(PRODUCT, p.oid()), e -> {
            e.tabs.getSelectionModel().select(1);
            String json = e.json.getText();
            assertThat(json).contains("<photo: PNG · 3.0 MiB, kept unchanged").hasSizeLessThan(2000);
            e.json.replaceText(json.replaceFirst("\"price\": [0-9.]+", "\"price\": 1.25"));
        });
        settle();
        DynamicObject afterJson = view.session().get(PRODUCT, p.oid());
        assertThat((byte[]) afterJson.get("photo")).isEqualTo(big);
        assertThat(afterJson.get("price")).isEqualTo(new BigDecimal("1.25"));
        ObjectEditor broken = edit(robot, afterJson, e -> {
            e.tabs.getSelectionModel().select(1);
            e.json.replaceText(e.json.getText().replaceFirst("\"<photo: [^\"]*\"", "\"not base64!\""));
            try {
                e.result();
                throw new AssertionError("invalid Base64 accepted");
            } catch (IllegalArgumentException ex) {
                e.error.setText(ex.getMessage());
            }
        });
        assertThat(broken.error.getText()).contains("photo: not valid Base64");
        settle();

        // clear: the photo becomes null, the preview disappears
        DynamicObject withNew = view.session().get(PRODUCT, p.oid());
        assertThat((byte[]) withNew.get("photo")).isEqualTo(big);
        edit(robot, withNew, e -> {
            Node box = e.widget("photo");
            ((Button) byId(box, "field-photo-clear")).fire();
            assertThat(((Label) e.control("photo")).getText()).isEqualTo("null");
            assertThat(box.lookup(".image-view")).isNull();
            assertThat(((Button) byId(box, "field-photo-save")).isDisabled()).isTrue();
        });
        settle();
        assertThat(view.session().get(PRODUCT, p.oid()).get("photo")).isNull();

        // long text: shown in a text area, edited there; a short one expands on request
        showClass(robot, "Customer");
        robot.interact(() -> view.data.filter.setText("x.notes LIKE '%side door%'"));
        robot.interact(() -> view.data.apply.fire());
        settle();
        DynamicObject c = view.data.table.getItems().get(0);
        String longText = ((String) c.get("notes")) + "\nLine added in Studio — 笑翠鸟 🐦";
        edit(robot, c, e -> {
            Node box = e.widget("notes");
            TextArea area = byId(box, "field-notes-text");
            assertThat(area).isNotNull();
            assertThat(area.getText()).isEqualTo(c.get("notes"));
            area.setText(longText);
        });
        settle();
        robot.interact(() -> view.data.filter.setText("x.notes IS NULL"));
        robot.interact(() -> view.data.apply.fire());
        settle();
        DynamicObject quiet = view.data.table.getItems().get(0);
        edit(robot, quiet, e -> {
            Node box = e.widget("notes");
            ((Button) byId(box, "field-notes-expand")).fire();
            TextArea area = byId(box, "field-notes-text");
            area.setText("first line\nsecond line");
        });
        settle();
        long longOid = c.oid();
        long quietOid = quiet.oid();
        long productOid = p.oid();
        verifyStored(robot, db -> {
            assertThat(db.get(CUSTOMER, longOid).get("notes")).isEqualTo(longText);
            assertThat(db.get(CUSTOMER, quietOid).get("notes")).isEqualTo("first line\nsecond line");
            assertThat(db.get(PRODUCT, productOid).get("photo")).isNull();
        });
    }
}
