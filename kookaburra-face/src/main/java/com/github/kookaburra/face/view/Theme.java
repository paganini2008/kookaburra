package com.github.kookaburra.face.view;

import atlantafx.base.theme.CupertinoDark;
import com.github.kookaburra.face.StudioPaths;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.scene.Scene;

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

    /** Applies the theme to the application and the scene. */
    public void apply(Scene scene) {
        Application.setUserAgentStylesheet(userAgent.get());
        scene.getStylesheets().setAll(Theme.class.getResource("/com/github/kookaburra/face/studio.css").toExternalForm());
        if (neon) {
            scene.getStylesheets().add(Theme.class.getResource("/com/github/kookaburra/face/neon.css").toExternalForm());
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
