package com.github.kookaburra.face.view;

import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.StudioPaths;
import com.github.kookaburra.face.model.ClassInfo;
import com.github.kookaburra.face.model.SegmentInfo;
import com.github.kookaburra.face.model.Session;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Studio main window content (design §17.1): toolbar, collections tree, Data / Query / Schema tabs and status bar.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class MainView extends BorderPane {

    final Tasks tasks;
    final TreeView<ClassInfo> tree = new TreeView<>();
    final DataView data;
    final QueryView query;
    final SchemaView schema = new SchemaView();
    final TabPane tabs = new TabPane();
    final Label status = new Label("No database open");
    final Label seq = new Label();
    final ProgressIndicator busy = new ProgressIndicator();
    final Button open = button("open", "mdi2f-folder-open-outline", "Open database directory…");
    final Button demo = button("demo", "mdi2d-database-eye-outline", "Open the demo database (in memory)");
    final Button refresh = button("refresh", "mdi2r-refresh", "Refresh");
    final Button add = button("new", "mdi2p-plus", "New object");
    final Button edit = button("edit", "mdi2p-pencil-outline", "Edit selected object");
    final Button delete = button("delete", "mdi2d-delete-outline", "Delete selected objects");
    final Button run = button("run-toolbar", "mdi2p-play", "Run query (Ctrl+Enter)");
    final Button about = button("about", "mdi2i-information-outline", "About Kookaburra Studio");
    /** Opens a URL in the system browser (set by the application; no-op in tests). */
    Consumer<String> browser = url -> { };
    final MenuButton themes = new MenuButton();
    private Theme theme = Theme.NEON;
    final VBox welcome;
    private final SplitPane workspace;
    private Session session;
    /** Opens an editor and returns the edited object or empty (replaceable in tests). */
    Function<ObjectEditor, Optional<DynamicObject>> editorDialog = this::showEditor;
    /** Default data directory (next to the Studio jar unless configured). */
    private Path dataDir = StudioPaths.dataDir(List.of());
    final Label dataLabel = new Label();
    /** Chooses a database directory (replaceable in tests). */
    Supplier<File> directoryChooser = () -> {
        DirectoryChooser c = new DirectoryChooser();
        c.setTitle("Open Kookaburra database");
        File start = Files.isDirectory(dataDir) ? dataDir.toFile() : dataDir.getParent() == null ? null : dataDir.getParent().toFile();
        if (start != null && start.isDirectory()) {
            c.setInitialDirectory(start);
        }
        return c.showDialog(window());
    };

    public MainView() {
        getStyleClass().add("k-main");
        tasks = new Tasks(b -> busy.setVisible(b));
        data = new DataView(() -> session, tasks, this::status, this::error, this::editObject);
        query = new QueryView(() -> session, tasks, this::status, this::refresh);

        open.setOnAction(e -> {
            File dir = directoryChooser.get();
            if (dir != null && (!Session.isForeignDirectory(dir.toPath()) || Dialogs.confirm(window(), "Create database",
                    dir + " is not empty and is not a Kookaburra database.\nCreate a new database in this directory?"))) {
                tasks.run(() -> Session.open(dir.toPath()), this::open, this::error);
            }
        });
        demo.setOnAction(e -> tasks.run(Session::demo, this::open, this::error));
        refresh.setOnAction(e -> refresh());
        add.setOnAction(e -> newObject());
        edit.setOnAction(e -> {
            List<DynamicObject> sel = data.selected();
            if (!sel.isEmpty()) {
                editObject(sel.get(0));
            }
        });
        delete.setOnAction(e -> deleteSelected());
        run.setOnAction(e -> {
            tabs.getSelectionModel().select(1);
            query.runQuery();
        });
        run.getStyleClass().add("accent");

        busy.setVisible(false);
        busy.setPrefSize(18, 18);
        ImageView logo = new ImageView(new Image(getClass().getResourceAsStream("/com/github/kookaburra/face/logo_64.png")));
        logo.setFitHeight(26);
        logo.setPreserveRatio(true);
        Label brand = new Label("Kookaburra Studio");
        brand.getStyleClass().add("k-brand");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        themes.setId("themes");
        themes.setGraphic(Icons.of("mdi2p-palette-outline"));
        themes.setTooltip(new Tooltip("Theme"));
        themes.getStyleClass().add("k-tool");
        ToggleGroup group = new ToggleGroup();
        for (Theme t : Theme.values()) {
            RadioMenuItem item = new RadioMenuItem(t.title());
            item.setId("theme-" + t.name());
            item.setToggleGroup(group);
            item.setUserData(t);
            item.setOnAction(e -> {
                applyTheme(getScene(), t);
                t.save();
            });
            themes.getItems().add(item);
        }
        about.setOnAction(e -> About.show(window(), browser));
        ToolBar toolbar = new ToolBar(logo, brand, new Region(), open, demo, refresh, add, edit, delete, run, gap, busy, themes, about);
        toolbar.getStyleClass().add("k-toolbar");
        setTop(toolbar);

        tree.setId("classes");
        tree.setShowRoot(false);
        tree.setCellFactory(t -> new TreeCell<>() {
            @Override
            protected void updateItem(ClassInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.simpleName() + "  " + item.count());
                    setGraphic(Icons.of(item.children().isEmpty() ? "mdi2t-table" : "mdi2f-file-tree"));
                }
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && b.getValue() != null) {
                if (b.getValue().name().equals(data.className())) {
                    data.refresh();       // same class re-selected after a refresh: keep filter, sort and page
                    return;
                }
                tabs.getSelectionModel().select(0);
                data.show(b.getValue().name());
                Session s = session;
                String name = b.getValue().name();
                tasks.run(() -> s.meta(name), schema.metadata::show, this::error);
            }
        });
        BorderPane treePane = new BorderPane(tree);
        Label collections = new Label("COLLECTIONS");
        collections.getStyleClass().add("k-section-title");
        treePane.setTop(collections);
        treePane.getStyleClass().add("k-sidebar");

        tabs.getTabs().addAll(tab("Data", "mdi2t-table-large", data), tab("Query", "mdi2c-console", query),
                tab("Schema", "mdi2f-file-tree-outline", schema));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        workspace = new SplitPane(treePane, tabs);
        workspace.setDividerPositions(0.22);

        welcome = welcome();
        setDataDir(dataDir);
        setCenter(welcome);

        status.setId("status");
        seq.setId("seq");
        Region gap2 = new Region();
        HBox.setHgrow(gap2, Priority.ALWAYS);
        HBox bar = new HBox(10, Icons.of("mdi2c-circle-medium"), status, gap2, seq);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 10, 4, 10));
        bar.getStyleClass().add("k-status");
        setBottom(bar);
        updateButtons();
    }

    private static Tab tab(String name, String icon, Node content) {
        Tab t = new Tab(name, content);
        t.setGraphic(Icons.of(icon));
        return t;
    }

    private static Button button(String id, String icon, String tip) {
        Button b = new Button();
        b.setId(id);
        b.setGraphic(Icons.of(icon));
        b.setTooltip(new Tooltip(tip));
        b.getStyleClass().add("k-tool");
        return b;
    }

    private VBox welcome() {
        ImageView logo = new ImageView(new Image(getClass().getResourceAsStream("/com/github/kookaburra/face/logo.png")));
        logo.setFitWidth(180);
        logo.setPreserveRatio(true);
        logo.getStyleClass().add("k-hero-logo");
        Label title = new Label("Kookaburra Studio");
        title.getStyleClass().add("k-hero-title");
        Label sub = new Label("Browse, query and edit any Kookaburra database — no entity classes needed.");
        sub.getStyleClass().add("k-muted");
        Button o = new Button("Open database…", Icons.of("mdi2f-folder-open-outline"));
        o.setOnAction(e -> open.fire());
        o.getStyleClass().add("accent");
        Button d = new Button("Open demo", Icons.of("mdi2d-database-eye-outline"));
        d.setId("welcome-demo");
        d.setOnAction(e -> demo.fire());
        Button data = new Button("Open data directory", Icons.of("mdi2d-database-outline"));
        data.setId("welcome-data");
        data.setOnAction(e -> {
            Path dir = dataDir;
            tasks.run(() -> Session.open(dir), this::open, this::error);
        });
        dataLabel.setId("welcome-data-dir");
        dataLabel.getStyleClass().add("k-muted");
        HBox buttons = new HBox(12, o, data, d);
        buttons.setAlignment(Pos.CENTER);
        VBox box = new VBox(16, logo, title, sub, buttons, dataLabel);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("k-welcome");
        return box;
    }

    // ---------------------------------------------------------------- actions

    /** Applies a theme to the scene and marks it in the theme menu. */
    public void applyTheme(Scene scene, Theme t) {
        theme = t;
        t.apply(scene);
        for (MenuItem i : themes.getItems()) {
            ((RadioMenuItem) i).setSelected(i.getUserData() == t);
        }
    }

    public Theme theme() {
        return theme;
    }

    /** Sets the default data directory (welcome page and directory chooser). */
    public void setDataDir(Path dir) {
        dataDir = dir;
        dataLabel.setText("data directory: " + dir);
    }

    public Path dataDir() {
        return dataDir;
    }

    /** Sets how links are opened (e.g. {@code HostServices::showDocument}). */
    public void setBrowser(Consumer<String> browser) {
        this.browser = browser;
    }

    public Session session() {
        return session;
    }

    /** Switches to a session (closing the previous one). */
    public void open(Session s) {
        if (session != null) {
            session.close();
        }
        session = s;
        setCenter(workspace);
        if (getScene() != null && getScene().getWindow() instanceof Stage st) {
            st.setTitle("Kookaburra Studio — " + s.title());
        }
        updateButtons();
        refresh();
    }

    public void refresh() {
        Session s = session;
        if (s == null) {
            return;
        }
        String selected = data.className();
        tasks.run(() -> new Object[]{s.classes(), s.schemaText(), s.seq(), s.segments()}, r -> {
            @SuppressWarnings("unchecked")
            List<ClassInfo> classes = (List<ClassInfo>) r[0];
            TreeItem<ClassInfo> root = new TreeItem<>();
            TreeItem<ClassInfo> toSelect = null;
            for (ClassInfo c : classes) {
                TreeItem<ClassInfo> item = item(c);
                root.getChildren().add(item);
                TreeItem<ClassInfo> found = find(item, selected);
                toSelect = toSelect != null ? toSelect : found;
            }
            tree.setRoot(root);
            @SuppressWarnings("unchecked") List<SegmentInfo> segs = (List<SegmentInfo>) r[3];
            schema.show(segs, (String) r[1]);
            seq.setText("snapshot seq " + r[2]);
            if (toSelect != null) {
                tree.getSelectionModel().select(toSelect);
            }
        }, this::error);
    }

    private static TreeItem<ClassInfo> item(ClassInfo c) {
        TreeItem<ClassInfo> i = new TreeItem<>(c);
        c.children().forEach(x -> i.getChildren().add(item(x)));
        i.setExpanded(true);
        return i;
    }

    private static TreeItem<ClassInfo> find(TreeItem<ClassInfo> item, String name) {
        if (name == null) {
            return null;
        }
        if (item.getValue().name().equals(name)) {
            return item;
        }
        for (TreeItem<ClassInfo> c : item.getChildren()) {
            TreeItem<ClassInfo> f = find(c, name);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    void newObject() {
        if (session == null || data.className() == null) {
            return;
        }
        edit(session.newObject(data.className()));
    }

    void editObject(DynamicObject o) {
        if (session != null) {
            edit(o);
        }
    }

    private void edit(DynamicObject o) {
        ObjectEditor editor = new ObjectEditor(session.classDef(o.className()), o);
        editorDialog.apply(editor).ifPresent(this::save);
    }

    /** Saves an edited object; on a version conflict asks whether to reload or overwrite. */
    void save(DynamicObject o) {
        Session s = session;
        tasks.run(() -> s.save(o), saved -> {
            status("saved " + saved.className().substring(saved.className().lastIndexOf('.') + 1) + " #" + saved.oid());
            refresh();
        }, t -> {
            if (t instanceof OptimisticLockException) {
                switch (Dialogs.conflict(window(), t.getMessage())) {
                    case RELOAD -> tasks.run(() -> s.reload(o), fresh -> {
                        if (fresh != null) {
                            edit(fresh);
                        }
                    }, this::error);
                    case OVERWRITE -> tasks.run(() -> s.reload(o), fresh -> {
                        if (fresh != null) {
                            o.assign(fresh.oid(), fresh.version());
                            save(o);
                        }
                    }, this::error);
                    default -> {
                    }
                }
            } else {
                error(t);
            }
        });
    }

    void deleteSelected() {
        List<DynamicObject> sel = data.selected();
        if (session == null) {
            return;
        }
        if (sel.isEmpty()) {
            status("select one or more rows to delete");
            return;
        }
        if (Dialogs.confirm(window(), "Delete", "Delete " + sel.size() + " object" + (sel.size() == 1 ? "" : "s") + "?")) {
            Session s = session;
            tasks.run(() -> {
                s.delete(sel);
                return sel.size();
            }, n -> {
                status("deleted " + n);
                refresh();
            }, this::error);
        }
    }

    private Optional<DynamicObject> showEditor(ObjectEditor editor) {
        Dialog<DynamicObject> d = new Dialog<>();
        d.initOwner(window());
        d.setTitle("Edit object");
        d.getDialogPane().setContent(editor);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().getStyleClass().add("k-dialog");
        d.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, e -> {
            try {
                session.validate(editor.result());           // type errors stay in the editor, input is kept
            } catch (RuntimeException ex) {
                editor.error.setText(ex.getMessage());
                e.consume();
            }
        });
        d.setResultConverter(b -> b == ButtonType.OK ? editor.result() : null);
        return d.showAndWait();
    }

    private void updateButtons() {
        boolean none = session == null;
        refresh.setDisable(none);
        add.setDisable(none);
        edit.setDisable(none);
        delete.setDisable(none);
        run.setDisable(none);
    }

    void status(String text) {
        status.setText(text);
    }

    void error(Throwable t) {
        status("error: " + t.getMessage());
        Dialogs.error(window(), "Error", String.valueOf(t.getMessage()));
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    public void close() {
        if (session != null) {
            session.close();
        }
        tasks.close();
    }
}
