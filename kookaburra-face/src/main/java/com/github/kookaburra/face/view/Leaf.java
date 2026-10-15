package com.github.kookaburra.face.view;

import javafx.scene.Node;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One value widget of the object form: the node placed in the layout, the control tests and tools address by
 * id (often the same node), and how the value is read and written.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
record Leaf(Node node, Node input, Supplier<Object> read, Consumer<Object> write) {

    Leaf(Node node, Supplier<Object> read, Consumer<Object> write) {
        this(node, node, read, write);
    }
}
