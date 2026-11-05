package com.github.kookaburra.face.view;

import com.github.kookaburra.face.model.Highlight;
import com.github.kookaburra.mapping.SchemaExport;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Export as Java: the stored schema as Java classes (entities, embeddables, enums with their annotations and
 * comments), previewed per file with highlighting and editable before saving. The package can be changed (the
 * classes then carry {@code @Renamed}); edited files are marked and can be reset; saving writes the files under a
 * chosen source folder and asks before replacing existing files.
 *
 * @Author: Fred Feng
 * @Date: 06/11/2026
 * @Version 1.0.0
 */
public class JavaExport extends BorderPane {

    final TextField pkg = new TextField();
    final Button regenerate = new Button("Generate");
    final Label warnings = new Label();
    final ListView<SchemaExport.SourceFile> files = new ListView<>();
    final CodeArea editor = new CodeArea();
    final Button reset = new Button("Reset file");
    final Button copy = new Button("Copy");
    final Button save = new Button("Save…");
    final Label status = new Label();

    /** Generates the code for a package (null = stored packages). */
    private final Function<String, SchemaExport> generator;
    /** Chooses the source folder to save into; null cancels. */
    Supplier<File> chooser;
    /** Asks a yes / no question (title, message); replaceable in tests. */
    BiPredicate<String, String> confirm;

    private SchemaExport export;
    /** Text per file path as edited; absent = as generated. */
    final Map<String, String> edits = new LinkedHashMap<>();
    private boolean loading;

    public JavaExport(Function<String, SchemaExport> generator, Supplier<File> chooser, BiPredicate<String, String> confirm) {
        this.generator = generator;
        this.chooser = chooser;
        this.confirm = confirm;
        getStyleClass().add("k-java-export");
        setPrefSize(1060, 680);

        pkg.setId("java-package");
        pkg.setPromptText("keep the stored packages");
        pkg.setPrefColumnCount(26);
        pkg.setTooltip(new Tooltip("Move every class into this package: the classes then carry @Renamed, and opening"
                + " the database with them renames the stored classes"));
        regenerate.setId("java-generate");
        regenerate.setGraphic(Icons.of("mdi2r-refresh"));
        regenerate.setOnAction(e -> generate());
        pkg.setOnAction(e -> generate());
        Label pkgLabel = new Label("Package");
        HBox top = new HBox(8, pkgLabel, pkg, regenerate);
        top.setAlignment(Pos.CENTER_LEFT);
        warnings.setId("java-warnings");
        warnings.getStyleClass().add("k-java-warnings");
        warnings.setMaxWidth(Double.MAX_VALUE);
        VBox header = new VBox(6, top, warnings);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        files.setId("java-files");
        files.getStyleClass().add("k-java-files");
        files.setCellFactory(l -> new ListCell<>() {
            @Override
            protected void updateItem(SchemaExport.SourceFile f, boolean empty) {
                super.updateItem(f, empty);
                getStyleClass().remove("k-java-edited");
                if (empty || f == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                boolean edited = edits.containsKey(f.path());
                setText(f.path().substring(f.path().lastIndexOf('/') + 1) + (edited ? "  ● edited" : ""));
                setTooltip(new Tooltip(f.path()));
                if (edited) {
                    getStyleClass().add("k-java-edited");
                }
            }
        });
        files.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> show(b));

        editor.setId("java-editor");
        editor.getStyleClass().add("k-code");
        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.textProperty().addListener((o, a, b) -> {
            editor.setStyleSpans(0, Highlight.java(b));
            if (!loading) {
                edited(b);
            }
        });
        SplitPane split = new SplitPane(files, new VirtualizedScrollPane<>(editor));
        split.setDividerPositions(0.24);
        setCenter(split);

        reset.setId("java-reset");
        reset.setGraphic(Icons.of("mdi2u-undo-variant"));
        reset.setTooltip(new Tooltip("Discard the edits of this file"));
        reset.setOnAction(e -> resetFile());
        copy.setId("java-copy");
        copy.setGraphic(Icons.of("mdi2c-content-copy"));
        copy.setTooltip(new Tooltip("Copy this file to the clipboard"));
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(editor.getText());
            Clipboard.getSystemClipboard().setContent(c);
            status.setText("copied " + selectedName());
        });
        save.setId("java-save");
        save.setGraphic(Icons.of("mdi2c-content-save-outline"));
        save.setTooltip(new Tooltip("Write every file under a source folder (package folders are created)"));
        save.setOnAction(e -> save());
        status.setId("java-status");
        status.getStyleClass().add("k-muted");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bottom = new HBox(8, reset, copy, status, gap, save);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(8, 0, 0, 0));
        setBottom(bottom);
        generate();
    }

    /** The code as generated now (null before the first generation). */
    public SchemaExport export() {
        return export;
    }

    /** Regenerates for the package in the field; edits are discarded after a confirmation. */
    void generate() {
        if (!edits.isEmpty() && !confirm.test("Discard edits",
                edits.size() + " edited file" + (edits.size() == 1 ? "" : "s") + " will be generated again. Discard the edits?")) {
            return;
        }
        String p = pkg.getText().trim();
        if (!p.isEmpty() && !validPackage(p)) {
            status.setText("not a package name: " + p);
            return;
        }
        export = generator.apply(p.isEmpty() ? null : p);
        edits.clear();
        warnings.setText(String.join("\n", export.warnings()));
        warnings.setVisible(!export.warnings().isEmpty());
        warnings.setManaged(!export.warnings().isEmpty());
        files.getItems().setAll(export.files());
        if (export.files().isEmpty()) {
            show(null);
            status.setText("no stored classes");
        } else {
            files.getSelectionModel().select(0);
            status.setText(export.files().size() + " file" + (export.files().size() == 1 ? "" : "s"));
        }
        save.setDisable(export.files().isEmpty());
    }

    static boolean validPackage(String p) {
        for (String part : p.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))
                    || !part.chars().allMatch(Character::isJavaIdentifierPart) || KEYWORDS.contains(part)) {
                return false;
            }
        }
        return true;
    }

    private static final List<String> KEYWORDS = List.of("abstract", "boolean", "byte", "case", "catch", "char", "class",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "new", "null", "package", "private",
            "protected", "public", "return", "short", "static", "super", "switch", "this", "throw", "throws", "try",
            "void", "volatile", "while", "true", "false");

    private void show(SchemaExport.SourceFile f) {
        loading = true;
        try {
            editor.replaceText(f == null ? "" : text(f));
            editor.moveTo(0);
            editor.requestFollowCaret();
        } finally {
            loading = false;
        }
        editor.setDisable(f == null);
        reset.setDisable(f == null || !edits.containsKey(f.path()));
        copy.setDisable(f == null);
    }

    /** The current text of a file: edited or generated. */
    String text(SchemaExport.SourceFile f) {
        return edits.getOrDefault(f.path(), f.source());
    }

    private void edited(String text) {
        SchemaExport.SourceFile f = files.getSelectionModel().getSelectedItem();
        if (f == null) {
            return;
        }
        if (text.equals(f.source())) {
            edits.remove(f.path());
        } else {
            edits.put(f.path(), text);
        }
        reset.setDisable(!edits.containsKey(f.path()));
        files.refresh();
    }

    private void resetFile() {
        SchemaExport.SourceFile f = files.getSelectionModel().getSelectedItem();
        if (f != null) {
            edits.remove(f.path());
            show(f);
            files.refresh();
            status.setText("reset " + selectedName());
        }
    }

    private String selectedName() {
        SchemaExport.SourceFile f = files.getSelectionModel().getSelectedItem();
        return f == null ? "" : f.path().substring(f.path().lastIndexOf('/') + 1);
    }

    /** Writes every file under the chosen folder; asks before replacing files whose content differs. */
    void save() {
        if (export == null || export.files().isEmpty()) {
            return;
        }
        File dir = chooser.get();
        if (dir == null) {
            return;
        }
        Path root = dir.toPath();
        try {
            List<String> replaced = new ArrayList<>();
            for (SchemaExport.SourceFile f : export.files()) {
                Path p = root.resolve(f.path());
                if (Files.exists(p) && !Files.readString(p, StandardCharsets.UTF_8).equals(text(f))) {
                    replaced.add(f.path());
                }
            }
            if (!replaced.isEmpty() && !confirm.test("Replace files", "These files exist and will be replaced:\n"
                    + String.join("\n", replaced))) {
                status.setText("not saved");
                return;
            }
            for (SchemaExport.SourceFile f : export.files()) {
                Path p = root.resolve(f.path());
                Files.createDirectories(p.getParent());
                Files.writeString(p, text(f), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int n = export.files().size();
        status.setText("saved " + n + " file" + (n == 1 ? "" : "s") + (edits.isEmpty() ? "" : " (" + edits.size() + " edited)")
                + " → " + root.getFileName());
    }

    /** Shows the export in a dialog. */
    public static void show(Window owner, Function<String, SchemaExport> generator, Supplier<File> chooser) {
        Dialog<Void> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle("Export schema as Java");
        d.setResizable(true);
        JavaExport view = new JavaExport(generator, chooser, (t, m) -> Dialogs.confirm(owner, t, m));
        d.getDialogPane().setContent(view);
        d.getDialogPane().setPrefSize(1100, 760);
        d.getDialogPane().setMinSize(980, 640);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.getDialogPane().getStyleClass().add("k-dialog");
        d.show();
    }
}
