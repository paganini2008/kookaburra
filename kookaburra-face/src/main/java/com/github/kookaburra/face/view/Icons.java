package com.github.kookaburra.face.view;

import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Material Design 2 icons (Ikonli).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Icons {

    private Icons() {
    }

    public static FontIcon of(String literal) {
        FontIcon i = new FontIcon(literal);
        i.getStyleClass().add("k-icon");
        return i;
    }
}
