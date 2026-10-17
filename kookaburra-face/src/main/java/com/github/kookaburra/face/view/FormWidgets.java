package com.github.kookaburra.face.view;

import com.github.kookaburra.Ref;
import com.github.kookaburra.face.model.Display;
import com.github.kookaburra.mapping.TypeDef;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Value widgets of the object form, per stored value type (design §17.3): check box, enum combo, date picker,
 * text field (expands to a text area for long or multi-line text), binary field (format, size, image preview,
 * load / save / clear), element table for lists of embedded objects and maps.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class FormWidgets {

    /** Texts longer than this, or with line breaks, are edited in a text area. */
    static final int LONG_TEXT = 120;

    /** Largest image decoded for the preview. */
    static final int PREVIEW_MAX_BYTES = 32 << 20;

    /**
     * @param files  picks a file: (save?, suggested name) → file, or null when cancelled
     * @param errors receives readable input errors
     */
    record Context(BiFunction<Boolean, String, File> files, Consumer<String> errors) {
    }

    private FormWidgets() {
    }

    /**
     * The widget of one value.
     *
     * @param inRow true inside a row of an element table: nested lists / maps are kept, not edited
     */
    static Leaf leaf(String path, TypeDef t, boolean inRow, Context ctx) {
        Leaf leaf = switch (t.kind()) {
            case BOOL -> {
                CheckBox c = new CheckBox();
                c.setAllowIndeterminate(true);
                yield new Leaf(c, () -> c.isIndeterminate() ? null : c.isSelected(), v -> {
                    c.setIndeterminate(v == null);
                    c.setSelected(Boolean.TRUE.equals(v));
                });
            }
            case ENUM -> {
                ComboBox<String> c = new ComboBox<>();
                c.getItems().add("");
                c.getItems().addAll(t.constants());
                yield new Leaf(c, () -> c.getValue() == null || c.getValue().isEmpty() ? null : c.getValue(),
                        v -> c.setValue(v == null ? "" : String.valueOf(v)));
            }
            case DATE -> {
                DatePicker c = new DatePicker();
                yield new Leaf(c, c::getValue, v -> c.setValue(v instanceof LocalDate d ? d : v == null ? null : LocalDate.parse(String.valueOf(v))));
            }
            case STRING -> inRow ? text(path, t) : longText(path);
            case BYTES -> binary(path, ctx);
            case EMBEDDED_LIST, MAP -> inRow ? kept(t) : new CollectionEditor(path, t, ctx).leaf();
            case OBJECT -> kept(t);
            default -> text(path, t);
        };
        leaf.input().setId("field-" + path);
        return leaf;
    }

    /** A value that is shown but not edited here (edited in the JSON tab); the stored value is kept. */
    private static Leaf kept(TypeDef t) {
        Label c = new Label();
        c.getStyleClass().add("k-muted");
        Object[] value = {null};
        return new Leaf(c, () -> value[0], v -> {
            value[0] = v;
            c.setText((v == null ? "null" : Display.cell(v)) + "  (edit in the JSON tab)");
        });
    }

    private static Leaf text(String path, TypeDef t) {
        TextField c = new TextField();
        c.setPromptText(hint(t));
        return new Leaf(c, () -> parse(t, c.getText(), path), v -> c.setText(v == null ? "" : v instanceof Ref<?> r ? String.valueOf(r.oid())
                : v instanceof List<?> l ? String.join(", ", l.stream().map(Display::text).toList()) : String.valueOf(v)));
    }

    /** One-line text field; long or multi-line texts (or the expand button) switch to a text area. */
    private static Leaf longText(String path) {
        TextField line = new TextField();
        TextArea area = new TextArea();
        area.setId("field-" + path + "-text");
        area.setWrapText(true);
        area.setPrefRowCount(8);
        Button expand = new Button("⤢");
        expand.setId("field-" + path + "-expand");
        expand.setTooltip(new Tooltip("Edit as multi-line text"));
        Label info = new Label();
        info.getStyleClass().add("k-muted");
        HBox row = new HBox(6, line, expand);
        HBox.setHgrow(line, Priority.ALWAYS);
        VBox box = new VBox(4, row);
        boolean[] long_ = {false};
        Runnable toArea = () -> {
            if (!long_[0]) {
                long_[0] = true;
                area.setText(line.getText());
                box.getChildren().setAll(area, info);
            }
        };
        expand.setOnAction(e -> toArea.run());
        area.textProperty().addListener((o, a, b) -> info.setText(Display.size(b.length(), "chars")));
        return new Leaf(box, line, () -> {
            String s = long_[0] ? area.getText() : line.getText();
            return s == null || s.isEmpty() ? null : s;
        }, v -> {
            String s = v == null ? "" : String.valueOf(v);
            if (s.length() > LONG_TEXT || s.indexOf('\n') >= 0) {
                line.setText(s);
                long_[0] = false;
                toArea.run();
                area.setText(s);
            } else if (long_[0]) {
                area.setText(s);
            } else {
                line.setText(s);
            }
        });
    }

    /** Binary content: format and size, a preview for images, load from / save to a file, clear. */
    private static Leaf binary(String path, Context ctx) {
        byte[][] value = {null};
        Label info = new Label();
        info.setId("field-" + path + "-info");
        ImageView preview = new ImageView();
        preview.setPreserveRatio(true);
        preview.setFitWidth(160);
        preview.setFitHeight(120);
        Button load = new Button("Load…");
        load.setId("field-" + path + "-load");
        Button save = new Button("Save…");
        save.setId("field-" + path + "-save");
        Button clear = new Button("Clear");
        clear.setId("field-" + path + "-clear");
        HBox buttons = new HBox(6, info, load, save, clear);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, buttons);
        Consumer<Object> write = v -> {
            value[0] = (byte[]) v;
            info.setText(v == null ? "null" : Display.text(v));
            save.setDisable(v == null);
            clear.setDisable(v == null);
            Image img = image(value[0]);
            preview.setImage(img);
            if (img == null) {
                box.getChildren().remove(preview);
            } else if (!box.getChildren().contains(preview)) {
                box.getChildren().add(preview);
            }
        };
        load.setOnAction(e -> {
            File f = ctx.files().apply(false, path);
            if (f != null) {
                try {
                    write.accept(Files.readAllBytes(f.toPath()));
                } catch (IOException | UncheckedIOException ex) {
                    ctx.errors().accept(path + ": cannot read " + f + " (" + ex.getMessage() + ")");
                }
            }
        });
        save.setOnAction(e -> {
            String ext = Display.format(value[0]);
            File f = ctx.files().apply(true, path.replace('.', '_') + (ext == null ? ".bin" : "." + ext.toLowerCase()));
            if (f != null) {
                try {
                    Files.write(f.toPath(), value[0]);
                } catch (IOException ex) {
                    ctx.errors().accept(path + ": cannot write " + f + " (" + ex.getMessage() + ")");
                }
            }
        });
        clear.setOnAction(e -> write.accept(null));
        write.accept(null);
        return new Leaf(box, info, () -> value[0], write);
    }

    /** A preview image, or null when the bytes are not a decodable image. */
    static Image image(byte[] b) {
        if (b == null || b.length > PREVIEW_MAX_BYTES || !Display.isImage(b)) {
            return null;
        }
        Image img = new Image(new ByteArrayInputStream(b));
        return img.isError() ? null : img;
    }

    static String hint(TypeDef t) {
        return switch (t.kind()) {
            case INT32, INT64 -> "integer";
            case FLOAT64 -> "number";
            case DECIMAL -> "decimal (exact)";
            case DATETIME -> "yyyy-MM-ddTHH:mm:ss (UTC)";
            case INSTANT -> "yyyy-MM-ddTHH:mm:ssZ";
            case REF -> "OID of " + t.name().substring(t.name().lastIndexOf('.') + 1);
            case MULTI -> "comma-separated " + t.element().kind().name().toLowerCase();
            default -> "";
        };
    }

    static Object parse(TypeDef t, String text, String path) {
        String s = text == null ? "" : text.strip();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return switch (t.kind()) {
                case INT32, INT64 -> Long.parseLong(s);
                case FLOAT64 -> Double.parseDouble(s);
                case DECIMAL -> new BigDecimal(s);
                case REF -> Ref.of(Long.parseLong(s));
                case BOOL -> switch (s.toLowerCase()) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    default -> throw new IllegalArgumentException(path + ": '" + s + "' is not true / false");
                };
                case ENUM -> {
                    if (!t.constants().contains(s)) {
                        throw new IllegalArgumentException(path + ": '" + s + "' is not one of " + t.constants());
                    }
                    yield s;
                }
                case DATE -> LocalDate.parse(s);
                case MULTI -> {
                    List<Object> out = new ArrayList<>();
                    for (String part : s.split(",")) {
                        if (!part.isBlank()) {
                            out.add(parse(t.element(), part, path));
                        }
                    }
                    yield out;
                }
                default -> s;
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + ": '" + s + "' is not a valid " + t.kind().name().toLowerCase());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(path + ": '" + s + "' is not a date (yyyy-MM-dd)");
        }
    }
}
