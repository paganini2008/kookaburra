package com.github.kookaburra.face.view;

import com.github.kookaburra.Isolation;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.face.StudioApplication;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.face.model.Export;
import com.github.kookaburra.face.model.Session;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableRow;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableRow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.transform.Transform;
import javafx.stage.PopupWindow;
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
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Makes the tutorial video {@code docs/assets/kookaburra-tutorial.mp4} and its poster in one run: drives the real
 * Studio through a story (every click is recorded as a before / after pair plus the clicked control's position, so
 * the video shows the mouse cursor gliding there and clicking), then shows the same features as code, and renders
 * everything with {@link TutorialVideo} (cursor animation, cross-fades, English subtitles, original background
 * music). Not part of the regular test run (the class name does not end in {@code Test}); needs ffmpeg with libx264
 * and libass.
 * <p>
 * Run it from the IDE, or: {@code mvn -f kookaburra-face/pom.xml test -P tutorial}
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
    /** Seconds into a Studio clip at which the click happens. */
    private static final double CLICK_AT = 1.6;

    private MainView view;
    private Scene scene;
    private Stage stage;
    private final List<TutorialVideo.Clip> clips = new ArrayList<>();

    /**
     * A captured picture and how scene coordinates map into it: {@code frame = o + sceneOffset × k} for the main
     * window (and popups), with separate values for a dialog drawn on top.
     */
    private record Frame(BufferedImage image, double ox, double oy, double k, Window dialog, double dox, double doy, double dk) {
    }

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
        WaitForAsyncUtils.sleep(150, TimeUnit.MILLISECONDS);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean await(BooleanSupplier c) {
        for (int i = 0; i < 250 && !c.getAsBoolean(); i++) {
            WaitForAsyncUtils.sleep(20, TimeUnit.MILLISECONDS);
            WaitForAsyncUtils.waitForFxEvents();
        }
        return c.getAsBoolean();
    }

    private static <T> T fx(Supplier<T> s) {
        if (Platform.isFxApplicationThread()) {
            return s.get();
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(s.get());
            } catch (RuntimeException e) {
                f.completeExceptionally(e);
            }
        });
        return f.join();
    }

    private static BufferedImage snap(Node node, double scale) {
        return fx(() -> {
            SnapshotParameters p = new SnapshotParameters();
            p.setTransform(Transform.scale(scale, scale));
            p.setFill(Color.TRANSPARENT);
            return awt(node.snapshot(p, null));
        });
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

    /** Showing windows above the main one: a dialog and / or a popup (menu). */
    private Window overlay(boolean dialog) {
        return fx(() -> Window.getWindows().stream()
                .filter(w -> w != stage && w.isShowing() && w.getScene() != null)
                .filter(w -> dialog ? w.getScene().getRoot() instanceof DialogPane : w instanceof PopupWindow)
                .reduce((a, b) -> b).orElse(null));
    }

    /** The main window scaled into the picture area; a dialog centred on top (window dimmed), a popup at its place. */
    private Frame capture() {
        Window dialog = overlay(true);
        Window popup = overlay(false);
        BufferedImage main = snap(scene.getRoot(), HI_DPI);
        BufferedImage dlg = dialog == null ? null : snap(dialog.getScene().getRoot(), HI_DPI);
        BufferedImage pop = popup == null ? null : snap(popup.getScene().getRoot(), HI_DPI);
        double[] popAt = popup == null ? null : fx(() -> new double[]{
                popup.getX() + popup.getScene().getX() - stage.getX() - scene.getX(),
                popup.getY() + popup.getScene().getY() - stage.getY() - scene.getY()});

        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        double scale = Math.min((W - 80.0) / main.getWidth(), (PICTURE_H - 40.0) / main.getHeight());
        int w = (int) Math.round(main.getWidth() * scale);
        int h = (int) Math.round(main.getHeight() * scale);
        int x = (W - w) / 2;
        int y = 20 + (PICTURE_H - 40 - h) / 2;
        double dox = 0;
        double doy = 0;
        double dk = 0;
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new java.awt.Color(0x0B0E14));
            g.fillRect(0, 0, W, H);
            g.setColor(new java.awt.Color(0x26304A));
            g.fillRoundRect(x - 3, y - 3, w + 6, h + 6, 14, 14);
            g.drawImage(main, x, y, w, h, null);
            if (pop != null) {
                g.drawImage(pop, (int) Math.round(x + popAt[0] * HI_DPI * scale), (int) Math.round(y + popAt[1] * HI_DPI * scale),
                        (int) Math.round(pop.getWidth() * scale), (int) Math.round(pop.getHeight() * scale), null);
            }
            if (dlg != null) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                g.setColor(java.awt.Color.BLACK);
                g.fillRect(x, y, w, h);
                g.setComposite(AlphaComposite.SrcOver);
                double ds = Math.min(scale, Math.min((w - 40.0) / dlg.getWidth(), (h - 40.0) / dlg.getHeight()));
                int dw = (int) Math.round(dlg.getWidth() * ds);
                int dh = (int) Math.round(dlg.getHeight() * ds);
                dox = x + (w - dw) / 2.0;
                doy = y + (h - dh) / 2.0;
                dk = HI_DPI * ds;
                g.setColor(new java.awt.Color(0x00E5FF));
                g.fillRoundRect((int) dox - 3, (int) doy - 3, dw + 6, dh + 6, 12, 12);
                g.drawImage(dlg, (int) dox, (int) doy, dw, dh, null);
            }
        } finally {
            g.dispose();
        }
        return new Frame(out, x, y, HI_DPI * scale, dialog, dox, doy, dk);
    }

    /** Position of a control in a captured frame (its centre, or {@code fx} of its width from the left). */
    private Point2D at(Frame f, Node node, double fxw) {
        return fx(() -> {
            Bounds b = node.localToScreen(node.getBoundsInLocal());
            double cx = b.getMinX() + b.getWidth() * fxw;
            double cy = b.getCenterY();
            Window w = node.getScene().getWindow();
            if (w == f.dialog()) {
                Scene s = w.getScene();
                return new Point2D.Double(f.dox() + (cx - w.getX() - s.getX()) * f.dk(), f.doy() + (cy - w.getY() - s.getY()) * f.dk());
            }
            return new Point2D.Double(f.ox() + (cx - stage.getX() - scene.getX()) * f.k(), f.oy() + (cy - stage.getY() - scene.getY()) * f.k());
        });
    }

    private Path save(String file, BufferedImage img) {
        try {
            Path p = OUT.resolve("frames").resolve(file);
            ImageIO.write(img, "png", p.toFile());
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A Studio clip: the cursor glides to {@code target} and clicks; {@code action} then runs (on the FX thread)
     * and the picture switches to the result. {@code waitFor} (may be null) holds the "after" capture until it is true.
     */
    private void click(String name, double seconds, String subtitle, Supplier<Node> target, double fxw, Runnable action, BooleanSupplier waitFor) {
        Frame before = capture();
        Point2D to = at(before, target.get(), fxw);
        String id = String.format("%02d-%s", clips.size() + 1, name);
        Path a = save(id + "-a.png", before.image());
        Platform.runLater(action);
        settle();
        if (waitFor != null) {
            await(waitFor);
            settle();
        }
        Path b = save(id + "-b.png", capture().image());
        clips.add(new TutorialVideo.Clip(id, a, b, seconds, subtitle, to, true, CLICK_AT));
    }

    private void click(String name, double seconds, String subtitle, Supplier<Node> target, Runnable action) {
        click(name, seconds, subtitle, target, 0.5, action, null);
    }

    private void click(String name, double seconds, String subtitle, Supplier<Node> target, Runnable action, BooleanSupplier waitFor) {
        click(name, seconds, subtitle, target, 0.5, action, waitFor);
    }

    /** A clip without a picture change: a still image, with or without the cursor. */
    private void still(String name, BufferedImage image, double seconds, String subtitle, boolean cursor) {
        String id = String.format("%02d-%s", clips.size() + 1, name);
        clips.add(new TutorialVideo.Clip(id, null, save(id + ".png", image), seconds, subtitle, null, cursor, 0));
    }

    // ------------------------------------------------------------------ slides

    /**
     * A slide rendered by JavaFX at the picture size: centred {@code top} (e.g. a logo), kicker, title, then either
     * a code card (left-aligned) or a centred {@code bottom} node.
     */
    private void slide(String name, double seconds, String subtitle, String kicker, String title, String code, Node top, Node bottom) {
        BufferedImage pic = fx(() -> {
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
            return awt(box.snapshot(new SnapshotParameters(), null));
        });
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new java.awt.Color(0x0B0E14));
        g.fillRect(0, 0, W, H);
        g.drawImage(pic, 0, 0, null);
        g.dispose();
        still(name, out, seconds, subtitle, false);
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
                    + "return|var|catch|if|SELECT|FROM|WHERE|UPDATE|SET|ORDER|BY|MEMBER|OF|AND|GROUP|COUNT|AVG)\\b)|(?<num>\\b\\d+(?:\\.\\d+)?\\b)");

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
        t.setFont(Font.font("Menlo", 30));
        t.setFill(Color.web(color));
        return t;
    }

    // ------------------------------------------------------------------ finding controls

    private Node treeCell(String simpleName) {
        TreeItem<NavNode> item = StudioUiTest.findClass(view, simpleName);
        return fx(() -> view.tree.lookupAll(".tree-cell").stream()
                .filter(n -> n instanceof TreeCell<?> c && c.getTreeItem() == item).findFirst().orElseThrow());
    }

    private Node tableRow(int index) {
        return fx(() -> view.data.table.lookupAll(".table-row-cell").stream()
                .filter(n -> n instanceof TableRow<?> r && r.getIndex() == index).findFirst().orElseThrow());
    }

    private Node inspectorRow(TreeItem<ObjectInspector.Entry> item) {
        return fx(() -> view.data.inspector.tree.lookupAll(".tree-table-row-cell").stream()
                .filter(n -> n instanceof TreeTableRow<?> r && r.getTreeItem() == item).findFirst().orElseThrow());
    }

    private Node tab(int index) {
        return fx(() -> view.tabs.lookupAll(".tab").stream().toList().get(index));
    }

    private static Node labeled(Node root, String text) {
        return fx(() -> root.lookupAll(".menu-item").stream()
                .filter(n -> n.lookupAll(".label").stream().anyMatch(l -> l instanceof Labeled lb && lb.getText().contains(text)))
                .findFirst().orElseThrow());
    }

    private TreeItem<ObjectInspector.Entry> inspectorItem(String key) {
        return view.data.inspector.tree.getRoot().getChildren().stream()
                .filter(i -> i.getValue().key().equals(key)).findFirst().orElseThrow();
    }

    private void select(String simpleName) {
        TreeItem<NavNode> item = StudioUiTest.findClass(view, simpleName);
        Platform.runLater(() -> view.tree.getSelectionModel().select(item));
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

    private DialogPane dialog() {
        await(() -> overlay(true) != null);
        return (DialogPane) overlay(true).getScene().getRoot();
    }

    // ------------------------------------------------------------------ the story

    @Test
    void record(FxRobot robot, @TempDir Path tmp) throws Exception {
        Files.createDirectories(OUT.resolve("frames"));
        try (Stream<Path> old = Files.list(OUT.resolve("frames"))) {
            old.forEach(p -> p.toFile().delete());
        }
        Image logo = new Image(StudioApplication.class.getResourceAsStream("/com/github/kookaburra/face/logo.png"));
        ImageView logoView = new ImageView(logo);
        logoView.setFitHeight(180);
        logoView.setPreserveRatio(true);

        slide("title", 5, "Kookaburra: an embedded object database for Java 17+, with type-safe queries and a desktop Studio",
                "TUTORIAL", "Kookaburra in three minutes", null, logoView,
                text("Query Java objects like a database, right inside your application.", 34, FontWeight.NORMAL, "#7A8BA8"));

        // ---- part 1: Studio, operated with the mouse
        StudioApplication app = new StudioApplication();
        Stage[] main = new Stage[1];
        Platform.runLater(() -> main[0] = StudioApplicationAccess.start(app));
        await(() -> Window.getWindows().stream().anyMatch(w -> w.getScene() != null && w.getScene().lookup("#splash-version") != null));
        Window splash = Window.getWindows().stream().filter(w -> w.getScene() != null && w.getScene().lookup("#splash-version") != null)
                .findFirst().orElseThrow();
        BufferedImage splashImg = snap(splash.getScene().getRoot(), HI_DPI * 1.6);
        BufferedImage splashFrame = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = splashFrame.createGraphics();
        sg.setColor(new java.awt.Color(0x0B0E14));
        sg.fillRect(0, 0, W, H);
        sg.drawImage(splashImg, (W - splashImg.getWidth()) / 2, (PICTURE_H - splashImg.getHeight()) / 2, null);
        sg.dispose();
        still("splash", splashFrame, 3.5, "Part 1, Kookaburra Studio: a desktop tool to browse, edit, query and back up any database", false);
        Platform.runLater(() -> StudioApplicationAccess.stop(app, main[0]));
        WaitForAsyncUtils.sleep(1500, TimeUnit.MILLISECONDS);
        Platform.runLater(() -> Window.getWindows().stream().filter(w -> w != stage).toList().forEach(w -> ((Stage) w).close()));
        WaitForAsyncUtils.waitForFxEvents();
        settle();

        Path dir = tmp.resolve("shop");
        Session disk = Session.open(dir);
        DemoData.fill(disk.db(), 400, 7);
        disk.db().flush();
        view.backupChooser = () -> tmp.resolve("backups").toFile();
        view.data.exportChooser = format -> tmp.resolve("customers." + format.extension()).toFile();
        click("open", 4.5, "Open databases side by side: a directory on disk and the in-memory demo",
                () -> view.demo, 0.5, () -> {
                    view.open(disk);
                    view.open(Session.demo());
                }, () -> !view.status.getText().equals("No database open"));

        click("customers", 4, "Click a class: the Data tab shows a paged grid of its objects",
                () -> treeCell("Customer"), () -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, "Customer")));

        Platform.runLater(() -> view.data.filter.setText("x.age > 60 AND x.address.city = 'Perth'"));
        settle();
        click("filter", 4.5, "Type an OQL condition into the filter box and apply it",
                () -> view.data.apply, () -> view.data.apply.fire());

        Platform.runLater(() -> {
            view.data.filter.setText("x.contacts IS NOT NULL");
            view.data.apply.fire();
        });
        settle();
        int row = rowWhere(o -> o.get("address") != null && ((Map<?, ?>) o.get("contacts")).size() > 1);
        click("inspector", 5, "Select a row: the inspector shows its nested values as a Key / Value / Type tree",
                () -> tableRow(row), 0.3, () -> view.data.table.getSelectionModel().clearAndSelect(row), null);

        Platform.runLater(() -> view.data.inspector.tree.getRoot().getChildren().forEach(i -> i.setExpanded(false)));
        settle();
        TreeItem<ObjectInspector.Entry> links = inspectorItem(ObjectInspector.REFERENCED_BY);
        click("backlinks", 5, "Expand \"referenced by\" to see every object that points at it, here the customer's purchases",
                () -> inspectorRow(links), 0.25, () -> links.setExpanded(true), () -> links.getChildren().size() > 0
                        && !links.getChildren().get(0).getValue().key().equals("…"));

        click("export-menu", 3.5, "Export writes every object that matches the filter",
                () -> view.data.export, () -> view.data.export.show(), () -> overlay(false) != null);
        Window exportMenu = overlay(false);
        click("export-csv", 4.5, "as JSON, JSONL or CSV, read in chunks so large exports stay small in memory",
                () -> labeled(exportMenu.getScene().getRoot(), "CSV"), () -> {
                    view.data.export.hide();
                    view.data.exportAs(Export.Format.CSV);
                }, () -> view.status.getText().startsWith("exported"));

        click("purchases", 3.5, "Purchases hold a list of order lines and references to products",
                () -> treeCell("Purchase"), () -> view.tree.getSelectionModel().select(StudioUiTest.findClass(view, "Purchase")));
        int withLines = rowWhere(o -> ((List<?>) o.get("lines")).size() >= 3);
        click("lines", 4.5, "Lists of embedded objects show as [ n elements ] and expand element by element",
                () -> tableRow(withLines), 0.3, () -> {
                    view.data.table.getSelectionModel().clearAndSelect(withLines);
                    TreeItem<ObjectInspector.Entry> lines = inspectorItem("lines");
                    view.data.inspector.tree.getSelectionModel().select(lines.getChildren().get(0));
                }, null);

        TreeItem<ObjectInspector.Entry> productRef = inspectorItem("product");
        click("reference", 5, "Double-click a reference to open the object it points at, here the product with its photo",
                () -> inspectorRow(productRef), 0.25, () -> view.openRef(productRef.getValue().refClass(), productRef.getValue().refOid()),
                () -> overlay(true) != null);
        DialogPane refPane = dialog();
        click("reference-close", 2, "",
                () -> refPane.lookupButton(ButtonType.CANCEL), () -> ((Button) refPane.lookupButton(ButtonType.CANCEL)).fire());

        click("edit", 4.5, "Double-click a row to edit it: lists of embedded objects get an element table",
                () -> tableRow(withLines), 0.3, () -> view.edit.fire(), () -> overlay(true) != null);
        DialogPane editorPane = dialog();
        ObjectEditor editor = (ObjectEditor) editorPane.getContent();
        int n = ((List<?>) editor.original().get("lines")).size();
        click("add-line", 5, "Add, remove and reorder elements; nested fields are columns",
                () -> StudioNestedTest.byId(editor.control("lines"), "lines-add"), () -> {
                    ((Button) StudioNestedTest.byId(editor.control("lines"), "lines-add")).fire();
                    Platform.runLater(() -> {
                        Node table = editor.control("lines");
                        ((TextField) StudioNestedTest.byId(table, "field-lines[" + n + "].sku")).setText("SKU-1001");
                        ((TextField) StudioNestedTest.byId(table, "field-lines[" + n + "].qty")).setText("2");
                        ((TextField) StudioNestedTest.byId(table, "field-lines[" + n + "].price.amount")).setText("19.90");
                        ((TextField) StudioNestedTest.byId(table, "field-lines[" + n + "].price.currency")).setText("AUD");
                    });
                });
        click("json", 4.5, "The JSON tab edits the whole nested document, checked against the schema on save",
                () -> fx(() -> editor.tabs.lookupAll(".tab").stream().toList().get(1)), () -> editor.tabs.getSelectionModel().select(1));
        click("cancel", 2, "",
                () -> editorPane.lookupButton(ButtonType.CANCEL), () -> ((Button) editorPane.lookupButton(ButtonType.CANCEL)).fire());

        // a unique e-mail
        click("customers-again", 2.5, "Back to the customers",
                () -> treeCell("Customer"), () -> {
                    view.data.filter.setText("");
                    view.tree.getSelectionModel().select(StudioUiTest.findClass(view, "Customer"));
                });
        String taken = (String) view.data.table.getItems().get(0).get("email");
        click("new", 3.5, "The + button creates an object",
                () -> view.add, () -> view.add.fire(), () -> overlay(true) != null);
        DialogPane newPane = dialog();
        ObjectEditor newEditor = (ObjectEditor) newPane.getContent();
        Platform.runLater(() -> {
            ((TextField) newEditor.control("name")).setText("Ann Copy");
            ((TextField) newEditor.control("email")).setText(taken);
        });
        settle();
        click("unique", 5.5, "Customer.email is @Unique: saving a taken e-mail is refused and nothing is stored",
                () -> newPane.lookupButton(ButtonType.OK), () -> ((Button) newPane.lookupButton(ButtonType.OK)).fire(),
                () -> overlay(true) != null && overlay(true).getScene().getRoot() != newPane);
        DialogPane error = dialog();
        click("close-error", 2, "",
                () -> error.lookupButton(ButtonType.OK), () -> ((Button) error.lookupButton(ButtonType.OK)).fire());

        // delete with ON DELETE CASCADE
        click("select-victim", 3, "Select a customer and press Delete",
                () -> tableRow(1), 0.3, () -> view.data.table.getSelectionModel().clearAndSelect(1), null);
        click("delete", 3.5, "Delete asks first",
                () -> view.delete, () -> view.delete.fire(), () -> overlay(true) != null);
        DialogPane confirm = dialog();
        click("delete-ok", 5, "Purchase.customer is a foreign key with CASCADE: the customer's purchases go in the same commit",
                () -> confirm.lookupButton(ButtonType.OK), () -> ((Button) confirm.lookupButton(ButtonType.OK)).fire(),
                () -> StudioUiTest.findClass(view, "Purchase").getValue().cls().count() < 750);

        // transactions: SERIALIZABLE, commit, rollback
        click("isolation-menu", 3.5, "The arrow next to Begin picks the isolation level",
                () -> fx(() -> view.txBegin.lookup(".arrow-button")), () -> view.txBegin.show(), () -> overlay(false) != null);
        Window menu = overlay(false);
        click("serializable", 3.5, "Read committed, snapshot or serializable",
                () -> labeled(menu.getScene().getRoot(), "Serializable"), () -> {
                    Toggle t = view.isolation.getToggles().stream().filter(x -> x.getUserData() == Isolation.SERIALIZABLE)
                            .findFirst().orElseThrow();
                    view.isolation.selectToggle(t);
                    view.txBegin.hide();
                });
        DynamicObject first = view.data.table.getItems().get(0);
        DynamicObject second = view.data.table.getItems().get(1);
        view.editorDialog = e -> Optional.of(e.original());
        click("begin", 4.5, "Begin: edits stay private until Commit; the status bar counts the pending changes",
                () -> view.txBegin, 0.3, () -> {
                    view.txBegin.fire();
                    first.set("age", 33);
                    second.set("tier", "GOLD");
                    Platform.runLater(() -> {
                        view.editObject(first);
                        Platform.runLater(() -> view.editObject(second));
                    });
                }, () -> view.txStatus.getText().contains("2 pending"));
        click("commit", 4, "Commit publishes both changes atomically",
                () -> view.txCommit, () -> view.txCommit.fire());
        DynamicObject third = view.data.table.getItems().get(2);
        click("begin-again", 3.5, "Another transaction, another edit",
                () -> view.txBegin, 0.3, () -> {
                    view.txBegin.fire();
                    third.set("age", 99);
                    Platform.runLater(() -> view.editObject(third));
                }, () -> view.txStatus.getText().contains("1 pending"));
        click("rollback", 3.5, "Rollback",
                () -> view.txRollback, () -> view.txRollback.fire(), () -> overlay(true) != null);
        DialogPane discard = dialog();
        click("rollback-ok", 3.5, "discards it: nothing reached the database",
                () -> discard.lookupButton(ButtonType.OK), () -> ((Button) discard.lookupButton(ButtonType.OK)).fire(),
                () -> !view.txStatus.isVisible());

        // console
        Platform.runLater(() -> view.query.setText("SELECT c.tier, COUNT(*) AS customers, AVG(c.age) AS avgAge\n"
                + "FROM Customer c\nGROUP BY c.tier\nORDER BY customers DESC"));
        click("query-tab", 3.5, "The Query tab: an OQL console with highlighting and completion",
                () -> tab(1), () -> view.tabs.getSelectionModel().select(1));
        click("run", 4.5, "Run (or Ctrl+Enter) shows the result, paged",
                () -> view.query.run, () -> view.query.run.fire());
        Platform.runLater(() -> view.query.setText("SELECT c FROM Customer c\nWHERE c.tier = 'GOLD' AND c.age BETWEEN 30 AND 40\n"
                + "ORDER BY c.age DESC"));
        settle();
        click("explain", 4.5, "Explain shows the plan: which index answers each condition, cheapest first",
                () -> view.query.explain, () -> view.query.explain.fire());

        // schema with constraints
        click("schema", 4.5, "The Schema tab: fields, indexes and constraints such as UNIQUE and foreign keys",
                () -> tab(2), () -> {
                    view.tree.getSelectionModel().select(StudioUiTest.findClass(view, "Purchase"));
                    view.tabs.getSelectionModel().select(2);
                    view.schema.split.setDividerPositions(0.62);
                });

        // backup
        click("backup", 5, "Backup copies the database as of now while it keeps running; open the copy to restore",
                () -> view.backup, () -> view.backup.fire(), () -> view.status.getText().startsWith("backup of"));

        // ---- part 2: the same features in code
        slide("code", 3.5, "Part 2, the Java API: everything Studio showed is a few lines of code",
                "PART 2", "The same, in code", null, logoView, null);
        slide("entity", 6, "Annotate a plain class: indexes, a unique e-mail, a foreign key, an exact decimal and a callback",
                "DECLARE", "A plain class is the schema", """
                        @Entity
                        public class Order {
                            @Id long oid;
                            @Unique String number;                                  // no duplicates
                            @ForeignKey(onDelete = OnDelete.CASCADE) Ref<Customer> customer;
                            @Index BigDecimal total;                                // exact money
                            List<Line> lines;                                       // embedded objects
                            @PrePersist void stamp() { created = Instant.now(); }
                        }""", null, null);
        slide("query", 6, "Open a directory, write a batch, and query with the chained, type-safe API",
                "STORE AND QUERY", "Chained queries from method references", """
                        try (ObjectDB db = ObjectDB.open(Path.of("data"))) {
                            db.batch().insert(order1).insert(order2).commit();

                            List<Order> big = db.query(Order.class)
                                    .where(Order::getTotal).gt(new BigDecimal("100"))
                                    .orderBy(Order::getTotal).desc()
                                    .limit(10)
                                    .list();
                        }""", null, null);
        slide("oql", 5.5, "OQL is a JPQL-style text language for the console, scripts and bulk updates",
                "OQL", "JPQL-style text queries", """
                        db.oql("SELECT c.tier, COUNT(*), AVG(c.age) FROM Customer c GROUP BY c.tier").rows();

                        db.oql("SELECT p FROM Purchase p WHERE 'SKU-1007' MEMBER OF p.lines.sku").list();

                        db.oql("UPDATE Product p SET p.price = p.price * 0.9 WHERE p.price > 100").execute();""", null, null);
        slide("transactions", 6, "Transactions at three isolation levels; SERIALIZABLE also stops write skew",
                "TRANSACTIONS", "Optimistic, never blocking", """
                        db.inTransaction(TxOptions.defaults().isolation(Isolation.SERIALIZABLE), tx -> {
                            Account a = tx.get(Account.class, from);
                            tx.update(a.balance(a.getBalance() - 30));
                            tx.increment(Account.class, to, "balance", 30);
                        });                     // conflicts are retried automatically""", null, null);
        slide("constraints", 6, "Constraints are checked when the write commits; backlinks come from the index",
                "INTEGRITY", "Unique keys and foreign keys", """
                        db.insert(new Customer("Ann", "ann@example.com"));
                        db.insert(new Customer("Bob", "ann@example.com"));   // ConstraintViolationException

                        db.referrers(Purchase.class, annOid);                 // her purchases
                        db.delete(Customer.class, annOid);                    // purchases go too (CASCADE)""", null, null);
        slide("backup-code", 5, "Online backup in one call; restoring is opening the directory",
                "BACKUP", "A consistent copy while writing", """
                        BackupInfo info = db.backup(Path.of("backups/2026-10-17"));

                        try (ObjectDB copy = ObjectDB.open(info.path())) {
                            copy.query(Order.class).count();
                        }""", null, null);

        HBox perf = new HBox(26);
        perf.setAlignment(Pos.CENTER);
        for (String[] kv : new String[][]{{"0.6 µs", "get(oid)"}, {"0.24 ms", "indexed count"}, {"0.39 ms", "top-10 of 2.3 M"},
                {"696 k/s", "bulk insert"}, {"140 k/s", "serializable tx"}}) {
            VBox card = new VBox(8, text(kv[0], 54, FontWeight.BOLD, "#00E5FF"), text(kv[1], 26, FontWeight.NORMAL, "#7A8BA8"));
            card.setPadding(new Insets(26, 30, 26, 30));
            card.setStyle("-fx-background-color: #11151F; -fx-background-radius: 14; -fx-border-color: #26304A; -fx-border-radius: 14;");
            card.setAlignment(Pos.CENTER);
            perf.getChildren().add(card);
        }
        slide("performance", 6, "On 10 million objects: get in 0.6 µs, indexed counts in 0.24 ms, 140 k serializable transactions per second",
                "PERFORMANCE", "10 million objects, milliseconds", null, null, centred(36, perf,
                        text("Apple M2 Max · SSD · JDK 17 · full report in docs/benchmark-10m.md", 28, FontWeight.NORMAL, "#7A8BA8")));
        ImageView endLogo = new ImageView(logo);
        endLogo.setFitHeight(150);
        endLogo.setPreserveRatio(true);
        slide("outro", 5, "Apache-2.0 licensed: documentation, source and this video at github.com/paganini2008/kookaburra",
                "GET STARTED", "github.com/paganini2008/kookaburra", null, endLogo,
                text("com.github.paganini2008 : kookaburra-core  ·  paganini2008.github.io/kookaburra", 34, FontWeight.NORMAL, "#39FF88"));
        Platform.runLater(view::close);
        WaitForAsyncUtils.waitForFxEvents();

        // ---- render into docs/assets
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("docs/assets"))) {
            root = root.getParent();
        }
        Path video = root.resolve("docs/assets/kookaburra-tutorial.mp4");
        Path poster = root.resolve("docs/assets/kookaburra-tutorial-poster.jpg");
        double seconds = new TutorialVideo(OUT.resolve("build").toAbsolutePath()).render(clips, video, poster);
        assertThat(TutorialVideo.duration(video)).isBetween(seconds - 0.5, seconds + 0.5);
        assertThat(seconds).isBetween(120.0, 180.0);                    // under three minutes
        assertThat(clips).allMatch(c -> !c.subtitle().contains("—"));
        assertThat(ImageIO.read(poster.toFile()).getWidth()).isEqualTo(1280);
    }
}
