package com.github.kookaburra.face.view;

import com.github.kookaburra.DatabaseStats;
import com.github.kookaburra.face.model.StatsRows;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.StringConverter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Stats tab, a dashboard of {@link DatabaseStats} for the current database: key figures, live write and
 * transaction rates (sampled while the tab is shown), objects and storage per collection, and the full tables
 * (collections, counters since open).
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
public class StatsView extends BorderPane {

    /** Samples kept per database: 5 minutes at one sample every 2 s. */
    static final int HISTORY = 150;

    /** One sample: seconds since the first one, the statistics and the rates since the previous sample. */
    record Sample(double at, DatabaseStats stats, StatsRows.Rates rates) {
    }

    final Label summary = new Label();
    final HBox tiles = new HBox(10);
    final Map<String, Label[]> tileLabels = new LinkedHashMap<>();
    final LineChart<Number, Number> writes = lineChart("stats-writes");
    final LineChart<Number, Number> transactions = lineChart("stats-transactions");
    final BarChart<String, Number> objects = new BarChart<>(new CategoryAxis(), new NumberAxis());
    final StackedBarChart<String, Number> storage = new StackedBarChart<>(new CategoryAxis(), new NumberAxis());
    final TableView<StatsRows.Collection> collections = new TableView<>();
    final TableView<StatsRows.Metric> metrics = new TableView<>();
    /** Samples per open database (keyed by its session, compared by identity). */
    private final Map<Object, Deque<Sample>> history = new IdentityHashMap<>();
    private Object current;

    public StatsView() {
        getStyleClass().add("k-stats");
        summary.setId("stats-summary");
        summary.getStyleClass().add("k-section-title");
        summary.setPadding(new Insets(8, 10, 4, 10));

        tiles.setId("stats-tiles");
        tiles.setPadding(new Insets(4, 10, 8, 10));
        for (StatsRows.Tile t : StatsRows.tiles(empty(), StatsRows.Rates.NONE)) {
            Label value = new Label(t.value());
            value.getStyleClass().add("k-tile-value");
            Label caption = new Label(t.caption());
            caption.getStyleClass().add("k-tile-caption");
            Label detail = new Label(t.detail());
            detail.getStyleClass().add("k-tile-detail");
            VBox box = new VBox(2, caption, value, detail);
            box.setId("tile-" + t.id());
            box.getStyleClass().add("k-tile");
            box.setMinWidth(0);
            HBox.setHgrow(box, Priority.ALWAYS);
            box.setMaxWidth(Double.MAX_VALUE);
            tiles.getChildren().add(box);
            tileLabels.put(t.id(), new Label[]{value, detail});
        }

        writes.getData().add(series("inserts"));
        writes.getData().add(series("updates"));
        writes.getData().add(series("deletes"));
        transactions.getData().add(series("commits (all writes)"));
        transactions.getData().add(series("transactions committed"));
        transactions.getData().add(series("conflicts"));
        barChart(objects, "stats-objects");
        barChart(storage, "stats-storage");

        GridPane charts = new GridPane();
        charts.setHgap(10);
        charts.setVgap(10);
        charts.setPadding(new Insets(0, 10, 10, 10));
        charts.add(panel("writes / s", writes, "inserts", "updates", "deletes"), 0, 0);
        charts.add(panel("transactions / s", transactions, "commits (all writes)", "transactions committed", "conflicts"), 1, 0);
        charts.add(panel("objects per collection", objects), 0, 1);
        charts.add(panel("storage per collection (MiB)", storage, "segment files", "not flushed yet"), 1, 1);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(50);
            charts.getColumnConstraints().add(c);
            RowConstraints r = new RowConstraints();
            r.setPercentHeight(50);
            r.setVgrow(Priority.ALWAYS);
            charts.getRowConstraints().add(r);
        }

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

        TabPane tables = new TabPane(new Tab("Collections", collections), new Tab("Counters since open", metrics));
        tables.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tables.setId("stats-tables");
        VBox dashboard = new VBox(summary, tiles, charts);
        VBox.setVgrow(charts, Priority.ALWAYS);
        SplitPane split = new SplitPane(dashboard, tables);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.68);
        setCenter(split);
    }

    private static LineChart<Number, Number> lineChart(String id) {
        NumberAxis x = new NumberAxis();
        x.setForceZeroInRange(false);
        x.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                double v = n.doubleValue();
                return v == Math.rint(v) ? String.format(Locale.ROOT, "%.0f s", v) : String.format(Locale.ROOT, "%.1f s", v);
            }

            @Override
            public Number fromString(String s) {
                return Double.valueOf(s.replace(" s", ""));
            }
        });
        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(true);
        LineChart<Number, Number> c = new LineChart<>(x, y);
        c.setId(id);
        c.setAnimated(false);
        c.setCreateSymbols(false);
        c.setLegendVisible(false);
        c.setMinHeight(0);
        c.getStyleClass().add("k-chart");
        return c;
    }

    private static void barChart(XYChart<String, Number> c, String id) {
        c.setId(id);
        c.setLegendVisible(false);
        c.setAnimated(false);
        c.setMinHeight(0);
        c.getStyleClass().add("k-chart");
    }

    /**
     * A chart under a header with its title and legend: colored dots in series order (the chart's own legend is hidden
     * by JavaFX whenever the chart is short).
     */
    private static VBox panel(String title, XYChart<?, ?> chart, String... series) {
        Label name = new Label(title);
        name.getStyleClass().add("k-chart-title");
        HBox header = new HBox(14, name);
        header.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < series.length; i++) {
            Circle dot = new Circle(4);
            dot.getStyleClass().addAll("k-dot", "k-dot-" + i);
            Label item = new Label(series[i], dot);
            item.getStyleClass().add("k-legend-item");
            header.getChildren().add(item);
        }
        VBox box = new VBox(2, header, chart);
        box.getStyleClass().add("k-chart-panel");
        box.setId(chart.getId() + "-panel");
        VBox.setVgrow(chart, Priority.ALWAYS);
        box.setMinHeight(0);
        return box;
    }

    private static XYChart.Series<Number, Number> series(String name) {
        XYChart.Series<Number, Number> s = new XYChart.Series<>();
        s.setName(name);
        return s;
    }

    private static <T> TableColumn<T, String> column(String name, Function<T, String> value, double width) {
        TableColumn<T, String> c = new TableColumn<>(name);
        c.setCellValueFactory(d -> new SimpleStringProperty(value.apply(d.getValue())));
        c.setPrefWidth(width);
        c.setSortable(false);
        return c;
    }

    /** Statistics of a database without any data, for the initial tiles. */
    private static DatabaseStats empty() {
        Instant now = Instant.EPOCH;
        return new DatabaseStats(now, now, 0, List.of(), new DatabaseStats.Writes(0, 0, 0, 0, 0, 0, 0, 0, 0),
                new DatabaseStats.Transactions(0, 0, Map.of()), new DatabaseStats.Constraints(0, 0, 0, 0),
                new DatabaseStats.Maintenance(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    /** Statistics of the database {@code key} (a session): appended to its history and shown. */
    public void show(Object key, DatabaseStats s) {
        Deque<Sample> h = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        Sample last = h.peekLast();
        double at = last == null || !last.stats().openedAt().equals(s.openedAt()) ? 0
                : last.at() + Duration.between(last.stats().takenAt(), s.takenAt()).toNanos() / 1e9;
        if (last != null && at == 0) {
            h.clear();                                               // reopened: counters start again
        }
        h.addLast(new Sample(at, s, StatsRows.rates(last == null ? null : last.stats(), s)));
        while (h.size() > HISTORY) {
            h.removeFirst();
        }
        current = key;
        render(s, h);
    }

    /** Forgets the samples of a closed database. */
    public void forget(Object key) {
        history.remove(key);
    }

    /** Samples kept for {@code key}. */
    int samples(Object key) {
        Deque<Sample> h = history.get(key);
        return h == null ? 0 : h.size();
    }

    Object current() {
        return current;
    }

    private void render(DatabaseStats s, Deque<Sample> h) {
        Sample last = h.peekLast();
        for (StatsRows.Tile t : StatsRows.tiles(s, last.rates())) {
            Label[] l = tileLabels.get(t.id());
            l[0].setText(t.value());
            l[1].setText(t.detail());
        }
        List<List<XYChart.Data<Number, Number>>> rates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rates.add(new ArrayList<>());
        }
        boolean first = true;
        for (Sample p : h) {
            if (first) {
                first = false;                                       // no rate before the second sample
                continue;
            }
            StatsRows.Rates r = p.rates();
            double[] v = {r.inserts(), r.updates(), r.deletes(), r.commits(), r.txCommits(), r.conflicts()};
            for (int i = 0; i < 6; i++) {
                rates.get(i).add(new XYChart.Data<>(p.at(), v[i]));
            }
        }
        for (int i = 0; i < 3; i++) {
            writes.getData().get(i).getData().setAll(rates.get(i));
            transactions.getData().get(i).getData().setAll(rates.get(i + 3));
        }

        XYChart.Series<String, Number> count = new XYChart.Series<>();
        count.setName("objects");
        XYChart.Series<String, Number> onDisk = new XYChart.Series<>();
        onDisk.setName("segment files");
        XYChart.Series<String, Number> unflushed = new XYChart.Series<>();
        unflushed.setName("not flushed yet");
        for (DatabaseStats.CollectionStats c : s.collections()) {
            String name = c.name().substring(c.name().lastIndexOf('.') + 1);
            count.getData().add(new XYChart.Data<>(name, c.objects()));
            onDisk.getData().add(new XYChart.Data<>(name, c.segmentBytes() / 1048576.0));
            unflushed.getData().add(new XYChart.Data<>(name, c.memBytes() / 1048576.0));
        }
        objects.getData().setAll(List.of(count));
        storage.getData().setAll(List.of(onDisk, unflushed));

        collections.getItems().setAll(StatsRows.collections(s));
        metrics.getItems().setAll(StatsRows.metrics(s));
        summary.setText(String.format(Locale.ROOT, "%,d objects in %d collections · %,d batches committed · %d active transactions",
                s.objects(), s.collections().size(), s.writes().batches(), s.transactions().active()));
    }
}
