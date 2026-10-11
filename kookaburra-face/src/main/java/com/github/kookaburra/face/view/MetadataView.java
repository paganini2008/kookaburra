package com.github.kookaburra.face.view;

import com.github.kookaburra.face.model.ClassMeta;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.function.Function;

/**
 * Sidebar panel with the metadata of the selected class: name, ids, hierarchy and its stored fields.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class MetadataView extends BorderPane {

    final Label title = new Label("METADATA");
    final Label className = new Label();
    final GridPane facts = new GridPane();
    final TableView<ClassMeta.FieldMeta> fields = new TableView<>();

    public MetadataView() {
        getStyleClass().add("k-metadata");
        title.getStyleClass().add("k-section-title");
        className.setId("meta-class");
        className.getStyleClass().add("k-meta-class");
        className.setWrapText(true);
        facts.setHgap(10);
        facts.setVgap(3);
        VBox top = new VBox(4, title, className, facts);
        top.setPadding(new Insets(0, 10, 8, 10));

        fields.setId("meta-fields");
        fields.setPlaceholder(new Label("Select a class"));
        fields.getColumns().add(column("field", ClassMeta.FieldMeta::path, 130));
        fields.getColumns().add(column("type", ClassMeta.FieldMeta::type, 170));
        fields.getColumns().add(column("index", ClassMeta.FieldMeta::index, 70));
        fields.getColumns().add(column("declared in", ClassMeta.FieldMeta::declaredIn, 90));
        fields.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // facts on top, the field table takes all remaining height and scrolls by itself
        setTop(top);
        setCenter(fields);
        setMinHeight(0);
    }

    private static TableColumn<ClassMeta.FieldMeta, String> column(String name, Function<ClassMeta.FieldMeta, String> value, double width) {
        TableColumn<ClassMeta.FieldMeta, String> c = new TableColumn<>(name);
        c.setCellValueFactory(d -> new SimpleStringProperty(value.apply(d.getValue())));
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setTooltip(empty || item == null || item.isEmpty() ? null : new Tooltip(item));   // full text when clipped
            }
        });
        c.setPrefWidth(width);
        return c;
    }

    public void show(ClassMeta m) {
        className.setText(m.name());
        facts.getChildren().clear();
        int row = 0;
        row = fact(row, "class id", String.valueOf(m.id()));
        row = fact(row, "extends", m.parent() == null ? "—" : simple(m.parent()));
        row = fact(row, "collection", simple(m.collection()));
        row = fact(row, "subclasses", m.subclasses().isEmpty() ? "—" : String.join(", ", m.subclasses()));
        row = fact(row, "@Id / @Version", m.idField() + " / " + (m.versionField() == null ? "—" : m.versionField()));
        row = fact(row, "layouts", String.valueOf(m.layouts()));
        fact(row, "objects", String.valueOf(m.count()));
        fields.getItems().setAll(m.fields());
    }

    private int fact(int row, String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("k-field-label");
        Label v = new Label(value);
        v.setId("meta-" + label.replaceAll("[^a-z]", ""));
        facts.add(l, 0, row);
        facts.add(v, 1, row);
        return row + 1;
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
