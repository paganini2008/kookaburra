package com.github.kookaburra.face.view;

import com.github.kookaburra.face.model.SegmentInfo;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Schema tab: metadata of the class selected in the sidebar (left), segments per collection (memory / disk,
 * documents, live, garbage, size) and the stored layout history (right).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class SchemaView extends BorderPane {

    final MetadataView metadata = new MetadataView();
    final Label summary = new Label();
    final TableView<SegmentInfo> segments = new TableView<>();
    final TextArea layouts = new TextArea();

    public SchemaView() {
        getStyleClass().add("k-schema");
        summary.setId("storage-summary");
        summary.getStyleClass().add("k-section-title");
        summary.setPadding(new Insets(8, 10, 8, 10));

        segments.setId("segments");
        segments.setPlaceholder(new Label("No segments"));
        segments.getColumns().add(column("collection", SegmentInfo::collection, 120));
        segments.getColumns().add(column("segment", SegmentInfo::label, 90));
        segments.getColumns().add(column("kind", SegmentInfo::kind, 80));
        segments.getColumns().add(column("documents", i -> String.format(Locale.ROOT, "%,d", i.docs()), 100));
        segments.getColumns().add(column("live", i -> String.format(Locale.ROOT, "%,d", i.live()), 100));
        segments.getColumns().add(column("garbage", i -> String.format(Locale.ROOT, "%.0f %%", i.garbage() * 100), 80));
        segments.getColumns().add(column("size", i -> i.bytes() == 0 ? "—" : size(i.bytes()), 90));
        segments.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        layouts.setId("layouts");
        layouts.setEditable(false);
        layouts.getStyleClass().add("k-code-text");
        Label title = new Label("LAYOUT HISTORY");
        title.getStyleClass().add("k-section-title");
        title.setPadding(new Insets(8, 10, 4, 10));
        VBox bottom = new VBox(title, layouts);
        VBox.setVgrow(layouts, Priority.ALWAYS);

        BorderPane storage = new BorderPane();
        storage.setTop(summary);
        SplitPane right = new SplitPane(segments, bottom);
        right.setOrientation(Orientation.VERTICAL);
        right.setDividerPositions(0.6);
        storage.setCenter(right);
        SplitPane split = new SplitPane(metadata, storage);
        split.setDividerPositions(0.45);
        setCenter(split);
    }

    private static TableColumn<SegmentInfo, String> column(String name, Function<SegmentInfo, String> value, double width) {
        TableColumn<SegmentInfo, String> c = new TableColumn<>(name);
        c.setCellValueFactory(d -> new SimpleStringProperty(value.apply(d.getValue())));
        c.setPrefWidth(width);
        return c;
    }

    public void show(List<SegmentInfo> infos, String layoutText) {
        segments.getItems().setAll(infos);
        long disk = infos.stream().filter(i -> i.kind().equals("DISK")).count();
        long bytes = infos.stream().mapToLong(SegmentInfo::bytes).sum();
        long docs = infos.stream().mapToLong(SegmentInfo::docs).sum();
        long live = infos.stream().mapToLong(SegmentInfo::live).sum();
        summary.setText(infos.size() + " segments (" + disk + " on disk) · " + String.format(Locale.ROOT, "%,d", live)
                + " live of " + String.format(Locale.ROOT, "%,d", docs) + " documents · " + size(bytes));
        layouts.setText(layoutText);
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }
}
