package com.github.kookaburra.face.view;

import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.StudioPaths;
import com.github.kookaburra.face.model.Column;
import com.github.kookaburra.face.model.Display;
import com.github.kookaburra.face.model.Export;
import com.github.kookaburra.face.model.Page;
import com.github.kookaburra.face.model.Session;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Paged grid of one class: server-side sort (header click → ORDER BY) and filter (OQL condition over {@code x}).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class DataView extends BorderPane {

    final TableView<DynamicObject> table = new TableView<>();
    final TextField filter = new TextField();
    final Button apply = new Button("Apply");
    final Button reload = new Button();
    final MenuButton export = new MenuButton("Export");
    /** Picks the target file for an export; null = cancelled. Replaceable in tests. */
    Function<Export.Format, File> exportChooser = this::chooseExportFile;
    final Button prev = new Button();
    final Button next = new Button();
    final Label pageLabel = new Label();
    final ComboBox<Integer> pageSize = new ComboBox<>();
    private final Supplier<Session> session;
    private final Tasks tasks;
    private final Consumer<String> status;
    private final Consumer<Throwable> error;
    private String className;
    private int pageIndex;
    private String orderBy;
    private boolean descending;
    private Page page;
    private boolean rebuilding;

    public DataView(Supplier<Session> session, Tasks tasks, Consumer<String> status, Consumer<Throwable> error,
                    Consumer<DynamicObject> onOpen) {
        this.session = session;
        this.tasks = tasks;
        this.status = status;
        this.error = error;
        getStyleClass().add("k-data");
        filter.setPromptText("filter, e.g.  x.age > 30 AND x.address.city = 'Perth'");
        filter.setId("filter");
        filter.setOnAction(e -> reload(0));
        apply.setOnAction(e -> reload(0));
        HBox.setHgrow(filter, Priority.ALWAYS);
        reload.setId("data-refresh");
        reload.setGraphic(Icons.of("mdi2r-refresh"));
        reload.setTooltip(new Tooltip("Reload this page (keeps filter, sort and page)"));
        reload.setOnAction(e -> refresh());
        export.setId("export");
        export.setGraphic(Icons.of("mdi2e-export"));
        export.setTooltip(new Tooltip("Export every object matching the filter, in the current sort order"));
        for (Export.Format f : Export.Format.values()) {
            MenuItem item = new MenuItem(f == Export.Format.JSONL ? "JSONL — one object per line (large exports)" : f.name());
            item.setId("export-" + f.extension());
            item.setOnAction(e -> exportAs(f));
            export.getItems().add(item);
        }
        HBox top = new HBox(8, Icons.of("mdi2f-filter-outline"), filter, apply, reload, export);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8));
        top.getStyleClass().add("k-filter-bar");
        setTop(top);

        table.setId("data-table");
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("Select a class on the left"));
        table.setRowFactory(t -> {
            TableRow<DynamicObject> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    onOpen.accept(row.getItem());
                }
            });
            return row;
        });
        table.setSortPolicy(t -> {
            if (!rebuilding && className != null) {
                if (t.getSortOrder().isEmpty()) {
                    orderBy = null;
                } else {
                    TableColumn<DynamicObject, ?> c = t.getSortOrder().get(0);
                    orderBy = (String) c.getUserData();
                    descending = c.getSortType() == TableColumn.SortType.DESCENDING;
                }
                reload(0);
            }
            return true;
        });
        setCenter(table);

        prev.setGraphic(Icons.of("mdi2c-chevron-left"));
        next.setGraphic(Icons.of("mdi2c-chevron-right"));
        prev.setId("prev");
        next.setId("next");
        prev.setOnAction(e -> reload(pageIndex - 1));
        next.setOnAction(e -> reload(pageIndex + 1));
        pageSize.getItems().addAll(25, 50, 100, 500);
        int configured = StudioPaths.integer(StudioPaths.PAGE_SIZE);
        if (!pageSize.getItems().contains(configured)) {
            pageSize.getItems().add(configured);
            pageSize.getItems().sort(null);
        }
        pageSize.setValue(configured);
        pageSize.setOnAction(e -> reload(0));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bottom = new HBox(8, prev, pageLabel, next, gap, new Label("rows / page"), pageSize);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(6, 8, 6, 8));
        bottom.getStyleClass().add("k-pager");
        setBottom(bottom);
        updatePager();
    }

    /** Shows a class from its first page, unsorted and unfiltered. */
    public void show(String cls) {
        className = cls;
        orderBy = null;
        descending = false;
        filter.clear();
        rebuilding = true;
        table.getSortOrder().clear();
        table.getColumns().clear();
        rebuilding = false;
        reload(0);
    }

    public String className() {
        return className;
    }

    public Page page() {
        return page;
    }

    /** Writes all objects matching the current filter and sort to a file chosen by the user. */
    void exportAs(Export.Format format) {
        Session s = session.get();
        if (s == null || className == null) {
            return;
        }
        File file = exportChooser.apply(format);
        if (file == null) {
            return;
        }
        String cls = className;
        String where = filter.getText();
        String ob = orderBy;
        boolean desc = descending;
        status.accept("exporting " + simple(cls) + " …");
        tasks.run(() -> Export.write(s, cls, where, ob, desc, format, file.toPath()),
                n -> status.accept("exported " + n + " objects → " + file.getName()), error);
    }

    private File chooseExportFile(Export.Format format) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export " + simple(className) + " as " + format.name());
        chooser.setInitialFileName(simple(className) + "." + format.extension());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(format.name(), "*." + format.extension()));
        return chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** Reloads the current page (after edits). */
    public void refresh() {
        if (className != null) {
            reload(pageIndex);
        }
    }

    void reload(int index) {
        Session s = session.get();
        if (s == null || className == null) {
            return;
        }
        int size = pageSize.getValue();
        String where = filter.getText();
        String cls = className;
        String ob = orderBy;
        boolean desc = descending;
        tasks.run(() -> s.page(cls, where, ob, desc, Math.max(0, index), size), p -> {
            if (p.pageIndex() > 0 && p.pageIndex() >= p.pageCount()) {
                reload(p.pageCount() - 1);                   // rows on the last page were deleted
                return;
            }
            page = p;
            pageIndex = p.pageIndex();
            if (table.getColumns().isEmpty()) {
                buildColumns(p.columns(), s);
            }
            table.getItems().setAll(p.rows());
            updatePager();
            status.accept(p.total() + " objects · page " + (p.pageIndex() + 1) + "/" + p.pageCount() + " · " + p.millis() + " ms");
        }, error);
    }

    private void buildColumns(List<Column> cols, Session s) {
        rebuilding = true;
        table.getColumns().add(RowNumbers.column(() -> page == null ? 0 : (long) page.pageIndex() * page.pageSize()));
        for (Column c : cols) {
            TableColumn<DynamicObject, String> tc = new TableColumn<>(c.path());
            tc.setUserData(c.path());
            tc.setSortable(c.sortable());
            tc.setCellValueFactory(d -> new SimpleStringProperty(Display.text(s.cell(d.getValue(), c))));
            tc.getStyleClass().add(c.system() ? "k-system-col" : "k-col");
            tc.setPrefWidth(c.system() ? 140 : 130);
            table.getColumns().add(tc);
        }
        rebuilding = false;
    }

    private void updatePager() {
        int count = page == null ? 1 : page.pageCount();
        pageLabel.setText((page == null ? 0 : pageIndex + 1) + " / " + count);
        prev.setDisable(page == null || pageIndex <= 0);
        next.setDisable(page == null || pageIndex >= count - 1);
    }

    public List<DynamicObject> selected() {
        return List.copyOf(table.getSelectionModel().getSelectedItems());
    }
}
