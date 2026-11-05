package com.github.kookaburra.face.view;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.github.kookaburra.face.StudioPaths;
import javafx.application.Application;
import javafx.collections.ListChangeListener;
import javafx.scene.Scene;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.prefs.Preferences;

/**
 * Studio themes: Kookaburra Neon (default) and the AtlantaFX themes. The choice is stored in user preferences.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public enum Theme {
    NEON("Kookaburra Neon", () -> new Dracula().getUserAgentStylesheet(), true),
    DRACULA("Dracula", () -> new Dracula().getUserAgentStylesheet(), false),
    NORD_DARK("Nord Dark", () -> new NordDark().getUserAgentStylesheet(), false),
    NORD_LIGHT("Nord Light", () -> new NordLight().getUserAgentStylesheet(), false),
    PRIMER_DARK("Primer Dark", () -> new PrimerDark().getUserAgentStylesheet(), false),
    PRIMER_LIGHT("Primer Light", () -> new PrimerLight().getUserAgentStylesheet(), false),
    CUPERTINO_DARK("Cupertino Dark", () -> new CupertinoDark().getUserAgentStylesheet(), false),
    CUPERTINO_LIGHT("Cupertino Light", () -> new CupertinoLight().getUserAgentStylesheet(), false);

    private static final String KEY = "theme";
    private static final Preferences PREFS = Preferences.userRoot().node("com/github/kookaburra/face");

    private final String title;
    private final Supplier<String> userAgent;
    private final boolean neon;

    Theme(String title, Supplier<String> userAgent, boolean neon) {
        this.title = title;
        this.userAgent = userAgent;
        this.neon = neon;
    }

    public String title() {
        return title;
    }

    /** Whether its background is dark (white text) or light (black text). */
    public boolean dark() {
        return this == NEON || this == DRACULA || name().endsWith("_DARK");
    }

    /** Stylesheets of the theme, in order: Studio, Neon colours, text colour (pure white or pure black, no grey). */
    public List<String> stylesheets() {
        List<String> css = new ArrayList<>();
        css.add(url("studio.css"));
        if (neon) {
            css.add(url("neon.css"));
        }
        css.add(url(dark() ? "text-dark.css" : "text-light.css"));
        return css;
    }

    private static String url(String name) {
        return Theme.class.getResource("/com/github/kookaburra/face/" + name).toExternalForm();
    }

    /** The theme applied last; windows opened later (dialogs) take its stylesheets. */
    private static Theme current;
    private static boolean following;

    /** Applies the theme to the application, the scene and every open window (dialogs included). */
    public void apply(Scene scene) {
        Application.setUserAgentStylesheet(userAgent.get());
        current = this;
        scene.getStylesheets().setAll(stylesheets());
        for (Window w : Window.getWindows()) {
            style(w);
        }
        if (!following) {
            following = true;
            Window.getWindows().addListener((ListChangeListener<Window>) c -> {
                while (c.next()) {
                    for (Window w : c.getAddedSubList()) {
                        style(w);
                        w.sceneProperty().addListener((o, a, b) -> {      // a dialog's scene comes later
                            if (b != null && current != null) {
                                b.getStylesheets().setAll(current.stylesheets());
                            }
                        });
                    }
                }
            });
        }
    }

    /** Gives a window's scene the current theme's stylesheets. */
    static void style(Window w) {
        if (current != null && w.getScene() != null && !w.getScene().getStylesheets().equals(current.stylesheets())) {
            w.getScene().getStylesheets().setAll(current.stylesheets());
        }
    }

    /** The theme saved by the user, else {@code kookaburra.theme}, else {@link #NEON}. */
    public static Theme saved() {
        String fallback = StudioPaths.settings().getOrDefault(StudioPaths.THEME, NEON.name());
        try {
            return valueOf(PREFS.get(KEY, fallback).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NEON;
        }
    }

    public void save() {
        PREFS.put(KEY, name());
    }
}
