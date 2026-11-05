package com.github.kookaburra.face.view;

import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.model.Clip;
import com.github.kookaburra.face.model.Display;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
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
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vector fields in Studio, in memory and on disk: grid cells, the form field and the JSON tab with invalid input
 * (wrong dimension, NaN, no brackets, text instead of numbers), clearing a vector, and the OQL console with
 * nearest-neighbour queries and their errors.
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioVectorTest {

    private static final String CLIP = Clip.class.getName();

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

    private boolean await(BooleanSupplier c) {
        for (int i = 0; i < 250 && !c.getAsBoolean(); i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        return c.getAsBoolean();
    }

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

    private Session open(FxRobot robot, boolean onDisk, Path tmp) {
        Session s = onDisk ? Session.open(tmp.resolve("db")) : Session.demo();
        s.db().insert(new Clip("x", 1, 0, 0));
        s.db().insert(new Clip("y", 0, 1, 0));
        s.db().insert(new Clip("none"));
        if (onDisk) {
            s.flush();
        }
        robot.interact(() -> view.open(s));
        settle();
        robot.interact(() -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, "Clip")));
        settle();
        return s;
    }

    private DynamicObject row(String name) {
        return view.data.table.getItems().stream().filter(o -> name.equals(o.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private String cell(String name, String column) {
        TableColumn<DynamicObject, Object> c = (TableColumn<DynamicObject, Object>) view.data.table.getColumns().stream()
                .filter(x -> column.equals(x.getText())).findFirst().orElseThrow();
        return Display.cell(c.getCellObservableValue(row(name)).getValue());
    }

    /** Tries a value in the form field: the error of {@code result()}, or null when it was accepted. */
    private static String tryForm(FxRobot robot, ObjectEditor editor, String text) {
        String[] error = new String[1];
        robot.interact(() -> {
            ((TextField) editor.control("embedding")).setText(text);
            try {
                editor.result();
            } catch (IllegalArgumentException e) {
                error[0] = e.getMessage();
            }
        });
        return error[0];
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void formAndGrid(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        Session s = open(robot, onDisk, tmp);
        assertThat(view.data.table.getItems()).hasSize(3);
        assertThat(cell("x", "embedding")).isEqualTo("[1.0, 0.0, 0.0]");
        assertThat(cell("none", "embedding")).isEmpty();

        List<String> errors = new ArrayList<>();
        view.editorDialog = editor -> {
            assertThat(((TextField) editor.control("embedding")).getText()).isEqualTo("[1.0, 0.0, 0.0]");
            assertThat(((TextField) editor.control("embedding")).getPromptText()).contains("3 numbers");
            errors.add(tryForm(robot, editor, "[1, 2]"));
            errors.add(tryForm(robot, editor, "[1, NaN, 0]"));
            errors.add(tryForm(robot, editor, "1, 2, 3"));
            errors.add(tryForm(robot, editor, "[1, two, 3]"));
            errors.add(tryForm(robot, editor, "[]"));
            errors.add(tryForm(robot, editor, "[1e40, 0, 0]"));          // float overflow: infinite
            errors.add(tryForm(robot, editor, "  [ 0.5 , 0.5, 0 ]  "));   // accepted
            DynamicObject[] out = new DynamicObject[1];
            robot.interact(() -> out[0] = editor.result());
            return Optional.of(out[0]);
        };
        robot.interact(() -> view.editObject(row("x")));
        settle();
        assertThat(errors.get(0)).contains("2 dimensions, expected 3");
        assertThat(errors.get(1)).contains("not finite");
        assertThat(errors.get(2)).contains("is not a vector");
        assertThat(errors.get(3)).contains("'two' is not a number");
        assertThat(errors.get(4)).contains("at least one dimension");
        assertThat(errors.get(5)).contains("not finite");
        assertThat(errors.get(6)).isNull();
        assertThat((float[]) s.reload(row("x")).get("embedding")).containsExactly(0.5f, 0.5f, 0);

        // a blank field clears the vector
        view.editorDialog = editor -> {
            assertThat(tryForm(robot, editor, "   ")).isNull();
            DynamicObject[] out = new DynamicObject[1];
            robot.interact(() -> out[0] = editor.result());
            return Optional.of(out[0]);
        };
        robot.interact(() -> view.editObject(row("y")));
        settle();
        assertThat(s.reload(row("y")).get("embedding")).isNull();
        assertThat(errorDialog(robot)).isNull();
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void jsonTab(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        Session s = open(robot, onDisk, tmp);
        List<String> errors = new ArrayList<>();
        view.editorDialog = editor -> {
            robot.interact(() -> editor.tabs.getSelectionModel().select(1));
            String json = editor.json.getText();
            assertThat(json).contains("\"embedding\"").contains("1.0");
            for (String bad : List.of("\"oops\"", "[1, 2]", "[1, \"a\", 3]", "{\"x\": 1}", "[]")) {
                robot.interact(() -> {
                    editor.json.replaceText(json.replaceFirst("\"embedding\" *: *\\[[^\\]]*\\]", "\"embedding\": " + bad));
                    try {
                        editor.result();
                        errors.add(null);
                    } catch (IllegalArgumentException e) {
                        errors.add(e.getMessage());
                    }
                });
            }
            DynamicObject[] out = new DynamicObject[1];
            robot.interact(() -> {
                editor.json.replaceText(json.replaceFirst("\"embedding\" *: *\\[[^\\]]*\\]", "\"embedding\": [0, 0, 2.5]"));
                out[0] = editor.result();
            });
            return Optional.of(out[0]);
        };
        robot.interact(() -> view.editObject(row("x")));
        settle();
        assertThat(errors.get(0)).contains("is not a vector");
        assertThat(errors.get(1)).contains("2 dimensions, expected 3");
        assertThat(errors.get(2)).contains("is not a number");
        assertThat(errors.get(3)).contains("is not a vector");
        assertThat(errors.get(4)).contains("at least one dimension");
        assertThat((float[]) s.reload(row("x")).get("embedding")).containsExactly(0, 0, 2.5f);
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void console(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        open(robot, onDisk, tmp);
        robot.interact(() -> view.tabs.getSelectionModel().select(1));
        robot.interact(() -> view.query.setText("SELECT c.name, cosine_distance(c.embedding, '[1, 0, 0]') AS d FROM Clip c ORDER BY d"));
        robot.interact(() -> view.query.run.fire());
        settle();
        assertThat(view.query.results.getItems()).hasSize(3);                 // x, y, then none (NULLS LAST)
        assertThat(view.query.message.getText()).doesNotContain("error");

        robot.interact(() -> view.query.setText("EXPLAIN SELECT c FROM Clip c ORDER BY l2_distance(c.embedding, '[1, 0, 0]') LIMIT 1"));
        robot.interact(() -> view.query.run.fire());
        settle();
        assertThat(await(() -> view.query.plan.getText().contains("Nearest  embedding by L2"))).as(view.query.plan.getText()).isTrue();

        for (String bad : List.of("SELECT c FROM Clip c ORDER BY cosine_distance(c.embedding, '[1, 0]') LIMIT 1",
                "SELECT cosine_distance(c.embedding, 'oops') FROM Clip c",
                "SELECT l2_distance(c.name, '[1, 0, 0]') FROM Clip c WHERE c.name = 'x'",
                "SELECT vector_dims(c.embedding, 1) FROM Clip c")) {
            robot.interact(() -> view.query.setText(bad));
            robot.interact(() -> view.query.run.fire());
            settle();
            assertThat(view.query.message.getStyleClass()).as(bad).contains("k-error");
        }
        assertThat(view.query.message.getText()).contains("VECTOR_DIMS takes 1 argument");
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void findSimilar(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        open(robot, onDisk, tmp);
        ContextMenu[] menu = new ContextMenu[1];
        robot.interact(() -> menu[0] = view.data.rowMenu(row("none")));
        assertThat(menu[0].getItems()).extracting(MenuItem::getText).containsExactly("Open", "Find similar by embedding");
        assertThat(menu[0].getItems().get(1).isDisable()).isTrue();                  // no vector: nothing to compare
        robot.interact(() -> menu[0] = view.data.rowMenu(row("x")));
        MenuItem similar = menu[0].getItems().get(1);
        assertThat(similar.isDisable()).isFalse();
        assertThat(view.data.rowMenu(null)).isNull();

        robot.interact(similar::fire);
        settle();
        assertThat(view.tabs.getSelectionModel().getSelectedIndex()).isEqualTo(1);
        assertThat(view.query.editor.getText()).startsWith("SELECT x, cosine_distance(x.embedding, :vector) AS distance FROM Clip x");
        assertThat(await(() -> view.query.results.getItems().size() == 2)).isTrue();   // y, then "none" (NULLS LAST); x left out
        assertThat(view.query.message.getStyleClass()).doesNotContain("k-error");

        // "Open" opens the editor
        boolean[] opened = {false};
        view.editorDialog = editor -> {
            opened[0] = true;
            return Optional.empty();
        };
        robot.interact(() -> view.data.rowMenu(row("y")).getItems().get(0).fire());
        settle();
        assertThat(opened[0]).isTrue();
    }

    /** The "From text" button next to the embedding field. */
    private static Button embedButton(ObjectEditor editor) {
        Button b = (Button) ((HBox) editor.control("embedding").getParent()).getChildren().get(1);
        assertThat(b.getId()).isEqualTo("field-embedding-embed");
        return b;
    }

    @ParameterizedTest(name = "on disk = {0}")
    @ValueSource(booleans = {false, true})
    void fromText(boolean onDisk, FxRobot robot, @TempDir Path tmp) {
        Session s = open(robot, onDisk, tmp);
        s.embedder(text -> text.equals("wrong") ? new float[]{1, 2} : new float[]{0, 0, text.length()});
        List<String> seen = new ArrayList<>();
        view.editorDialog = editor -> {
            Button embed = embedButton(editor);
            assertThat(embed.isDisable()).isFalse();
            TextField field = (TextField) editor.control("embedding");
            editor.embedWorker = Runnable::run;                                    // the dialog callback runs on the FX thread
            editor.askText = p -> null;                                            // cancelled: unchanged
            robot.interact(embed::fire);
            assertThat(field.getText()).isEqualTo("[1.0, 0.0, 0.0]");
            editor.askText = p -> "wrong";                                         // the model's dimension does not fit
            robot.interact(embed::fire);
            assertThat(editor.error.getText()).contains("returned 2 numbers, embedding needs 3");
            editor.askText = p -> "four";
            robot.interact(embed::fire);
            assertThat(field.getText()).isEqualTo("[0.0, 0.0, 4.0]");
            seen.add(field.getText());
            DynamicObject[] out = new DynamicObject[1];
            robot.interact(() -> out[0] = editor.result());
            return Optional.of(out[0]);
        };
        robot.interact(() -> view.editObject(row("x")));
        settle();
        assertThat(seen).containsExactly("[0.0, 0.0, 4.0]");
        assertThat((float[]) s.reload(row("x")).get("embedding")).containsExactly(0, 0, 4);

        s.embedder(text -> {
            throw new IllegalStateException("Ollama is not reachable");
        });
        view.editorDialog = editor -> {
            editor.embedWorker = Runnable::run;
            editor.askText = p -> "x";
            robot.interact(embedButton(editor)::fire);
            assertThat(editor.error.getText()).contains("Ollama is not reachable");
            return Optional.empty();
        };
        robot.interact(() -> view.editObject(row("x")));
        settle();

        s.embedder(null);
        view.editorDialog = editor -> {
            assertThat(embedButton(editor).isDisable()).isTrue();
            return Optional.empty();
        };
        robot.interact(() -> view.editObject(row("x")));
        settle();
    }
}
