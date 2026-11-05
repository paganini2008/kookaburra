package com.github.kookaburra.face.view;

import com.github.kookaburra.query.QueryPlan;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A query plan drawn as a diagram (like pgAdmin's or MySQL Workbench's visual explain): one row per step in tree
 * order with an icon and a colour for how it reads data (index green, full-text magenta, vector cyan, row scan
 * orange, processing grey); after {@code EXPLAIN ANALYZE} each step shows its rows (in → out) with a bar, and its
 * time with a bar relative to the whole query, the slowest step highlighted.
 *
 * @Author: Fred Feng
 * @Date: 05/11/2026
 * @Version 1.0.0
 */
public class PlanView extends ScrollPane {

    static final double BAR = 110;

    final Label summary = new Label();
    final VBox steps = new VBox(2);
    /** Rows drawn for the last plan, in tree order (for tests). */
    final List<HBox> rows = new ArrayList<>();
    private QueryPlan plan;

    public PlanView() {
        getStyleClass().add("k-plan");
        setFitToWidth(true);
        summary.getStyleClass().add("k-plan-summary");
        summary.setWrapText(true);
        HBox legend = new HBox(14, chip("index", QueryPlan.Access.INDEX), chip("full-text", QueryPlan.Access.FULLTEXT),
                chip("vector", QueryPlan.Access.VECTOR), chip("row scan", QueryPlan.Access.SCAN),
                chip("processing", QueryPlan.Access.PROCESS));
        legend.setAlignment(Pos.CENTER_LEFT);
        legend.getStyleClass().add("k-plan-legend");
        VBox content = new VBox(10, summary, legend, steps);
        content.setPadding(new Insets(12));
        setContent(content);
        show(null);
    }

    public QueryPlan plan() {
        return plan;
    }

    private static HBox chip(String text, QueryPlan.Access access) {
        Region dot = new Region();
        dot.getStyleClass().addAll("k-plan-dot", style(access));
        HBox h = new HBox(5, dot, new Label(text));
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    static String style(QueryPlan.Access a) {
        return "k-plan-" + a.name().toLowerCase(Locale.ROOT);
    }

    static String icon(QueryPlan.Step s) {
        return switch (s.access()) {
            case INDEX -> "mdi2l-lightning-bolt";
            case FULLTEXT -> "mdi2t-text-search";
            case VECTOR -> "mdi2v-vector-link";
            case SCAN -> "mdi2t-table-search";
            case PROCESS -> switch (s.operation()) {
                case "Limit" -> "mdi2c-content-cut";
                case "Fetch objects" -> "mdi2p-package-variant";
                case "Rerank", "Diverse", "Hybrid" -> "mdi2s-shuffle-variant";
                default -> "mdi2s-sort";
            };
            case NONE -> switch (s.operation()) {
                case "Query", "OQL" -> "mdi2d-database-search";
                case "Filter" -> "mdi2f-filter-outline";
                case "AND", "OR", "NOT" -> "mdi2c-call-split";
                default -> "mdi2l-layers-outline";
            };
        };
    }

    /** Draws {@code p}; null shows how to get a plan. */
    public void show(QueryPlan p) {
        plan = p;
        steps.getChildren().clear();
        rows.clear();
        if (p == null) {
            summary.setText("Explain shows how a query runs: which index answers each condition, what is scanned, sorted"
                    + " and cut. EXPLAIN ANALYZE also runs it and shows rows and time per step.");
            return;
        }
        QueryPlan.Step root = p.root();
        long total = root.nanos();
        long maxRows = Math.max(1, maxRows(root));
        QueryPlan.Step slowest = p.analyzed() ? slowest(root, null) : null;
        if (p.analyzed()) {
            summary.setText(String.format(Locale.ROOT, "Ran the query: %,d %s in %s. Bars: rows each step produced, and its share"
                            + " of the time; the slowest step is marked.", Math.max(0, root.rows()), root.rows() == 1 ? "row" : "rows",
                    QueryPlan.time(Math.max(0, total))));
        } else {
            summary.setText("Plan only (the query did not run). Use EXPLAIN ANALYZE for rows and times per step.");
        }
        add(root, 0, total, maxRows, slowest, p.analyzed());
    }

    private void add(QueryPlan.Step s, int depth, long total, long maxRows, QueryPlan.Step slowest, boolean analyzed) {
        Region indent = new Region();
        indent.setMinWidth(depth * 22);
        indent.setPrefWidth(depth * 22);
        Region mark = new Region();
        mark.getStyleClass().addAll("k-plan-mark", style(s.access()));
        Label op = new Label(s.operation());
        op.getStyleClass().add("k-plan-op");
        op.setGraphic(Icons.of(icon(s)));
        Label detail = new Label(s.detail());
        detail.getStyleClass().add("k-plan-detail");
        detail.setTextOverrun(OverrunStyle.ELLIPSIS);
        detail.setMinWidth(60);
        Tooltip.install(detail, new Tooltip(s.operation() + "  " + s.detail()));
        HBox.setHgrow(detail, Priority.ALWAYS);
        detail.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(8, indent, mark, op, detail);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("k-plan-row", style(s.access()));
        row.setUserData(s);
        if (s.rows() >= 0) {
            String text = (s.inputRows() >= 0 ? String.format(Locale.ROOT, "%,d → ", s.inputRows()) : "")
                    + String.format(Locale.ROOT, "%,d", s.rows());
            row.getChildren().add(bar(text, (double) s.rows() / maxRows, "k-plan-rows", style(s.access())));
        }
        if (analyzed && s.nanos() >= 0) {
            row.getChildren().add(bar(QueryPlan.time(s.nanos()), total > 0 ? (double) s.nanos() / total : 0, "k-plan-time",
                    s == slowest ? "k-plan-slowest" : "k-plan-normal"));
        }
        Label flag = new Label(s == slowest ? "slowest" : "");           // same width on every row: bars stay aligned
        flag.setMinWidth(56);
        flag.setPrefWidth(56);
        if (s == slowest) {
            row.getStyleClass().add("k-plan-slow-row");
            flag.getStyleClass().add("k-plan-flag");
        }
        if (analyzed) {
            row.getChildren().add(flag);
        }
        steps.getChildren().add(row);
        rows.add(row);
        for (QueryPlan.Step c : s.children()) {
            add(c, depth + 1, total, maxRows, slowest, analyzed);
        }
    }

    /** A bar with a figure on it; {@code share} 0..1 of the full width. */
    private static StackPane bar(String text, double share, String kind, String colour) {
        Region fill = new Region();
        fill.getStyleClass().addAll("k-plan-fill", colour);
        fill.setMaxWidth(Math.max(2, Math.min(1, share) * BAR));
        fill.setPrefWidth(Math.max(2, Math.min(1, share) * BAR));
        Label figure = new Label(text);
        figure.getStyleClass().add("k-plan-figure");
        StackPane p = new StackPane(fill, figure);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        StackPane.setAlignment(figure, Pos.CENTER_LEFT);
        p.setMinWidth(BAR);
        p.setPrefWidth(BAR);
        p.setMaxWidth(BAR);
        p.getStyleClass().addAll("k-plan-bar", kind);
        return p;
    }

    private static long maxRows(QueryPlan.Step s) {
        long m = Math.max(s.rows(), s.inputRows());
        for (QueryPlan.Step c : s.children()) {
            m = Math.max(m, maxRows(c));
        }
        return m;
    }

    /** The step with the most time of its own: leaves and steps that do work, not the containers. */
    static QueryPlan.Step slowest(QueryPlan.Step s, QueryPlan.Step best) {
        boolean container = s.access() == QueryPlan.Access.NONE;
        if (!container && s.nanos() >= 0 && (best == null || s.nanos() > best.nanos())) {
            best = s;
        }
        for (QueryPlan.Step c : s.children()) {
            best = slowest(c, best);
        }
        return best;
    }
}
