package com.github.kookaburra.face.view;

import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records the frames of the tutorial video (not part of the regular test run: the class name does not end in
 * {@code Test}). Drives the real Studio through a story and writes 1920 × 1080 frames plus {@code scenes.tsv}
 * (frame, seconds, subtitle) to {@code target/tutorial}; {@code docs/tools/make-tutorial.sh} turns them into the video.
 * <p>
 * Run: {@code mvn -f kookaburra-face/pom.xml test -Dtest=TutorialRecorder -Djacoco.skip=true -Dsurefire.failIfNoSpecifiedTests=false}
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@ExtendWith(ApplicationExtension.class)
class TutorialRecorder {

    static final int W = 1920;
    static final int H = 1080;
    /** Picture area above the subtitle band. */
    static final int PICTURE_H = 880;
    private static final double HI_DPI = 1.5;
    private static final Path OUT = Path.of("target/tutorial");

    private MainView view;
    private Scene scene;
    private Stage stage;
    private final List<String> scenes = new ArrayList<>();

    @Start
    void start(Stage stage) {
        this.stage = stage;
        view = new MainView();
        scene = StudioApplication.scene(view);
        stage.setScene(scene);
        stage.setWidth(1200);
        stage.setHeight(745);
        stage.show();
    }

    // ------------------------------------------------------------------ plumbing

    private void settle() {
        for (int i = 0; i < 500 && view.tasks.pending() > 0; i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
        }
        WaitForAsyncUtils.waitForFxEvents();
        WaitForAsyncUtils.sleep(120, TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean await(BooleanSupplier c) {
        for (int i = 0; i < 250 && !c.getAsBoolean(); i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        return c.getAsBoolean();
    }

    private static BufferedImage snap(Node node, double scale) {
        CompletableFuture<WritableImage> f = new CompletableFuture<>();
        Runnable r = () -> {
            SnapshotParameters p = new SnapshotParameters();
            p.setTransform(Transform.scale(scale, scale));
            p.setFill(Color.TRANSPARENT);
            f.complete(node.snapshot(p, null));
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
        return awt(f.join());
    }

    static BufferedImage awt(WritableImage img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader r = img.getPixelReader();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            r.getPixels(0, y, w, 1, PixelFormat.getIntArgbInstance(), row, 0, w);
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    /** One scene: the main window (optionally with a dialog on top) shown for {@code seconds} with a subtitle. */
    private void shot(String name, double seconds, String subtitle, Window dialog) {
        BufferedImage main = snap(scene.getRoot(), HI_DPI);
        BufferedImage over = dialog == null ? null : snap(dialog.getScene().getRoot(), HI_DPI);
        write(name, compose(main, over), seconds, subtitle);
    }

    private void shot(String name, double seconds, String subtitle) {
        shot(name, seconds, subtitle, null);
    }

    private void write(String name, BufferedImage frame, double seconds, String subtitle) {
        try {
            Files.createDirectories(OUT.resolve("frames"));
            String file = String.format("%02d-%s.png", scenes.size() + 1, name);
            ImageIO.write(frame, "png", OUT.resolve("frames").resolve(file).toFile());
            scenes.add(file + "\t" + seconds + "\t" + subtitle);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The window scaled into the picture area on the dark canvas; a dialog is centred on top, the window dimmed. */
    static BufferedImage compose(BufferedImage main, BufferedImage dialog) {
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(0x0B0E14));
            g.fillRect(0, 0, W, H);
            double scale = Math.min((W - 80.0) / main.getWidth(), (PICTURE_H - 40.0) / main.getHeight());
            int w = (int) Math.round(main.getWidth() * scale);
            int h = (int) Math.round(main.getHeight() * scale);
            int x = (W - w) / 2;
            int y = 20 + (PICTURE_H - 40 - h) / 2;
            g.setColor(new java.awt.Color(0x26304A));
            g.fillRoundRect(x - 3, y - 3, w + 6, h + 6, 14, 14);
            g.drawImage(main, x, y, w, h, null);
            if (dialog != null) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                g.setColor(java.awt.Color.BLACK);
                g.fillRect(x, y, w, h);
                g.setComposite(AlphaComposite.SrcOver);
                double ds = Math.min(scale, Math.min((w - 40.0) / dialog.getWidth(), (h - 40.0) / dialog.getHeight()));
                int dw = (int) Math.round(dialog.getWidth() * ds);
                int dh = (int) Math.round(dialog.getHeight() * ds);
                int dx = x + (w - dw) / 2;
                int dy = y + (h - dh) / 2;
                g.setColor(new java.awt.Color(0x00E5FF));
                g.fillRoundRect(dx - 3, dy - 3, dw + 6, dh + 6, 12, 12);
                g.drawImage(dialog, dx, dy, dw, dh, null);
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * A slide rendered by JavaFX at the picture size: centred {@code top} (e.g. a logo), kicker, title, then either
     * a code card (left-aligned) or a centred {@code bottom} node.
     */
    private void slide(String name, double seconds, String subtitle, String kicker, String title, String code, Node top, Node bottom) {
        CompletableFuture<BufferedImage> img = new CompletableFuture<>();
        Platform.runLater(() -> {
            VBox box = new VBox(26);
            box.setPadding(new Insets(40, 110, 40, 110));
            box.setStyle("-fx-background-color: #0B0E14;");
            box.setPrefSize(W, PICTURE_H);
            box.setAlignment(code == null ? Pos.CENTER : Pos.CENTER_LEFT);
            if (top != null) {
                box.getChildren().add(top);
            }
            if (kicker != null) {
                box.getChildren().add(text(kicker, 28, FontWeight.BOLD, "#FF2BD6"));
            }
            box.getChildren().add(text(title, code == null ? 68 : 52, FontWeight.BOLD, "#E6EDF7"));
            if (code != null) {
                VBox card = new VBox(highlight(code));
                card.setPadding(new Insets(32, 40, 32, 40));
                card.setMaxWidth(Region.USE_PREF_SIZE);
                card.setStyle("-fx-background-color: #11151F; -fx-background-radius: 14; -fx-border-color: #26304A; "
                        + "-fx-border-radius: 14; -fx-border-width: 2;");
                box.getChildren().add(card);
            }
            if (bottom != null) {
                box.getChildren().add(bottom);
            }
            new Scene(box, W, PICTURE_H, Color.web("#0B0E14"));
            box.applyCss();
            box.layout();
            img.complete(awt(box.snapshot(new SnapshotParameters(), null)));
        });
        BufferedImage pic = img.join();
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new java.awt.Color(0x0B0E14));
        g.fillRect(0, 0, W, H);
        g.drawImage(pic, 0, 0, null);
        g.dispose();
        write(name, out, seconds, subtitle);
    }

    private static VBox centred(double spacing, Node... nodes) {
        VBox v = new VBox(spacing, nodes);
        v.setAlignment(Pos.CENTER);
        return v;
    }

    private static Text text(String s, double size, FontWeight weight, String color) {
        Text t = new Text(s);
        t.setFont(Font.font("Helvetica Neue", weight, size));
        t.setFill(Color.web(color));
        return t;
    }

    private static final Pattern TOKENS = Pattern.compile(
            "(?<comment>//[^\\n]*)|(?<str>\"[^\"]*\"|'[^']*')|(?<ann>@\\w+)|(?<kw>\\b(?:try|new|class|public|long|int|double|"
                    + "return|var|SELECT|FROM|WHERE|UPDATE|SET|ORDER|BY|MEMBER|OF|AND|GROUP|COUNT|AVG)\\b)|(?<num>\\b\\d+(?:\\.\\d+)?\\b)");

    /** Java / OQL code with the Studio colours: keywords magenta, strings green, annotations cyan, numbers amber. */
    static TextFlow highlight(String code) {
        TextFlow flow = new TextFlow();
        Matcher m = TOKENS.matcher(code);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) {
                flow.getChildren().add(code(code.substring(at, m.start()), "#E6EDF7"));
            }
            String color = m.group("comment") != null ? "#7A8BA8" : m.group("str") != null ? "#39FF88"
                    : m.group("ann") != null ? "#00E5FF" : m.group("kw") != null ? "#FF2BD6" : "#FFC14D";
            flow.getChildren().add(code(m.group(), color));
            at = m.end();
        }
        flow.getChildren().add(code(code.substring(at), "#E6EDF7"));
        flow.setLineSpacing(6);
        return flow;
    }

    private static Text code(String s, String color) {
        Text t = new Text(s);
        t.setFont(Font.font("Menlo", 31));
        t.setFill(Color.web(color));
        return t;
    }

    private void select(String simpleName) {
        TreeItem<NavNode> item = StudioUiTest.findClass(view, simpleName);
        Platform.runLater(() -> view.tree.getSelectionModel().select(item));
        settle();
    }

    private void filter(String condition) {
        Platform.runLater(() -> {
            view.data.filter.setText(condition);
            view.data.apply.fire();
        });
        settle();
    }

    private int rowWhere(Predicate<DynamicObject> p) {
        List<DynamicObject> items = view.data.table.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (p.test(items.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private void selectRow(int i) {
        Platform.runLater(() -> {
            view.data.table.getSelectionModel().clearAndSelect(i);
            view.data.table.scrollTo(Math.max(0, i - 3));
        });
        settle();
    }

    private void console(String oql, boolean explain) {
        Platform.runLater(() -> {
            view.tabs.getSelectionModel().select(1);
            view.query.setText(oql);
            (explain ? view.query.explain : view.query.run).fire();
        });
        settle();
    }

    private DialogPane dialog() {
        await(() -> Window.getWindows().stream().anyMatch(w -> w != stage && w.isShowing()
                && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane));
        return (DialogPane) Window.getWindows().stream().filter(w -> w != stage && w.isShowing() && w.getScene() != null
                && w.getScene().getRoot() instanceof DialogPane).findFirst().orElseThrow().getScene().getRoot();
    }

    private static void press(DialogPane pane, ButtonType type) {
        Platform.runLater(() -> ((Button) pane.lookupButton(type)).fire());
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------ the story

    @Test
    void record(FxRobot robot, @TempDir Path tmp) throws Exception {
        Files.createDirectories(OUT);
        Files.createDirectories(OUT.resolve("frames"));
        try (var old = Files.list(OUT.resolve("frames"))) {
            old.forEach(p -> p.toFile().delete());
        }
        Image logo = new Image(StudioApplication.class.getResourceAsStream("/com/github/kookaburra/face/logo.png"));
        ImageView logoView = new ImageView(logo);
        logoView.setFitHeight(180);
        logoView.setPreserveRatio(true);

        slide("title", 6, "Kookaburra: an embedded object database for Java 17+, with bitmap indexes and a desktop Studio",
                "TUTORIAL", "Kookaburra in three minutes", null, logoView,
                text("Query Java objects like a database: bitmaps underneath, streams on top.", 34, FontWeight.NORMAL, "#7A8BA8"));
        slide("entity", 7, "Step 1: annotate a plain class with @Entity, and add @Index to the fields you filter or sort on",
                "1 · DECLARE", "A plain class is the schema", """
                        @Entity
                        public class Book {
                            @Id long oid;
                            @Version long version;              // optimistic locking
                            @Index String title;
                            @Index Genre genre;
                            @Index(IndexKind.ORDINAL) int year; // ranges and sorting
                            double price;
                            List<String> tags;
                        }""", null, null);
        slide("query", 8, "Step 2: open a directory, write a batch, and query with the chained, type-safe API",
                "2 · STORE & QUERY", "Chained queries from method references", """
                        try (ObjectDB db = ObjectDB.open(Path.of("data"))) {
                            db.batch().insert(dune).insert(neuromancer).insert(hyperion).commit();

                            List<Book> books = db.query(Book.class)
                                    .where(Book::getGenre).eq(Genre.SCIFI)
                                    .where(Book::getYear).ge(1960)
                                    .orderBy(Book::getYear).desc()
                                    .limit(2)
                                    .list();                     // [Hyperion, Neuromancer]
                        }""", null, null);
        slide("oql", 6, "Or use OQL, a JPQL-style query language, for text queries and bulk updates",
                "3 · OQL", "JPQL-style text queries", """
                        db.oql("SELECT b.author, b.year FROM Book b WHERE b.year < :y ORDER BY b.year")
                          .param("y", 1950).rows();

                        db.oql("UPDATE Book b SET b.price = b.price * 0.9 WHERE b.genre = 'CRIME'").execute();""", null, null);
        slide("transactions", 8, "Transactions are opt-in: begin and commit with snapshot isolation; a conflict raises ConflictException",
                "4 · TRANSACTIONS", "kookaburra.transactions=true", """
                        try (Transaction tx = db.begin(Isolation.SNAPSHOT)) {
                            Account a = tx.get(Account.class, from);
                            tx.update(a.balance(a.getBalance() - 30));
                            tx.increment(Account.class, to, "balance", 30);   // never conflicts
                            tx.commit();                                      // or ConflictException
                        }                                                     // otherwise rolled back""", null, null);
        slide("nested", 7, "Nested objects: embedded objects, lists of them and maps are all queryable with dotted paths",
                "5 · NESTED OBJECTS", "Rich models without flattening", """
                        @Entity     class Shipment { @Id long oid; Route route; List<Item> items; }
                        @Embeddable class Item     { String sku; int qty; Money price; }

                        db.oql("SELECT s FROM Shipment s WHERE s.route.to.city = 'Perth'").list();
                        db.query(Shipment.class)
                          .whereAny(Shipment::getItems, Item::getSku).contains("pear").list();
                        db.oql("SELECT s FROM Shipment s WHERE 'kiwi' MEMBER OF s.items.sku").list();""", null, null);

        // ---- Studio
        StudioApplication app = new StudioApplication();
        Stage[] main = new Stage[1];
        Platform.runLater(() -> main[0] = StudioApplicationAccess.start(app));
        await(() -> Window.getWindows().stream().anyMatch(w -> w.getScene() != null && w.getScene().lookup("#splash-version") != null));
        Window splash = Window.getWindows().stream().filter(w -> w.getScene() != null && w.getScene().lookup("#splash-version") != null)
                .findFirst().orElseThrow();
        write("splash", compose(snap(splash.getScene().getRoot(), HI_DPI * 1.6), null), 4,
                "Kookaburra Studio: a desktop admin tool, one jar for Windows, Linux and macOS");
        Platform.runLater(() -> StudioApplicationAccess.stop(app, main[0]));
        WaitForAsyncUtils.sleep(1500, TimeUnit.MILLISECONDS);
        Platform.runLater(() -> Window.getWindows().stream().filter(w -> w != stage).toList().forEach(w -> ((Stage) w).close()));
        WaitForAsyncUtils.waitForFxEvents();

        Path dir = tmp.resolve("shop");
        Session disk = Session.open(dir);
        DemoData.fill(disk.db(), 400, 7);
        disk.db().flush();
        Platform.runLater(() -> view.open(disk));
        settle();
        Session demo = Session.demo();
        Platform.runLater(() -> view.open(demo));
        settle();
        select("Customer");
        shot("multi-db", 6, "Open several databases side by side: a database on disk and the in-memory demo");

        Platform.runLater(() -> view.data.table.getSortOrder().clear());
        settle();
        shot("grid", 6, "The Data tab: a paged grid of any class; click a header to sort on the server");
        filter("x.age > 60 AND x.address.city = 'Perth'");
        shot("filter", 6, "The filter box takes an OQL condition, e.g. x.age > 60 AND x.address.city = 'Perth'");

        filter("x.contacts IS NOT NULL");
        selectRow(rowWhere(o -> o.get("address") != null && ((Map<?, ?>) o.get("contacts")).size() > 1));
        shot("inspector", 7, "Select a row: the inspector shows its nested values as a Key / Value / Type tree");

        select("Purchase");
        selectRow(rowWhere(o -> ((List<?>) o.get("lines")).size() >= 3));
        TreeItem<ObjectInspector.Entry> lines = view.data.inspector.tree.getRoot().getChildren().stream()
                .filter(i -> i.getValue().key().equals("lines")).findFirst().orElseThrow();
        Platform.runLater(() -> view.data.inspector.tree.getSelectionModel().select(lines.getChildren().get(0)));
        settle();
        shot("lines", 7, "Lists of embedded objects show as [ n elements ] in the grid and expand element by element");

        // the editor dialog: element table, JSON tab
        Platform.runLater(() -> view.edit.fire());
        DialogPane editorPane = dialog();
        ObjectEditor editor = (ObjectEditor) editorPane.getContent();
        Platform.runLater(() -> ((Button) StudioNestedTest.byId(editor.control("lines"), "lines-add")).fire());
        settle();
        int n = ((List<?>) editor.original().get("lines")).size();
        Platform.runLater(() -> {
            Node table = editor.control("lines");
            ((TextField) StudioNestedTest.byId(table, "field-lines[" + n + "].sku")).setText("SKU-2001");
            ((TextField) StudioNestedTest.byId(table, "field-lines[" + n + "].qty")).setText("2");
            ((TextField) StudioNestedTest.byId(table, "field-lines[" + n + "].price.amount")).setText("19.90");
            ((TextField) StudioNestedTest.byId(table, "field-lines[" + n + "].price.currency")).setText("AUD");
        });
        settle();
        shot("editor-form", 7, "Double-click to edit: lists of embedded objects get an element table to add, remove and reorder",
                editorPane.getScene().getWindow());
        Platform.runLater(() -> editor.tabs.getSelectionModel().select(1));
        settle();
        shot("editor-json", 6, "The JSON tab edits the whole nested document, checked against the schema on save",
                editorPane.getScene().getWindow());
        press(editorPane, ButtonType.CANCEL);
        settle();

        select("Product");
        selectRow(0);
        Platform.runLater(() -> view.edit.fire());
        DialogPane productPane = dialog();
        settle();
        shot("binary", 6, "Binary fields show format, size and an image preview, and load from or save to files",
                productPane.getScene().getWindow());
        press(productPane, ButtonType.CANCEL);
        settle();

        // transactions
        select("Customer");
        filter("");
        Platform.runLater(() -> view.txBegin.fire());
        settle();
        DynamicObject first = view.data.table.getItems().get(0);
        DynamicObject second = view.data.table.getItems().get(1);
        first.set("age", 33);
        second.set("tier", "GOLD");
        view.editorDialog = e -> Optional.of(e.original());
        Platform.runLater(() -> view.editObject(first));
        settle();
        Platform.runLater(() -> view.editObject(second));
        settle();
        shot("tx-begin", 7, "Click Begin: edits stay inside the transaction, and the status bar counts the pending changes");
        Platform.runLater(() -> view.txCommit.fire());
        settle();
        shot("tx-commit", 5, "Commit publishes them atomically, Rollback discards them; concurrent edits are reported as conflicts");

        // console
        console("SELECT c.tier, COUNT(*) AS customers, AVG(c.age) AS avgAge\nFROM Customer c\nGROUP BY c.tier\nORDER BY customers DESC", false);
        shot("console", 7, "The Query tab: an OQL console with highlighting, completion and paging; Ctrl+Enter runs");
        console("SELECT p.oid, p.quantity, p.lines\nFROM Purchase p\nWHERE 'SKU-1007' MEMBER OF p.lines.sku", false);
        shot("console-nested", 6, "Query nested paths: MEMBER OF matches any element of a list");
        console("SELECT c FROM Customer c WHERE c.tier = 'GOLD' AND c.age BETWEEN 30 AND 40 ORDER BY c.age DESC", true);
        shot("explain", 6, "EXPLAIN shows the plan: which index answers each condition");

        Platform.runLater(() -> view.tabs.getSelectionModel().select(2));
        settle();
        shot("schema", 6, "The Schema tab: fields, indexes, the class hierarchy and the live ratio of every segment");

        Platform.runLater(() -> {
            view.tabs.getSelectionModel().select(0);
            view.applyTheme(scene, Theme.PRIMER_LIGHT);
        });
        settle();
        select("Purchase");
        selectRow(2);
        shot("theme", 5, "Built-in themes: switch between dark and light at any time");
        Platform.runLater(() -> view.applyTheme(scene, Theme.NEON));
        settle();

        HBox perf = new HBox(26);
        perf.setAlignment(Pos.CENTER);
        for (String[] kv : new String[][]{{"0.75 µs", "get(oid)"}, {"0.24 ms", "bitmap count"}, {"0.40 ms", "top-10 of 2.3 M"},
                {"632 k/s", "bulk insert"}, {"135 k/s", "transactions"}}) {
            VBox card = new VBox(8, text(kv[0], 54, FontWeight.BOLD, "#00E5FF"), text(kv[1], 26, FontWeight.NORMAL, "#7A8BA8"));
            card.setPadding(new Insets(26, 30, 26, 30));
            card.setStyle("-fx-background-color: #11151F; -fx-background-radius: 14; -fx-border-color: #26304A; -fx-border-radius: 14;");
            card.setAlignment(Pos.CENTER);
            perf.getChildren().add(card);
        }
        slide("performance", 8, "On 10 million objects: get in 0.75 µs, bitmap counts in 0.24 ms, 135 k transactions per second",
                "PERFORMANCE", "10 million objects, milliseconds", null, null, centred(36, perf,
                        text("Apple M2 Max · SSD · JDK 17 · full report in docs/benchmark-10m.md", 28, FontWeight.NORMAL, "#7A8BA8")));
        ImageView endLogo = new ImageView(logo);
        endLogo.setFitHeight(150);
        endLogo.setPreserveRatio(true);
        slide("outro", 6, "Apache-2.0 licensed: documentation, source and this video at github.com/paganini2008/kookaburra",
                "GET STARTED", "github.com/paganini2008/kookaburra", null, endLogo,
                text("com.github.paganini2008 : kookaburra-core  ·  paganini2008.github.io/kookaburra", 34, FontWeight.NORMAL, "#39FF88"));

        Files.write(OUT.resolve("scenes.tsv"), scenes, StandardCharsets.UTF_8);
        Platform.runLater(view::close);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
