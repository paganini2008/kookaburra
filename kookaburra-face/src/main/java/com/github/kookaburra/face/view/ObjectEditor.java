package com.github.kookaburra.face.view;

import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.model.Display;
import com.github.kookaburra.face.model.Highlight;
import com.github.kookaburra.face.model.ObjectJson;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Editor for one object: a form with a widget per value type, and a JSON tab (kept in sync when switching).
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
    private final Map<String, Supplier<Object>> readers = new LinkedHashMap<>();
    private final Map<String, Node> controls = new LinkedHashMap<>();
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
        Tab formTab = new Tab("Form", new ScrollPane(form));
        Tab jsonTab = new Tab("JSON", new VirtualizedScrollPane<>(json));
        tabs.getTabs().addAll(formTab, jsonTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> {
            try {
                if (b.intValue() == 1) {
                    json.replaceText(ObjectJson.toJson(fromForm(), def));
                } else {
                    fill(ObjectJson.fromJson(object, def, json.getText()));
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
        setPrefSize(640, 560);
    }

    private void build(List<FieldDef> fields, String prefix, int[] row) {
        for (FieldDef f : fields) {
            String path = prefix + f.name();
            TypeDef t = f.type();
            if (t.kind() == ValueType.EMBEDDED) {
                Label section = new Label(path);
                section.getStyleClass().add("k-section-title");
                form.add(section, 0, row[0]++, 2, 1);
                build(t.fields(), path + ".", row);
                continue;
            }
            Label label = new Label(path);
            label.getStyleClass().add("k-field-label");
            Node control = control(path, t);
            control.setId("field-" + path);
            controls.put(path, control);
            form.add(label, 0, row[0]);
            form.add(control, 1, row[0]++);
        }
    }

    private Node control(String path, TypeDef t) {
        switch (t.kind()) {
            case BOOL -> {
                CheckBox c = new CheckBox();
                c.setAllowIndeterminate(true);
                readers.put(path, () -> c.isIndeterminate() ? null : c.isSelected());
                return c;
            }
            case ENUM -> {
                ComboBox<String> c = new ComboBox<>();
                c.getItems().add("");
                c.getItems().addAll(t.constants());
                readers.put(path, () -> c.getValue() == null || c.getValue().isEmpty() ? null : c.getValue());
                return c;
            }
            case DATE -> {
                DatePicker c = new DatePicker();
                readers.put(path, c::getValue);
                return c;
            }
            case MAP, BYTES, OBJECT, EMBEDDED_LIST -> {
                Label c = new Label("(edit in the JSON tab)");
                c.getStyleClass().add("k-muted");
                readers.put(path, () -> object.get(path));
                return c;
            }
            default -> {
                TextField c = new TextField();
                c.setPromptText(hint(t));
                readers.put(path, () -> parse(t, c.getText(), path));
                return c;
            }
        }
    }

    private static String hint(TypeDef t) {
        return switch (t.kind()) {
            case INT32, INT64 -> "integer";
            case FLOAT64 -> "number";
            case DATETIME -> "yyyy-MM-ddTHH:mm:ss (UTC)";
            case INSTANT -> "yyyy-MM-ddTHH:mm:ssZ";
            case REF -> "OID of " + t.name().substring(t.name().lastIndexOf('.') + 1);
            case MULTI -> "comma-separated " + t.element().kind().name().toLowerCase();
            default -> "";
        };
    }

    private static Object parse(TypeDef t, String text, String path) {
        String s = text == null ? "" : text.strip();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return switch (t.kind()) {
                case INT32, INT64 -> Long.parseLong(s);
                case FLOAT64 -> Double.parseDouble(s);
                case REF -> Ref.of(Long.parseLong(s));
                case MULTI -> {
                    List<Object> out = new ArrayList<>();
                    for (String part : s.split(",")) {
                        if (!part.isBlank()) {
                            out.add(parse(t.element(), part, path));
                        }
                    }
                    yield out;
                }
                default -> s;
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + ": '" + s + "' is not a valid " + t.kind().name().toLowerCase());
        }
    }

    /** Puts an object's values into the form. */
    @SuppressWarnings("unchecked")
    private void fill(DynamicObject o) {
        for (Map.Entry<String, Node> e : controls.entrySet()) {
            Object v = o.get(e.getKey());
            Node c = e.getValue();
            if (c instanceof CheckBox cb) {
                cb.setIndeterminate(v == null);
                cb.setSelected(Boolean.TRUE.equals(v));
            } else if (c instanceof ComboBox<?> combo) {
                ((ComboBox<String>) combo).setValue(v == null ? "" : String.valueOf(v));
            } else if (c instanceof DatePicker dp) {
                dp.setValue(v instanceof LocalDate d ? d : v == null ? null : LocalDate.parse(String.valueOf(v)));
            } else if (c instanceof TextField tf) {
                tf.setText(v == null ? "" : v instanceof Ref<?> r ? String.valueOf(r.oid())
                        : v instanceof List<?> l ? String.join(", ", l.stream().map(Display::text).toList()) : String.valueOf(v));
            }
        }
        object = o;
    }

    /** The edited object from the form (same OID / version). */
    DynamicObject fromForm() {
        DynamicObject out = new DynamicObject(object.className());
        out.assign(object.oid(), object.version());
        for (Map.Entry<String, Supplier<Object>> e : readers.entrySet()) {
            Object v = e.getValue().get();
            if (v != null) {
                out.set(e.getKey(), v);
            }
        }
        return out;
    }

    /** The edited object from whichever tab is active; throws with a readable message when invalid. */
    public DynamicObject result() {
        return tabs.getSelectionModel().getSelectedIndex() == 1 ? ObjectJson.fromJson(object, def, json.getText()) : fromForm();
    }

    /** The form control of a field path (for tests and tooling). */
    Node control(String path) {
        return controls.get(path);
    }

    public DynamicObject original() {
        return object;
    }
}
