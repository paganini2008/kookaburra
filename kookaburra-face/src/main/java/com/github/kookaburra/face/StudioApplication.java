package com.github.kookaburra.face;

import com.github.kookaburra.face.model.Session;
import com.github.kookaburra.face.view.MainView;
import com.github.kookaburra.face.view.Splash;
import com.github.kookaburra.face.view.Theme;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Kookaburra Studio (design §17): dark / neon JavaFX admin tool. Arguments: {@code --demo} or a database path.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class StudioApplication extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(StudioApplication.class);

    private MainView view;

    @Override
    public void start(Stage stage) {
        Splash splash = new Splash();
        splash.show();
        // build the main window on the next pulse so the splash is painted first
        Platform.runLater(() -> {
            view = new MainView();
            view.setBrowser(url -> getHostServices().showDocument(url));
            Scene scene = scene(view);
            stage.setScene(scene);
            stage.setTitle("Kookaburra Studio");
            stage.getIcons().add(new Image(StudioApplication.class.getResourceAsStream("logo_64.png")));
            stage.setOnCloseRequest(e -> {
                if (!view.confirmDiscardAll()) {
                    e.consume();                              // keep the window: uncommitted transactions
                }
            });
            stage.setWidth(1320);
            stage.setHeight(820);
            splash.finish(() -> {
                stage.show();
                LOG.info("window shown ({} theme)", Theme.saved());
                openFromArgs(view, getParameters() == null ? List.of() : getParameters().getRaw());
            });
        });
    }

    /**
     * Opens, side by side: {@code --data=DIR} (or {@code kookaburra.data} in {@code kookaburra.properties}, or the
     * default data directory next to the jar if it holds a database), every plain argument as a database
     * directory, and with {@code --demo} the in-memory demo database (current, as the last one opened).
     */
    static void openFromArgs(MainView view, List<String> args) {
        Path data = StudioPaths.dataDir(args);
        view.setDataDir(data);
        // several databases may open side by side: the data directory, plain paths, then the demo
        if (args.stream().anyMatch(a -> a.startsWith(StudioPaths.ARG)) || StudioPaths.isDatabase(data)) {
            view.open(Session.open(data));
        }
        args.stream().filter(a -> !a.startsWith("--")).forEach(p -> view.open(Session.open(Path.of(p))));
        if (args.contains("--demo")) {
            view.open(Session.demo());
        }
    }

    /** Scene with the saved theme (default: Kookaburra Neon). */
    public static Scene scene(MainView view) {
        Scene scene = new Scene(view);
        view.applyTheme(scene, Theme.saved());
        return scene;
    }

    @Override
    public void stop() {
        if (view != null) {
            view.close();
        }
    }
}
