package com.github.kookaburra.face.view;

import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.model.Display;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BorderPane;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Key / Value / Type tree of one object (the "tree view" of document-database tools such as Studio 3T and
 * MongoDB Compass): embedded objects, lists of embedded objects (one node per element) and maps (one node per key)
 * expand in place; double-clicking a reference opens the referenced object.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
public class ObjectInspector extends BorderPane {

    /** Lists with more elements than this start collapsed. */
    static final int EXPAND_MAX = 10;

    final TreeTableView<Entry> tree = new TreeTableView<>();
    final Label title = new Label("Select a row");
    private final Function<String, ClassDef> classDefs;
    private DynamicObject object;

    /**
     * One row of the tree.
     *
     * @param refClass target class of a reference (null for other values)
     */
    public record Entry(String key, String value, String type, String refClass, long refOid) {
    }

    /**
     * @param classDefs schema lookup by class name
     * @param openRef   called with (class, OID) when a reference row is double-clicked
     */
    public ObjectInspector(Function<String, ClassDef> classDefs, BiConsumer<String, Long> openRef) {
        this.classDefs = classDefs;
        getStyleClass().add("k-inspector");
        title.getStyleClass().add("k-section-title");
        setTop(title);
        tree.setId("inspector");
        tree.setShowRoot(false);
        tree.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY);
        tree.setPlaceholder(new Label("No object selected"));
        tree.getColumns().add(column("Key", Entry::key, 0.36));
        tree.getColumns().add(column("Value", Entry::value, 0.40));
        tree.getColumns().add(column("Type", Entry::type, 0.24));
        tree.setRowFactory(t -> {
            TreeTableRow<Entry> row = new TreeTableRow<>();
            row.setOnMouseClicked(e -> {
                Entry entry = row.getItem();
                if (e.getClickCount() == 2 && entry != null && entry.refClass() != null) {
                    openRef.accept(entry.refClass(), entry.refOid());
                }
            });
            return row;
        });
        setCenter(tree);
    }

    private static TreeTableColumn<Entry, String> column(String name, Function<Entry, String> value, double share) {
        TreeTableColumn<Entry, String> c = new TreeTableColumn<>(name);
        c.setSortable(false);
        c.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getValue() == null ? "" : value.apply(d.getValue().getValue())));
        c.setPrefWidth(400 * share);
        return c;
    }

    /** Shows an object (null = nothing). */
    public void show(DynamicObject o) {
        object = o;
        TreeItem<Entry> root = new TreeItem<>();
        if (o == null) {
            title.setText("Select a row");
        } else {
            ClassDef def = classDefs.apply(o.className());
            title.setText(def.simpleName() + "  #" + o.oid());
            for (FieldDef f : def.current().fields()) {
                root.getChildren().add(node(f.name(), f.type(), o.values().get(f.name())));
            }
        }
        tree.setRoot(root);
    }

    public DynamicObject object() {
        return object;
    }

    private TreeItem<Entry> node(String key, TypeDef t, Object v) {
        if (v == null) {
            return new TreeItem<>(new Entry(key, "null", typeName(t), null, 0));
        }
        switch (t.kind()) {
            case EMBEDDED -> {
                Map<?, ?> m = (Map<?, ?>) v;
                TreeItem<Entry> item = new TreeItem<>(new Entry(key, "{ " + count(m.size(), "field") + " }", typeName(t), null, 0));
                for (FieldDef f : t.fields()) {
                    item.getChildren().add(node(f.name(), f.type(), m.get(f.name())));
                }
                item.setExpanded(true);
                return item;
            }
            case EMBEDDED_LIST, MULTI -> {
                List<?> l = (List<?>) v;
                TreeItem<Entry> item = new TreeItem<>(new Entry(key, "[ " + count(l.size(), "element") + " ]", typeName(t), null, 0));
                for (int i = 0; i < l.size(); i++) {
                    item.getChildren().add(node("[" + i + "]", t.element(), l.get(i)));
                }
                item.setExpanded(l.size() <= EXPAND_MAX);
                return item;
            }
            case MAP -> {
                Map<?, ?> m = (Map<?, ?>) v;
                TreeItem<Entry> item = new TreeItem<>(new Entry(key, "{ " + count(m.size(), "entry") + " }", typeName(t), null, 0));
                m.forEach((k, x) -> item.getChildren().add(node(String.valueOf(k), t.element(), x)));
                item.setExpanded(m.size() <= EXPAND_MAX);
                return item;
            }
            case REF -> {
                long oid = v instanceof Ref<?> r ? r.oid() : ((Number) v).longValue();
                return new TreeItem<>(new Entry(key, Display.text(v), typeName(t), t.name(), oid));
            }
            default -> {
                return new TreeItem<>(new Entry(key, Display.text(v), typeName(t), null, 0));
            }
        }
    }

    private static String count(int n, String noun) {
        return n + " " + (n == 1 ? noun : noun.equals("entry") ? "entries" : noun + "s");
    }

    /** Short type label: {@code String}, {@code Address}, {@code List<Item>}, {@code Map<String, Place>}, {@code → Customer}. */
    static String typeName(TypeDef t) {
        if (t == null) {
            return "";
        }
        return switch (t.kind()) {
            case BOOL -> "Boolean";
            case INT32 -> "Int32";
            case INT64 -> "Int64";
            case FLOAT64 -> "Double";
            case STRING -> "String";
            case DATE -> "Date";
            case DATETIME -> "DateTime";
            case INSTANT -> "Instant";
            case BYTES -> "Bytes";
            case ENUM, EMBEDDED, OBJECT -> simple(t.name());
            case REF -> "→ " + simple(t.name());
            case MULTI, EMBEDDED_LIST -> (t.set() ? "Set<" : "List<") + typeName(t.element()) + ">";
            case MAP -> "Map<" + typeName(t.key()) + ", " + typeName(t.element()) + ">";
        };
    }

    private static String simple(String name) {
        return name == null ? "?" : name.substring(name.lastIndexOf('.') + 1);
    }
}
