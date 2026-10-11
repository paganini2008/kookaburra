package com.github.kookaburra.face.view;

import com.github.kookaburra.face.StudioApplication;
import javafx.stage.Stage;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Test access to {@link StudioApplication}'s package-private startup handling.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class StudioApplicationAccess {

    private StudioApplicationAccess() {
    }

    static void open(MainView view, List<String> args) {
        try {
            Method m = StudioApplication.class.getDeclaredMethod("openFromArgs", MainView.class, List.class);
            m.setAccessible(true);
            m.invoke(null, view, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Starts the real application on a fresh stage (FX thread); the main window appears after the splash. */
    static Stage start(StudioApplication app) {
        Stage st = new Stage();
        app.start(st);
        return st;
    }

    /** Closes the main window and stops the application (FX thread). */
    static void stop(StudioApplication app, Stage st) {
        st.close();
        app.stop();
    }
}
