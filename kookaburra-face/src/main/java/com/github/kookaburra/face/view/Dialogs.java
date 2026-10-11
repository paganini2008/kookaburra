package com.github.kookaburra.face.view;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Standard dialogs (dark theme via the user-agent stylesheet).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Dialogs {

    /** Choices after an optimistic-lock conflict. */
    public enum Conflict { RELOAD, OVERWRITE, CANCEL }

    static final ButtonType RELOAD = new ButtonType("Reload", ButtonBar.ButtonData.LEFT);
    static final ButtonType OVERWRITE = new ButtonType("Overwrite", ButtonBar.ButtonData.OK_DONE);

    private Dialogs() {
    }

    static Alert alert(Window owner, Alert.AlertType type, String title, String message, ButtonType... buttons) {
        Alert a = new Alert(type, message, buttons);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().getStyleClass().add("k-dialog");
        return a;
    }

    public static void error(Window owner, String title, String message) {
        alert(owner, Alert.AlertType.ERROR, title, message, ButtonType.OK).showAndWait();
    }

    public static boolean confirm(Window owner, String title, String message) {
        Optional<ButtonType> r = alert(owner, Alert.AlertType.CONFIRMATION, title, message, ButtonType.OK, ButtonType.CANCEL)
                .showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }

    public static Conflict conflict(Window owner, String message) {
        Optional<ButtonType> r = alert(owner, Alert.AlertType.WARNING, "Version conflict",
                message + "\n\nReload the stored object, or overwrite it with your changes?", RELOAD, OVERWRITE, ButtonType.CANCEL)
                .showAndWait();
        if (r.isEmpty() || r.get() == ButtonType.CANCEL) {
            return Conflict.CANCEL;
        }
        return r.get() == RELOAD ? Conflict.RELOAD : Conflict.OVERWRITE;
    }
}
