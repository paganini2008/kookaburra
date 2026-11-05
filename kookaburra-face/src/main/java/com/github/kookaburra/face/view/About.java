package com.github.kookaburra.face.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * About dialog: product, version, GitHub, author, e-mail, license and runtime versions, and a feature map of what
 * Kookaburra does, grouped by area.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class About {

    private About() {
    }

    /** Build information from {@code studio.properties} (filtered by Maven). */
    public static Properties info() {
        Properties p = new Properties();
        try (InputStream in = About.class.getResourceAsStream("/com/github/kookaburra/face/studio.properties")) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    /** A group of the feature map: icon, title and its features. */
    record Area(String icon, String title, List<String> features) {
    }

    /** The feature map shown next to the product information. */
    static final List<Area> FEATURES = List.of(
            new Area("mdi2c-cube-outline", "Objects", List.of("Java entities", "Inheritance", "Embedded objects", "References",
                    "Foreign keys", "Unique constraints", "@Renamed", "@Comment", "Export as Java")),
            new Area("mdi2d-database-search", "Query", List.of("Fluent Java API", "SQL-style OQL", "Aggregates", "GROUP BY",
                    "List joins", "Explain", "Explain analyze")),
            new Area("mdi2l-lightning-bolt", "Indexes", List.of("@Index", "Bitmap", "Ordinal", "Multi-value",
                    "Composite unique")),
            new Area("mdi2t-text-search", "Full-text", List.of("BM25 ranking", "Phrases", "Prefixes", "Highlight",
                    "Many languages", "Custom analyzers")),
            new Area("mdi2v-vector-link", "Vectors", List.of("HNSW search", "Cosine / L2 / dot", "Int8 quantization",
                    "Hybrid search", "Diversity (MMR)", "Rerank", "Ollama embedder")),
            new Area("mdi2s-swap-horizontal-circle-outline", "Transactions", List.of("ACID", "Snapshot isolation",
                    "Serializable", "Optimistic locking", "Per-commit durability")),
            new Area("mdi2s-shield-check-outline", "Operations", List.of("Embedded or in memory", "Crash recovery",
                    "Online backup", "Compaction", "Statistics")),
            new Area("mdi2m-monitor-dashboard", "Studio", List.of("Data grid", "Object editor", "OQL console",
                    "Visual plans", "Find similar", "CSV / JSON export", "Themes")));

    /** The feature map: one card per area with its features as chips. */
    static GridPane featureMap() {
        GridPane grid = new GridPane();
        grid.setId("about-features");
        grid.getStyleClass().add("k-feature-map");
        grid.setHgap(10);
        grid.setVgap(10);
        for (int i = 0; i < FEATURES.size(); i++) {
            Area a = FEATURES.get(i);
            Label title = new Label(a.title(), Icons.of(a.icon()));
            title.getStyleClass().add("k-feature-title");
            FlowPane chips = new FlowPane(5, 5);
            chips.setPrefWrapLength(200);
            for (String f : a.features()) {
                Label chip = new Label(f);
                chip.getStyleClass().add("k-feature-chip");
                chips.getChildren().add(chip);
            }
            VBox card = new VBox(8, title, chips);
            card.getStyleClass().add("k-feature-card");
            card.setPrefWidth(222);
            grid.add(card, i % 4, i / 4);
        }
        return grid;
    }

    /** Dialog content; {@code browse} opens links (GitHub, mail). */
    public static HBox content(Consumer<String> browse) {
        Properties p = info();
        ImageView logo = new ImageView(new Image(About.class.getResourceAsStream("/com/github/kookaburra/face/logo.png")));
        logo.setFitWidth(96);
        logo.setPreserveRatio(true);
        Label name = new Label(p.getProperty("name"));
        name.getStyleClass().add("k-about-title");
        Label version = new Label("Version " + p.getProperty("version"));
        version.setId("about-version");
        version.getStyleClass().add("k-muted");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(6);
        Hyperlink github = new Hyperlink(p.getProperty("github"));
        github.setId("about-github");
        github.setOnAction(e -> browse.accept(p.getProperty("github")));
        Hyperlink mail = new Hyperlink(p.getProperty("email"));
        mail.setId("about-email");
        mail.setOnAction(e -> browse.accept("mailto:" + p.getProperty("email")));
        row(grid, 0, "GitHub", github);
        row(grid, 1, "Author", new Label(p.getProperty("author")));
        row(grid, 2, "E-mail", mail);
        row(grid, 3, "License", new Label(p.getProperty("license")));
        row(grid, 4, "Kookaburra core", new Label(p.getProperty("core.version")));
        row(grid, 5, "JavaFX", new Label(System.getProperty("javafx.runtime.version", p.getProperty("javafx.version"))));
        row(grid, 6, "Java", new Label(System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"));

        VBox box = new VBox(10, logo, name, version, grid);
        box.setAlignment(Pos.TOP_CENTER);
        box.setPadding(new Insets(16, 24, 8, 8));
        box.setMinWidth(Region.USE_PREF_SIZE);
        grid.getChildren().forEach(n -> {
            if (n instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
        });
        Label mapTitle = new Label("FEATURE MAP");
        mapTitle.getStyleClass().add("k-section-title");
        VBox map = new VBox(8, mapTitle, featureMap());
        map.setPadding(new Insets(16, 8, 8, 16));
        HBox all = new HBox(8, box, map);
        all.getStyleClass().add("k-about");
        return all;
    }

    private static void row(GridPane grid, int row, String label, Node value) {
        Label l = new Label(label);
        l.getStyleClass().add("k-field-label");
        grid.add(l, 0, row);
        grid.add(value, 1, row);
    }

    public static void show(Window owner, Consumer<String> browse) {
        Dialog<Void> d = new Dialog<>();
        d.initOwner(owner);
        d.setTitle("About Kookaburra Studio");
        d.getDialogPane().setContent(content(browse));
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.getDialogPane().getStyleClass().add("k-dialog");
        d.showAndWait();
    }
}
