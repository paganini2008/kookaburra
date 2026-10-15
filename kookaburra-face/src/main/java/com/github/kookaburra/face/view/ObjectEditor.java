package com.github.kookaburra.face.view;

import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.model.Highlight;
import com.github.kookaburra.face.model.ObjectJson;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Editor for one object: a form with a widget per value type ({@link FormWidgets}; embedded objects as titled
 * sections, lists of embedded objects and maps as element tables), and a JSON tab (kept in sync when switching).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class ObjectEditor extends BorderPane {

    final TabPane tabs = new TabPane();
    final GridPane form = new GridPane();
    final CodeArea json = new CodeArea();
    final Label error = new Label();
    final Label header = new Label();
    private final ClassDef def;
    private final Map<String, Leaf> controls = new LinkedHashMap<>();
    /** Large binaries shown as placeholders in the JSON tab. */
    private final Map<String, byte[]> kept = new HashMap<>();
    /** Picks a file for binary fields: (save?, suggested name) → file or null. Replaceable in tests. */
    BiFunction<Boolean, String, File> files = this::chooseFile;
    private final FormWidgets.Context context = new FormWidgets.Context((save, name) -> files.apply(save, name), this::showError);
    private DynamicObject object;

    public ObjectEditor(ClassDef def, DynamicObject object) {
        this.def = def;
        this.object = object;
        getStyleClass().add("k-editor");
        header.getStyleClass().add("k-editor-header");
        header.setText(def.simpleName() + (object.oid() == 0 ? "  · new" : "  #" + object.oid() + "  · version " + object.version()));
        setTop(header);

        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(12));
        ColumnConstraints labels = new ColumnConstraints();
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(labels, fields);
        int[] row = {0};
        build(def.current().fields(), "", row);
        fill(object);

        json.setId("json-editor");
        json.getStyleClass().add("k-code");
        json.textProperty().addListener((o, a, b) -> json.setStyleSpans(0, Highlight.json(b)));
        ScrollPane formScroll = new ScrollPane(form);
        formScroll.setFitToWidth(true);
        Tab formTab = new Tab("Form", formScroll);
        Tab jsonTab = new Tab("JSON", new VirtualizedScrollPane<>(json));
        tabs.getTabs().addAll(formTab, jsonTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> {
            try {
                if (b.intValue() == 1) {
                    kept.clear();
                    json.replaceText(ObjectJson.toEditorJson(fromForm(), def, kept));
                } else {
                    fill(ObjectJson.fromJson(object, def, json.getText(), kept));
                }
                error.setText("");
            } catch (RuntimeException e) {
                error.setText(e.getMessage());
            }
        });
        setCenter(tabs);
        error.setId("editor-error");
        error.getStyleClass().add("k-error");
        error.setWrapText(true);
        setBottom(error);
        setPrefSize(760, 600);
    }

    private void build(List<FieldDef> fields, String prefix, int[] row) {
        for (FieldDef f : fields) {
            String path = prefix + f.name();
            TypeDef t = f.type();
            if (t.kind() == ValueType.EMBEDDED) {
                Label section = new Label(path + "  · " + ObjectInspector.typeName(t));
                section.getStyleClass().add("k-section-title");
                form.add(section, 0, row[0]++, 2, 1);
                build(t.fields(), path + ".", row);
                continue;
            }
            Label label = new Label(path);
            label.getStyleClass().add("k-field-label");
            label.setTooltip(new Tooltip(ObjectInspector.typeName(t)));
            Leaf leaf = FormWidgets.leaf(path, t, false, context);
            controls.put(path, leaf);
            form.add(label, 0, row[0]);
            form.add(leaf.node(), 1, row[0]++);
            GridPane.setValignment(label, VPos.TOP);               // aligned with the first line of tall widgets
            label.setPadding(new Insets(7, 0, 0, 0));
        }
    }

    private File chooseFile(boolean save, String name) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(save ? "Save " + name : "Load " + name);
        if (save) {
            chooser.setInitialFileName(name);
        }
        Window w = getScene() == null ? null : getScene().getWindow();
        return save ? chooser.showSaveDialog(w) : chooser.showOpenDialog(w);
    }

    /** Puts an object's values into the form. */
    private void fill(DynamicObject o) {
        for (Map.Entry<String, Leaf> e : controls.entrySet()) {
            e.getValue().write().accept(o.get(e.getKey()));
        }
        object = o;
    }

    /** The edited object from the form (same OID / version). */
    DynamicObject fromForm() {
        DynamicObject out = new DynamicObject(object.className());
        out.assign(object.oid(), object.version());
        for (Map.Entry<String, Leaf> e : controls.entrySet()) {
            Object v = e.getValue().read().get();
            if (v != null) {
                out.set(e.getKey(), v);
            }
        }
        return out;
    }

    /** The edited object from whichever tab is active; throws with a readable message when invalid. */
    public DynamicObject result() {
        return tabs.getSelectionModel().getSelectedIndex() == 1 ? ObjectJson.fromJson(object, def, json.getText(), kept) : fromForm();
    }

    /** The whole widget of a field path, e.g. a text field with its expand button (for tests and tooling). */
    Node widget(String path) {
        Leaf leaf = controls.get(path);
        return leaf == null ? null : leaf.node();
    }

    /** The form control of a field path (for tests and tooling). */
    Node control(String path) {
        Leaf leaf = controls.get(path);
        return leaf == null ? null : leaf.input();
    }

    private void showError(String message) {
        error.setText(message);
    }

    public DynamicObject original() {
        return object;
    }
}
