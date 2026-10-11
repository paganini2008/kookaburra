package com.github.kookaburra.face.view;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.Properties;

/**
 * Start-up splash: logo, product name, version and a loading bar, shown while the main window is built.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Splash {

    /** Minimum time on screen, so the splash does not just flicker on fast machines. */
    static final Duration MIN_SHOWN = Duration.millis(1200);

    private final Stage stage = new Stage(StageStyle.TRANSPARENT);
    final Label version = new Label();
    final Label status = new Label("Loading…");

    public Splash() {
        stage.setScene(scene(content()));
        stage.setTitle("Kookaburra Studio");
        stage.setAlwaysOnTop(true);
    }

    VBox content() {
        Properties info = About.info();
        ImageView logo = new ImageView(new Image(Splash.class.getResourceAsStream("/com/github/kookaburra/face/logo.png")));
        logo.setFitWidth(120);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        Label name = new Label(info.getProperty("name", "Kookaburra Studio"));
        name.getStyleClass().add("k-splash-name");
        version.setText("Version " + info.getProperty("version", "?") + "  ·  core " + info.getProperty("core.version", "?"));
        version.getStyleClass().add("k-splash-version");
        version.setId("splash-version");
        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setPrefWidth(260);
        bar.getStyleClass().add("k-splash-bar");
        status.getStyleClass().add("k-splash-status");
        Label tagline = new Label("Embedded object database on RoaringBitmap");
        tagline.getStyleClass().add("k-splash-status");
        VBox box = new VBox(10, logo, name, version, tagline, bar, status);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("k-splash");
        box.setPrefSize(440, 340);
        return box;
    }

    private static Scene scene(Parent root) {
        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(Splash.class.getResource("/com/github/kookaburra/face/studio.css").toExternalForm());
        return scene;
    }

    public void show() {
        stage.centerOnScreen();
        stage.show();
    }

    public boolean isShowing() {
        return stage.isShowing();
    }

    /** Runs {@code next} after the splash has been visible for {@link #MIN_SHOWN}, then fades the splash out. */
    public void finish(Runnable next) {
        status.setText("Opening…");
        PauseTransition wait = new PauseTransition(MIN_SHOWN);
        wait.setOnFinished(e -> {
            next.run();
            FadeTransition fade = new FadeTransition(Duration.millis(250), stage.getScene().getRoot());
            fade.setToValue(0);
            fade.setOnFinished(x -> stage.close());
            fade.play();
        });
        wait.play();
    }
}
