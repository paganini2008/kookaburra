package com.github.kookaburra.face.view;

import com.github.kookaburra.DatabaseStats;
import com.github.kookaburra.face.model.StatsRows;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.function.Function;

/**
 * Stats tab: {@link DatabaseStats} of the current database: objects and storage per collection (top) and the
 * write, transaction, constraint and maintenance counters since it was opened (bottom).
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
public class StatsView extends BorderPane {

    final Label summary = new Label();
    final TableView<StatsRows.Collection> collections = new TableView<>();
    final TableView<StatsRows.Metric> metrics = new TableView<>();

    public StatsView() {
        getStyleClass().add("k-stats");
        summary.setId("stats-summary");
        summary.getStyleClass().add("k-section-title");
        summary.setPadding(new Insets(8, 10, 8, 10));

        collections.setId("stats-collections");
        collections.setPlaceholder(new Label("No collections"));
        collections.getColumns().add(column("collection", StatsRows.Collection::name, 140));
        collections.getColumns().add(column("objects", StatsRows.Collection::objects, 100));
        collections.getColumns().add(column("segments", StatsRows.Collection::segments, 80));
        collections.getColumns().add(column("size", StatsRows.Collection::size, 90));
        collections.getColumns().add(column("deleted rows", StatsRows.Collection::deleted, 100));
        collections.getColumns().add(column("unflushed rows", StatsRows.Collection::unflushed, 110));
        collections.getColumns().add(column("unflushed size", StatsRows.Collection::unflushedSize, 110));
        collections.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        metrics.setId("stats-metrics");
        metrics.getColumns().add(column("section", StatsRows.Metric::section, 120));
        metrics.getColumns().add(column("metric", StatsRows.Metric::name, 220));
        metrics.getColumns().add(column("value", StatsRows.Metric::value, 420));
        metrics.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        Label counters = new Label("COUNTERS SINCE OPEN");
        counters.getStyleClass().add("k-section-title");
        counters.setPadding(new Insets(8, 10, 4, 10));
        VBox bottom = new VBox(counters, metrics);
        VBox.setVgrow(metrics, Priority.ALWAYS);
        SplitPane split = new SplitPane(collections, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.35);
        setTop(summary);
        setCenter(split);
    }

    private static <T> TableColumn<T, String> column(String name, Function<T, String> value, double width) {
        TableColumn<T, String> c = new TableColumn<>(name);
        c.setCellValueFactory(d -> new SimpleStringProperty(value.apply(d.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        return c;
    }

    public void show(DatabaseStats s) {
        collections.getItems().setAll(StatsRows.collections(s));
        metrics.getItems().setAll(StatsRows.metrics(s));
        summary.setText(String.format(Locale.ROOT, "%,d objects in %d collections · %,d batches committed · %d active transactions",
                s.objects(), s.collections().size(), s.writes().batches(), s.transactions().active()));
    }
}
