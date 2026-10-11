package com.github.kookaburra.face.view;

import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;

import java.util.function.LongSupplier;

/**
 * Leading "#" column numbering the rows of a paged grid from {@code offset + 1}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class RowNumbers {

    private RowNumbers() {
    }

    static <S> TableColumn<S, Void> column(LongSupplier offset) {
        TableColumn<S, Void> c = new TableColumn<>("#");
        c.setId("row-number");
        c.setSortable(false);
        c.setReorderable(false);
        c.setPrefWidth(56);
        c.getStyleClass().add("k-rownum-col");
        c.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || getTableRow() == null || getTableRow().getItem() == null
                        ? null : String.valueOf(offset.getAsLong() + getIndex() + 1));
            }
        });
        return c;
    }
}
