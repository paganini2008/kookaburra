package com.github.kookaburra.face.view;

import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.face.model.Session;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.SchemaExport;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Export as Java in Studio, in memory and on disk: the toolbar button opens the preview with one file per class and
 * highlighted code; editing marks the file and Reset restores it; a target package regenerates (after confirming the
 * discarded edits) with {@code @Renamed}; an invalid package is refused; saving writes the files with the edits and
 * asks before replacing changed files; the button is disabled without a database.
 *
 * @Author: Fred Feng
 * @Date: 06/11/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class StudioJavaExportTest {

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

    private static int index(JavaExport x, String name) {
        List<SchemaExport.SourceFile> files = x.files.getItems();
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).path().endsWith("/" + name)) {
                return i;
            }
        }
        throw new AssertionError(name);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exportPreviewEditSave(boolean onDisk, @TempDir Path tmp, FxRobot robot) throws Exception {
        assertThat(view.exportJava.isDisabled()).isTrue();
        Session s;
        if (onDisk) {
            s = Session.open(tmp.resolve("db"));
            DemoData.fill(s.db(), 5, 7);
            s.flush();
        } else {
            s = Session.demo();
        }
        robot.interact(() -> view.open(s));
        settle();
        assertThat(view.exportJava.isDisabled()).isFalse();
        Path out = tmp.resolve("src");
        robot.interact(() -> view.javaChooser = out::toFile);
        robot.clickOn("#export-java");
        WaitForAsyncUtils.waitForFxEvents();
        JavaExport x = robot.lookup(".k-java-export").queryAs(JavaExport.class);
        List<String> asked = new ArrayList<>();
        boolean[] answer = {true};
        robot.interact(() -> x.confirm = (t, m) -> {
            asked.add(t);
            return answer[0];
        });

        assertThat(x.files.getItems()).extracting(SchemaExport.SourceFile::path)
                .contains("com/github/kookaburra/face/demo/Product.java", "com/github/kookaburra/face/demo/Customer.java",
                        "com/github/kookaburra/face/demo/Address.java");
        int product = index(x, "Product.java");
        robot.interact(() -> x.files.getSelectionModel().select(product));
        assertThat(x.editor.getText()).contains("@Entity", "@Comment(\"Things the shop sells: books, gadgets and home items\")",
                "@FullText", "@Vector(dim = 4)", "public class Product {");
        assertThat(x.editor.getStyleSpans(0, x.editor.getLength()).styleStream().flatMap(c -> c.stream()))
                .contains("ann", "kw", "comment", "str");
        assertThat(x.reset.isDisabled()).isTrue();
        Shots.save(x.getScene(), "java-export-" + (onDisk ? "disk" : "memory"));

        // edit, the file is marked; reset restores it
        robot.interact(() -> x.editor.insertText(0, "// my note\n"));
        assertThat(x.edits).containsKey("com/github/kookaburra/face/demo/Product.java");
        assertThat(x.reset.isDisabled()).isFalse();
        robot.interact(() -> x.files.getSelectionModel().select(index(x, "Customer.java")));
        robot.interact(() -> x.files.getSelectionModel().select(product));
        assertThat(x.editor.getText()).startsWith("// my note\n");        // edits survive switching files
        robot.interact(() -> x.reset.fire());
        assertThat(x.edits).isEmpty();
        assertThat(x.editor.getText()).startsWith("package com.github.kookaburra.face.demo;");
        robot.interact(() -> x.editor.insertText(0, "// again\n"));
        robot.interact(() -> x.editor.deleteText(0, "// again\n".length()));  // back to the generated text: not edited
        assertThat(x.edits).isEmpty();

        // save, then save again over an edited file: asks, and "no" keeps the files
        robot.interact(() -> x.save.fire());
        Path productFile = out.resolve("com/github/kookaburra/face/demo/Product.java");
        assertThat(Files.readString(productFile)).isEqualTo(x.export().files().get(product).source());
        assertThat(x.status.getText()).startsWith("saved ").endsWith("→ src");
        robot.interact(() -> x.save.fire());                              // same content: no question
        assertThat(asked).isEmpty();
        robot.interact(() -> x.editor.insertText(0, "// mine\n"));
        answer[0] = false;
        robot.interact(() -> x.save.fire());
        assertThat(asked).containsExactly("Replace files");
        assertThat(x.status.getText()).isEqualTo("not saved");
        assertThat(Files.readString(productFile)).doesNotStartWith("// mine");
        answer[0] = true;
        robot.interact(() -> x.save.fire());
        assertThat(Files.readString(productFile)).startsWith("// mine\n");
        assertThat(x.status.getText()).contains("(1 edited)");

        // another package: edits are discarded after confirming; "no" keeps them
        answer[0] = false;
        robot.interact(() -> {
            x.pkg.setText("com.acme.shop");
            x.regenerate.fire();
        });
        assertThat(x.edits).hasSize(1);
        answer[0] = true;
        robot.interact(() -> x.regenerate.fire());
        assertThat(x.edits).isEmpty();
        assertThat(x.files.getItems()).extracting(SchemaExport.SourceFile::path)
                .contains("com/acme/shop/Product.java").noneMatch(p -> p.startsWith("com/github"));
        assertThat(x.warnings.getText()).contains("Classes move to package com.acme.shop");
        assertThat(x.warnings.isVisible()).isTrue();
        robot.interact(() -> x.files.getSelectionModel().select(index(x, "Product.java")));
        assertThat(x.editor.getText()).contains("@Renamed(\"com.github.kookaburra.face.demo.Product\")");

        // invalid packages are refused, the code stays
        for (String bad : List.of("com..acme", "1abc", "com.class", "a-b", ".x")) {
            robot.interact(() -> {
                x.pkg.setText(bad);
                x.regenerate.fire();
            });
            assertThat(x.status.getText()).as(bad).isEqualTo("not a package name: " + bad);
        }
        assertThat(x.files.getItems()).extracting(SchemaExport.SourceFile::path).contains("com/acme/shop/Product.java");
        robot.interact(() -> {
            x.pkg.setText("  ");
            x.regenerate.fire();                                          // blank: back to the stored packages
        });
        assertThat(x.files.getItems()).extracting(SchemaExport.SourceFile::path)
                .contains("com/github/kookaburra/face/demo/Product.java");
        assertThat(x.warnings.isVisible()).isFalse();

        // chooser cancelled: nothing happens
        robot.interact(() -> x.chooser = () -> null);
        String before = x.status.getText();
        robot.interact(() -> x.save.fire());
        assertThat(x.status.getText()).isEqualTo(before);
        DialogPane pane = robot.lookup(".dialog-pane").queryAs(DialogPane.class);
        robot.interact(() -> ((Button) pane.lookupButton(ButtonType.CLOSE)).fire());
        assertThat(robot.lookup(".k-java-export").tryQuery()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyDatabase(boolean onDisk, @TempDir Path tmp, FxRobot robot) {
        JavaExport[] x = new JavaExport[1];
        Session s = onDisk ? Session.open(tmp.resolve("empty")) : null;
        robot.interact(() -> x[0] = new JavaExport(p -> SchemaExport.of(Schema.empty(), p),
                () -> tmp.toFile(), (t, m) -> true));
        assertThat(x[0].files.getItems()).isEmpty();
        assertThat(x[0].status.getText()).isEqualTo("no stored classes");
        assertThat(x[0].save.isDisabled()).isTrue();
        assertThat(x[0].editor.isDisabled()).isTrue();
        robot.interact(() -> x[0].save());                                 // nothing to save
        if (s != null) {
            assertThat(s.javaCode(null).files()).isEmpty();
            s.close();
        }
    }
}
