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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * About dialog: product, version, GitHub, author, e-mail, license and runtime versions.
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

    /** Dialog content; {@code browse} opens links (GitHub, mail). */
    public static VBox content(Consumer<String> browse) {
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
        box.setPadding(new Insets(16, 24, 8, 24));
        box.getStyleClass().add("k-about");
        return box;
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
