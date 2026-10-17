package com.github.kookaburra.face.view;

import com.github.kookaburra.face.StudioPaths;
import com.github.kookaburra.face.model.Completion;
import com.github.kookaburra.face.model.Display;
import com.github.kookaburra.face.model.Export;
import com.github.kookaburra.face.model.Highlight;
import com.github.kookaburra.face.model.QueryOutcome;
import com.github.kookaburra.face.model.Session;
import com.github.kookaburra.oql.OqlException;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * OQL console: highlighted editor (RichTextFX), Ctrl+Enter to run, Ctrl+Space to complete, results grid,
 * explain view and history. Statements separated by {@code ;} run as a script; {@code BEGIN} / {@code COMMIT} /
 * {@code ROLLBACK} control the database's transaction (the same one as the toolbar buttons).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class QueryView extends BorderPane {

    static final KeyCombination COMPLETE = new KeyCodeCombination(KeyCode.SPACE, KeyCombination.CONTROL_DOWN);

    final CodeArea editor = new CodeArea();
    final Button run = new Button("Run");
    final Button explain = new Button("Explain");
    final MenuButton export = new MenuButton("Export");
    /** Picks the target file of a result export; null = cancelled. Replaceable in tests. */
    Function<Export.Format, File> exportChooser = this::chooseExportFile;
    final Label message = new Label();
    final TableView<List<Object>> results = new TableView<>();
    final TextArea plan = new TextArea();
    final ListView<String> history = new ListView<>();
    final TabPane output = new TabPane();
    final ContextMenu completions = new ContextMenu();
    final Button prev = new Button();
    final Button next = new Button();
    final Label pageLabel = new Label();
    final ComboBox<Integer> pageSize = new ComboBox<>();
    /** Statement and offset of the result currently shown (paging re-runs it). */
    private String current;
    private long offset;
    private final Supplier<Session> session;
    private final Tasks tasks;
    private final Consumer<String> status;
    private final Runnable changed;

    public QueryView(Supplier<Session> session, Tasks tasks, Consumer<String> status, Runnable changed) {
        this.session = session;
        this.tasks = tasks;
        this.status = status;
        this.changed = changed;
        getStyleClass().add("k-query");

        editor.setId("oql-editor");
        editor.getStyleClass().add("k-code");
        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.textProperty().addListener((o, a, b) -> editor.setStyleSpans(0, Highlight.oql(b)));
        editor.replaceText("SELECT x FROM Customer x WHERE x.age > 30 ORDER BY x.age DESC LIMIT 20");
        editor.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && (e.isControlDown() || e.isMetaDown())) {
                runQuery();
                e.consume();
            } else if (COMPLETE.match(e)) {
                complete();
                e.consume();
            }
        });

        run.setId("run");
        run.setGraphic(Icons.of("mdi2p-play"));
        run.getStyleClass().add("accent");
        run.setOnAction(e -> runQuery());
        explain.setId("explain");
        explain.setGraphic(Icons.of("mdi2l-lightbulb-on-outline"));
        explain.setOnAction(e -> explainQuery());
        message.setId("message");
        message.getStyleClass().add("k-message");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        export.setId("console-export");
        export.setGraphic(Icons.of("mdi2e-export"));
        export.setTooltip(new Tooltip("Export the whole result of the statement in the editor (all rows, not only this page)"));
        for (Export.Format f : Export.Format.values()) {
            MenuItem item = new MenuItem(f == Export.Format.JSONL ? "JSONL: one row per line (large results)" : f.name());
            item.setId("console-export-" + f.extension());
            item.setOnAction(e -> exportAs(f));
            export.getItems().add(item);
        }
        HBox bar = new HBox(8, run, explain, export, gap, message);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        bar.getStyleClass().add("k-console-bar");

        results.setId("results");
        results.setPlaceholder(new Label("Run a query (Ctrl+Enter)"));
        plan.setId("plan");
        plan.setEditable(false);
        plan.getStyleClass().add("k-code-text");
        prev.setId("query-prev");
        next.setId("query-next");
        prev.setGraphic(Icons.of("mdi2c-chevron-left"));
        next.setGraphic(Icons.of("mdi2c-chevron-right"));
        prev.setOnAction(e -> page(Math.max(0, offset - pageSize.getValue())));
        next.setOnAction(e -> page(offset + pageSize.getValue()));
        pageSize.getItems().addAll(100, 500, 1000);
        int configured = StudioPaths.integer(StudioPaths.CONSOLE_PAGE_SIZE);
        if (!pageSize.getItems().contains(configured)) {
            pageSize.getItems().add(configured);
            pageSize.getItems().sort(null);
        }
        pageSize.setValue(configured);
        pageSize.setOnAction(e -> page(0));
        pageLabel.setId("query-page");
        Region pagerGap = new Region();
        HBox.setHgrow(pagerGap, Priority.ALWAYS);
        HBox pager = new HBox(8, prev, pageLabel, next, pagerGap, new Label("rows / page"), pageSize);
        pager.setAlignment(Pos.CENTER_LEFT);
        pager.setPadding(new Insets(6, 8, 6, 8));
        pager.getStyleClass().add("k-pager");
        BorderPane resultPane = new BorderPane(results);
        resultPane.setBottom(pager);
        updatePager(null);
        Tab resultTab = new Tab("Result", resultPane);
        Tab planTab = new Tab("Plan", plan);
        output.getTabs().addAll(resultTab, planTab);
        output.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        history.setId("history");
        history.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && history.getSelectionModel().getSelectedItem() != null) {
                editor.replaceText(history.getSelectionModel().getSelectedItem());
            }
        });
        BorderPane historyPane = new BorderPane(history);
        Label h = new Label("History");
        h.getStyleClass().add("k-section-title");
        historyPane.setTop(h);

        BorderPane top = new BorderPane(new VirtualizedScrollPane<>(editor));
        top.setBottom(bar);
        SplitPane vertical = new SplitPane(top, output);
        vertical.setOrientation(Orientation.VERTICAL);
        vertical.setDividerPositions(0.38);
        SplitPane horizontal = new SplitPane(vertical, historyPane);
        horizontal.setDividerPositions(0.8);
        setCenter(horizontal);
    }

    public void runQuery() {
        Session s = session.get();
        String text = editor.getText().strip();
        if (s == null || text.isEmpty()) {
            return;
        }
        if (!history.getItems().contains(text)) {
            history.getItems().add(0, text);
        }
        List<String> statements = Session.statements(text);
        if (statements.isEmpty()) {
            return;
        }
        current = statements.get(statements.size() - 1);    // paging re-runs only the last statement
        if (statements.size() == 1) {
            page(0);
            return;
        }
        int size = pageSize.getValue();
        message.getStyleClass().remove("k-error");
        tasks.run(() -> s.script(statements, size), r -> {
            changed.run();                                   // earlier statements may have changed data or the transaction
            show(r);
            message.setText(statements.size() + " statements · last: " + r.summary());
        }, t -> {
            changed.run();
            fail(t);
        });
    }

    /** Runs the current statement for the page starting at {@code from}. */
    void page(long from) {
        Session s = session.get();
        if (s == null || current == null) {
            return;
        }
        String text = current;
        int size = pageSize.getValue();
        message.getStyleClass().remove("k-error");
        tasks.run(() -> s.run(text, from, size), this::show, this::fail);
    }

    private void updatePager(QueryOutcome r) {
        boolean paged = r != null && r.paged();
        offset = paged ? r.offset() : 0;
        prev.setDisable(!paged || offset == 0);
        next.setDisable(!paged || !r.hasNext());
        pageLabel.setText(r == null || !r.isTable() ? "" : paged ? (r.rows().isEmpty() ? "–" : (offset + 1) + "–" + (offset + r.rows().size()))
                : r.rows().size() + " rows");
    }

    public void explainQuery() {
        Session s = session.get();
        String text = editor.getText().strip();
        if (s == null || text.isEmpty()) {
            return;
        }
        tasks.run(() -> s.explain(text), p -> {
            plan.setText(p);
            output.getSelectionModel().select(1);
            message.setText("plan");
        }, this::fail);
    }

    void show(QueryOutcome r) {
        if (r.affected() > 0 || r.note() != null) {
            changed.run();                                   // UPDATE / DELETE / COMMIT …: counts, grid and transaction state change
        }
        message.setText(r.summary());
        status.accept(r.summary());
        if (r.plan() != null) {
            plan.setText(r.plan());
            output.getSelectionModel().select(1);
            return;
        }
        results.getColumns().clear();
        results.getItems().clear();
        if (r.isTable()) {
            long offset = r.offset();
            results.getColumns().add(RowNumbers.column(() -> offset));
            for (int i = 0; i < r.columns().size(); i++) {
                int col = i;
                TableColumn<List<Object>, String> c = new TableColumn<>(r.columns().get(i));
                c.setCellValueFactory(d -> new SimpleStringProperty(Display.text(d.getValue().get(col))));
                c.setPrefWidth(160);
                results.getColumns().add(c);
            }
            results.getItems().setAll(r.rows());
        }
        updatePager(r);
        output.getSelectionModel().select(0);
    }

    /** Writes the full result of the SELECT in the editor to a file chosen by the user. */
    void exportAs(Export.Format format) {
        Session s = session.get();
        String text = editor.getText().strip();
        if (s == null || text.isEmpty()) {
            return;
        }
        File file = exportChooser.apply(format);
        if (file == null) {
            return;
        }
        message.getStyleClass().remove("k-error");
        message.setText("exporting …");
        tasks.run(() -> Export.writeQuery(s, text, format, file.toPath()), n -> {
            message.setText("exported " + n + " rows → " + file.getName());
            status.accept(message.getText());
        }, this::fail);
    }

    private File chooseExportFile(Export.Format format) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export query result as " + format.name());
        chooser.setInitialFileName("result." + format.extension());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(format.name(), "*." + format.extension()));
        return chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
    }

    void fail(Throwable t) {
        message.setText(t.getMessage());
        if (!message.getStyleClass().contains("k-error")) {
            message.getStyleClass().add("k-error");
        }
        status.accept("error");
        if (t instanceof OqlException o && o.line() > 0) {
            int line = Math.min(o.line() - 1, editor.getParagraphs().size() - 1);
            editor.moveTo(line, Math.min(Math.max(0, o.column()), editor.getParagraphLength(line)));
        }
    }

    /** Shows completions for the word at the caret. */
    public void complete() {
        Session s = session.get();
        if (s == null) {
            return;
        }
        int caret = editor.getCaretPosition();
        String prefix = Completion.prefix(editor.getText(), caret);
        List<String> items = Completion.suggest(editor.getText(), caret, s.schema());
        completions.getItems().clear();
        for (String item : items.subList(0, Math.min(items.size(), 30))) {
            MenuItem mi = new MenuItem(item);
            mi.setOnAction(e -> editor.replaceText(caret - prefix.length(), caret, item));
            completions.getItems().add(mi);
        }
        if (!completions.getItems().isEmpty()) {
            completions.show(editor, Side.BOTTOM, 0, 0);
        }
    }

    public void setText(String text) {
        editor.replaceText(text);
    }
}
