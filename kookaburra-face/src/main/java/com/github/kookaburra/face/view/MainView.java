package com.github.kookaburra.face.view;

import com.github.kookaburra.ConflictException;
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
import javafx.scene.control.Separator;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    final TreeView<NavNode> tree = new TreeView<>();
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
    final Button closeDb = button("close-db", "mdi2d-database-remove-outline", "Close the current database");
    final Button txBegin = button("tx-begin", "mdi2s-source-branch", "Begin transaction: changes stay private until Commit");
    final Button txCommit = button("tx-commit", "mdi2c-check-circle-outline", "Commit the transaction");
    final Button txRollback = button("tx-rollback", "mdi2u-undo-variant", "Roll back the transaction (discard its changes)");
    final Label txStatus = new Label();
    /** Status text of the open transaction as of the last refresh, or null (autocommit). */
    private String txLabel;
    /** Opens a URL in the system browser (set by the application; no-op in tests). */
    Consumer<String> browser = url -> { };
    final MenuButton themes = new MenuButton();
    private Theme theme = Theme.NEON;
    final VBox welcome;
    private final SplitPane workspace;
    /** Every open database, in opening order; {@code session} is the one the tabs and tools act on. */
    private final List<Session> sessions = new ArrayList<>();
    /** Navigator labels and transaction states per database (as of the last refresh). */
    private final Map<Session, String> labels = new HashMap<>();
    private final Map<Session, String> txLabels = new HashMap<>();
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
        data = new DataView(() -> session, tasks, this::status, this::error, this::editObject, this::openRef);
        query = new QueryView(() -> session, tasks, this::status, this::refresh);

        open.setOnAction(e -> {
            File dir = directoryChooser.get();
            if (dir != null && !switchToOpen(dir.toPath()) && (!Session.isForeignDirectory(dir.toPath()) || Dialogs.confirm(window(),
                    "Create database", dir + " is not empty and is not a Kookaburra database.\nCreate a new database in this directory?"))) {
                tasks.run(() -> Session.open(dir.toPath()), this::open, this::error);
            }
        });
        demo.setOnAction(e -> tasks.run(Session::demo, this::open, this::error));
        closeDb.setOnAction(e -> closeCurrent());
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
        txBegin.setText("Begin");
        txCommit.setText("Commit");
        txRollback.setText("Rollback");
        txCommit.getStyleClass().add("success");
        txRollback.getStyleClass().add("danger");
        txBegin.setOnAction(e -> beginTransaction());
        txCommit.setOnAction(e -> commitTransaction());
        txRollback.setOnAction(e -> rollbackTransaction());
        ToolBar toolbar = new ToolBar(logo, brand, new Region(), open, demo, closeDb, refresh, add, edit, delete, run, new Separator(),
                txBegin, txCommit, txRollback, gap, busy, themes, about);
        toolbar.getStyleClass().add("k-toolbar");
        setTop(toolbar);

        tree.setId("classes");
        tree.setShowRoot(false);
        tree.setCellFactory(t -> new TreeCell<>() {
            @Override
            protected void updateItem(NavNode item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("k-nav-db", "k-nav-db-current", "k-nav-tx");
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setTooltip(null);
                } else if (item.isDatabase()) {
                    setText(item.text());
                    setGraphic(Icons.of(item.session().dir() == null ? "mdi2d-database-eye-outline" : "mdi2d-database"));
                    setTooltip(new Tooltip(item.session().title() + (item.txLabel() == null ? "" : "\n" + item.txLabel())));
                    getStyleClass().add(item.session() == session ? "k-nav-db-current" : "k-nav-db");
                    if (item.txLabel() != null) {
                        getStyleClass().add("k-nav-tx");
                    }
                } else {
                    setText(item.text());
                    setGraphic(Icons.of(item.cls().children().isEmpty() ? "mdi2t-table" : "mdi2f-file-tree"));
                    setTooltip(null);
                }
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b == null || b.getValue() == null) {
                return;
            }
            NavNode n = b.getValue();
            boolean switched = n.session() != session;
            if (switched) {
                use(n.session());
            }
            if (n.isDatabase()) {
                if (switched) {
                    data.clear();
                    refresh();
                }
                return;
            }
            String name = n.cls().name();
            if (!switched && name.equals(data.className())) {
                data.refresh();           // same class re-selected after a refresh: keep filter, sort and page
                return;
            }
            tabs.getSelectionModel().select(0);
            data.show(name);
            Session s = session;
            tasks.run(() -> s.meta(name), schema.metadata::show, this::error);
            if (switched) {
                refresh();
            }
        });
        BorderPane treePane = new BorderPane(tree);
        Label collections = new Label("DATABASES");
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
        txStatus.setId("tx-status");
        txStatus.getStyleClass().add("k-tx-status");
        txStatus.setGraphic(Icons.of("mdi2s-source-branch"));
        txStatus.setVisible(false);
        txStatus.managedProperty().bind(txStatus.visibleProperty());
        HBox bar = new HBox(10, Icons.of("mdi2c-circle-medium"), status, gap2, txStatus, seq);
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

    /** Opens one more database next to the open ones and makes it current. */
    public void open(Session s) {
        sessions.add(s);
        labels.put(s, uniqueLabel(s));
        use(s);
        data.clear();
        setCenter(workspace);
        refresh();
    }

    /** All open databases, in opening order. */
    public List<Session> sessions() {
        return List.copyOf(sessions);
    }

    /** Makes an open database the one the tabs and tools act on. */
    void use(Session s) {
        session = s;
        if (getScene() != null && getScene().getWindow() instanceof Stage st) {
            st.setTitle("Kookaburra Studio — " + labels.getOrDefault(s, s.title())
                    + (sessions.size() > 1 ? "  (" + sessions.size() + " databases)" : ""));
        }
        showTransaction(txLabels.get(s));
    }

    /** If {@code dir} is already open, makes it current and returns true. */
    boolean switchToOpen(Path dir) {
        Path wanted = dir.toAbsolutePath().normalize();
        for (Session s : sessions) {
            if (wanted.equals(s.dir())) {
                use(s);
                status(labels.get(s) + " is already open");
                refresh();
                return true;
            }
        }
        return false;
    }

    private String uniqueLabel(Session s) {
        String base = s.dir() == null ? "demo" : s.dir().getFileName() == null ? s.dir().toString() : s.dir().getFileName().toString();
        String label = base;
        for (int i = 2; labels.containsValue(label); i++) {
            label = base + " (" + i + ")";
        }
        return label;
    }

    /** Closes the current database (asks first if its transaction has uncommitted changes). */
    void closeCurrent() {
        Session s = session;
        if (s == null || !confirmDiscard(s)) {
            return;
        }
        sessions.remove(s);
        labels.remove(s);
        txLabels.remove(s);
        tasks.run(() -> {
            s.close();
            return true;
        }, ok -> { }, this::error);
        data.clear();
        if (sessions.isEmpty()) {
            session = null;
            tree.setRoot(null);
            setCenter(welcome);
            status("No database open");
            showTransaction(null);
            if (getScene() != null && getScene().getWindow() instanceof Stage st) {
                st.setTitle("Kookaburra Studio");
            }
        } else {
            use(sessions.get(sessions.size() - 1));
            refresh();
        }
    }

    /** Reloads the navigator (every open database) and the schema of the current one. */
    public void refresh() {
        Session current = session;
        if (current == null) {
            return;
        }
        List<Session> all = List.copyOf(sessions);
        String selected = data.className();
        tasks.run(() -> {
            Map<Session, Object[]> trees = new LinkedHashMap<>();
            for (Session s : all) {
                trees.put(s, new Object[]{s.classes(), s.txLabel()});
            }
            return new Object[]{trees, current.schemaText(), current.seq(), current.segments()};
        }, r -> {
            @SuppressWarnings("unchecked")
            Map<Session, Object[]> trees = (Map<Session, Object[]>) r[0];
            TreeItem<NavNode> root = new TreeItem<>();
            TreeItem<NavNode> toSelect = null;
            for (Map.Entry<Session, Object[]> e : trees.entrySet()) {
                Session s = e.getKey();
                if (!sessions.contains(s)) {
                    continue;                                  // closed meanwhile
                }
                String tx = (String) e.getValue()[1];
                txLabels.put(s, tx);
                TreeItem<NavNode> db = new TreeItem<>(new NavNode(s, labels.get(s), null, tx));
                db.setExpanded(true);
                @SuppressWarnings("unchecked")
                List<ClassInfo> classes = (List<ClassInfo>) e.getValue()[0];
                for (ClassInfo c : classes) {
                    TreeItem<NavNode> item = item(s, c);
                    db.getChildren().add(item);
                    if (s == current && toSelect == null) {
                        toSelect = find(item, selected);
                    }
                }
                root.getChildren().add(db);
                if (s == current && toSelect == null && selected == null) {
                    toSelect = db;
                }
            }
            tree.setRoot(root);
            if (current != session) {
                return;                                        // another database became current meanwhile: its refresh selects
            }
            showTransaction(txLabels.get(current));
            @SuppressWarnings("unchecked") List<SegmentInfo> segs = (List<SegmentInfo>) r[3];
            schema.show(segs, (String) r[1]);
            seq.setText("snapshot seq " + r[2]);
            if (toSelect != null) {
                tree.getSelectionModel().select(toSelect);
            }
        }, this::error);
    }

    private static TreeItem<NavNode> item(Session s, ClassInfo c) {
        TreeItem<NavNode> i = new TreeItem<>(new NavNode(s, null, c, null));
        c.children().forEach(x -> i.getChildren().add(item(s, x)));
        i.setExpanded(true);
        return i;
    }

    private static TreeItem<NavNode> find(TreeItem<NavNode> item, String name) {
        if (name == null) {
            return null;
        }
        if (item.getValue().cls().name().equals(name)) {
            return item;
        }
        for (TreeItem<NavNode> c : item.getChildren()) {
            TreeItem<NavNode> f = find(c, name);
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

    /** Opens the target of a reference (double-click in the inspector). */
    void openRef(String className, long oid) {
        Session s = session;
        if (s == null) {
            return;
        }
        tasks.run(() -> s.get(className, oid), o -> {
            if (o == null) {
                status("dangling reference: " + className.substring(className.lastIndexOf('.') + 1) + " #" + oid + " does not exist");
            } else {
                edit(o);
            }
        }, this::error);
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
        closeDb.setDisable(none);
        txBegin.setDisable(none || txLabel != null);
        txCommit.setDisable(txLabel == null);
        txRollback.setDisable(txLabel == null);
    }

    // ---------------------------------------------------------------- transactions

    /** Shows the transaction state computed on the database thread (null = autocommit). */
    void showTransaction(String label) {
        txLabel = label;
        if (session != null) {
            txLabels.put(session, label);
        }
        txStatus.setText(label == null ? "" : label);
        txStatus.setVisible(label != null);
        updateButtons();
    }

    void beginTransaction() {
        Session s = session;
        if (s == null) {
            return;
        }
        tasks.run(() -> {
            s.begin();
            return s.txLabel();
        }, label -> {
            showTransaction(label);
            status("transaction started: changes stay private until Commit");
            refresh();
        }, this::error);
    }

    void commitTransaction() {
        Session s = session;
        if (s == null || txLabel == null) {
            return;
        }
        tasks.run(() -> {
            s.commit();
            return true;
        }, ok -> {
            status("transaction committed");
            refresh();
        }, t -> {
            showTransaction(null);
            if (t instanceof ConflictException) {
                status("commit failed: conflict");
                Dialogs.error(window(), "Commit failed", t.getMessage()
                        + "\n\nThe transaction was rolled back; reload and apply your changes again.");
            } else {
                error(t);
            }
            refresh();
        });
    }

    void rollbackTransaction() {
        Session s = session;
        if (s == null || txLabel == null || !Dialogs.confirm(window(), "Rollback", "Discard the changes of " + txLabel + "?")) {
            return;
        }
        tasks.run(() -> {
            s.rollback();
            return true;
        }, ok -> {
            status("transaction rolled back");
            refresh();
        }, this::error);
    }

    /** True when the database has nothing uncommitted to lose, or the user agrees to discard it. */
    boolean confirmDiscard(Session s) {
        String tx = txLabels.get(s);
        return tx == null || Dialogs.confirm(window(), "Uncommitted changes",
                labels.get(s) + ": " + tx + " is still open. Discard its changes?");
    }

    /** Before the window closes: true when no open database has an open transaction, or the user agrees. */
    public boolean confirmDiscardAll() {
        List<String> open = new ArrayList<>();
        for (Session s : sessions) {
            if (txLabels.get(s) != null) {
                open.add(labels.get(s) + ": " + txLabels.get(s));
            }
        }
        return open.isEmpty() || Dialogs.confirm(window(), "Uncommitted changes",
                "Open transactions:\n" + String.join("\n", open) + "\n\nDiscard their changes and quit?");
    }

    void status(String text) {
        status.setText(text);
    }

    void error(Throwable t) {
        if (session != null && txLabel != null) {
            refresh();                                         // a timeout may have ended the transaction
        }
        status("error: " + t.getMessage());
        Dialogs.error(window(), "Error", String.valueOf(t.getMessage()));
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    public void close() {
        for (Session s : sessions) {
            s.close();
        }
        sessions.clear();
        session = null;
        tasks.close();
    }
}
