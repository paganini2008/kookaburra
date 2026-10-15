package com.github.kookaburra.face.view;

import com.github.kookaburra.face.view.FormWidgets.Context;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Form editor of a list of embedded objects or a map: one row per element (map: per entry, with a key column),
 * one column per leaf of the element type (nested embedded objects flattened to dotted columns), and add /
 * remove / move up / move down, like the array editors of document-database tools. Values inside an element that
 * are themselves lists or maps are kept as they are (edited in the JSON tab).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class CollectionEditor {

    /** Column path of the map key. */
    static final String KEY = "$key";

    private final String path;
    private final TypeDef type;
    private final Context ctx;
    private final boolean map;
    private final boolean embedded;
    private final List<String> columns = new ArrayList<>();
    private final Map<String, TypeDef> columnTypes = new LinkedHashMap<>();
    private final GridPane grid = new GridPane();
    private final VBox box = new VBox(6);
    private final Label empty = new Label();
    /** Per row: column → widget. */
    private final List<Map<String, Leaf>> rows = new ArrayList<>();
    /** Per row: the element as loaded (keeps values that have no column). */
    private final List<Object> originals = new ArrayList<>();
    private boolean isNull = true;

    CollectionEditor(String path, TypeDef type, Context ctx) {
        this.path = path;
        this.type = type;
        this.ctx = ctx;
        this.map = type.kind() == ValueType.MAP;
        TypeDef element = type.element();
        this.embedded = element.kind() == ValueType.EMBEDDED;
        if (map) {
            columns.add(KEY);
            columnTypes.put(KEY, type.key());
        }
        if (embedded) {
            flatten(element.fields(), "");
        } else {
            columns.add("");
            columnTypes.put("", element);
        }
        grid.setHgap(6);
        grid.setVgap(4);
        grid.getStyleClass().add("k-collection");
        empty.getStyleClass().add("k-muted");
        Button add = new Button(map ? "+ Add entry" : "+ Add element");
        add.setId(path + "-add");
        add.setOnAction(e -> edit(values -> values.add(null)));
        box.getChildren().addAll(grid, empty, add);
        box.setId("field-" + path);
    }

    private void flatten(List<FieldDef> fields, String prefix) {
        for (FieldDef f : fields) {
            if (f.type().kind() == ValueType.EMBEDDED) {
                flatten(f.type().fields(), prefix + f.name() + ".");
            } else {
                columns.add(prefix + f.name());
                columnTypes.put(prefix + f.name(), f.type());
            }
        }
    }

    Leaf leaf() {
        return new Leaf(box, this::value, this::load);
    }

    /** Number of rows (for tests). */
    int size() {
        return rows.size();
    }

    // ---------------------------------------------------------------- model ⇄ rows

    /** The edited value: a list (elements are nested maps for embedded types) or a map; null when unset. */
    Object value() {
        List<Object> elements = elements();
        if (isNull && elements.isEmpty()) {
            return null;
        }
        if (!map) {
            return elements;
        }
        Map<Object, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Object key = rows.get(i).get(KEY).read().get();
            if (key == null) {
                throw new IllegalArgumentException(path + "[" + i + "]: the key is empty");
            }
            if (out.containsKey(key)) {
                throw new IllegalArgumentException(path + ": duplicate key '" + key + "'");
            }
            out.put(key, elements.get(i));
        }
        return out;
    }

    /** Element values in row order (map: values without keys). */
    private List<Object> elements() {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(element(i));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object element(int i) {
        Map<String, Leaf> row = rows.get(i);
        if (!embedded) {
            return row.get("").read().get();
        }
        Map<String, Object> out = copy(originals.get(i) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
        for (String c : columns) {
            if (!c.equals(KEY)) {
                set(out, c, row.get(c).read().get());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Map<?, ?> x ? copy((Map<String, Object>) x) : v));
        return out;
    }

    /** Sets a dotted path in nested maps; nested objects whose leaves are all null become null. */
    @SuppressWarnings("unchecked")
    private static void set(Map<String, Object> m, String dotted, Object v) {
        int dot = dotted.indexOf('.');
        if (dot < 0) {
            if (v == null) {
                m.remove(dotted);
            } else {
                m.put(dotted, v);
            }
            return;
        }
        String head = dotted.substring(0, dot);
        Map<String, Object> child = m.get(head) instanceof Map<?, ?> x ? (Map<String, Object>) x : new LinkedHashMap<>();
        set(child, dotted.substring(dot + 1), v);
        if (child.isEmpty()) {
            m.remove(head);
        } else {
            m.put(head, child);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object get(Object element, String dotted) {
        Object cur = element;
        for (String part : dotted.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = ((Map<String, Object>) m).get(part);
        }
        return cur;
    }

    /** Loads a stored value into the rows. */
    void load(Object v) {
        isNull = v == null;
        List<Object> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, x) -> {
                keys.add(k);
                values.add(x);
            });
        } else if (v instanceof List<?> l) {
            values.addAll(l);
        }
        render(keys, values);
    }

    /** Re-renders after a structural change (reads the current input first; input errors stop the change). */
    private void edit(Consumer<List<Object[]>> change) {
        List<Object[]> entries = new ArrayList<>();
        try {
            for (int i = 0; i < rows.size(); i++) {
                entries.add(new Object[]{map ? rows.get(i).get(KEY).read().get() : null, element(i)});
            }
        } catch (IllegalArgumentException e) {
            ctx.errors().accept(e.getMessage());
            return;
        }
        List<Object[]> list = new ArrayList<>(entries);
        change.accept(list);
        isNull = false;
        List<Object> keys = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Object[] e : list) {
            keys.add(e == null ? null : e[0]);
            values.add(e == null ? null : e[1]);
        }
        render(keys, values);
    }

    private void render(List<Object> keys, List<Object> values) {
        grid.getChildren().clear();
        rows.clear();
        originals.clear();
        int col = 1;
        for (String c : columns) {
            Label h = new Label(c.equals(KEY) ? "key" : c.isEmpty() ? "value" : c);
            h.getStyleClass().add("k-field-label");
            grid.add(h, col++, 0);
        }
        for (int i = 0; i < values.size(); i++) {
            int r = i;
            Object element = values.get(i);
            Map<String, Leaf> row = new LinkedHashMap<>();
            Label index = new Label(map ? "" : "[" + i + "]");
            index.getStyleClass().add("k-muted");
            grid.add(index, 0, i + 1);
            col = 1;
            for (String c : columns) {
                String cellPath = path + "[" + i + "]" + (c.isEmpty() ? "" : "." + c);
                Leaf leaf = FormWidgets.leaf(cellPath, columnTypes.get(c), true, ctx);
                leaf.write().accept(c.equals(KEY) ? keys.get(i) : c.isEmpty() ? element : get(element, c));
                if (leaf.node() instanceof TextField tf) {
                    tf.setPrefColumnCount(c.equals(KEY) ? 6 : columnTypes.get(c).kind() == ValueType.REF ? 11 : 7);
                    tf.setMinWidth(60);
                }
                row.put(c, leaf);
                grid.add(leaf.node(), col++, i + 1);
            }
            Button up = small("↑", "Move up", path + "-up-" + i, () -> edit(l -> swap(l, r, r - 1)));
            Button down = small("↓", "Move down", path + "-down-" + i, () -> edit(l -> swap(l, r, r + 1)));
            Button remove = small("✕", map ? "Remove entry" : "Remove element", path + "-remove-" + i, () -> edit(l -> l.remove(r)));
            up.setDisable(map || i == 0);
            down.setDisable(map || i == values.size() - 1);
            HBox actions = new HBox(2, up, down, remove);
            actions.setAlignment(Pos.CENTER_LEFT);
            grid.add(actions, col, i + 1);
            rows.add(row);
            originals.add(element);
        }
        empty.setText(values.isEmpty() ? (isNull ? "null" : map ? "{ } no entries" : "[ ] no elements") : "");
        empty.setManaged(values.isEmpty());
        empty.setVisible(values.isEmpty());
    }

    private static void swap(List<Object[]> l, int a, int b) {
        if (a >= 0 && b >= 0 && a < l.size() && b < l.size()) {
            Object[] x = l.get(a);
            l.set(a, l.get(b));
            l.set(b, x);
        }
    }

    private static Button small(String text, String tip, String id, Runnable action) {
        Button b = new Button(text);
        b.setId(id);
        b.getStyleClass().add("k-small");
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }
}
