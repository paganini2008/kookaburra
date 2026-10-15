package com.github.kookaburra.face.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.dynamic.DynamicObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Cell texts for grids (UTC for instants, design §11).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Display {

    private Display() {
    }

    /**
     * Grid cell text: like {@link #text} but nested documents are summarised the way document-database tools
     * (MongoDB Compass, Studio 3T) do — {@code [ 2 elements ]} for a list of embedded objects, {@code { 3 entries }}
     * for a map; the full value is in the tooltip ({@link #pretty}) and the inspector tree.
     */
    public static String cell(Object v) {
        if (v instanceof String str && str.length() > CELL_MAX) {
            return oneLine(str.substring(0, CELL_MAX)) + "…";
        }
        if (v instanceof List<?> l && l.stream().anyMatch(x -> x instanceof Map<?, ?>)) {
            return "[ " + l.size() + (l.size() == 1 ? " element ]" : " elements ]");
        }
        if (v instanceof Map<?, ?> m) {
            return "{ " + m.size() + (m.size() == 1 ? " entry }" : " entries }");
        }
        return text(v);
    }

    /** True when {@link #cell} shortens the value (it is worth a tooltip). */
    public static boolean nested(Object v) {
        return v instanceof Map<?, ?> || v instanceof List<?> l && l.stream().anyMatch(x -> x instanceof Map<?, ?>)
                || v instanceof String str && str.length() > CELL_MAX;
    }

    /** Longest text shown in a grid cell. */
    public static final int CELL_MAX = 200;

    /** Longest text in a tooltip. */
    public static final int TOOLTIP_MAX = 2000;

    /** Tooltip of a cell: indented JSON of nested values, the start of long texts. */
    public static String tooltip(Object v) {
        String t = v instanceof String str ? str : pretty(v);
        return t.length() > TOOLTIP_MAX ? t.substring(0, TOOLTIP_MAX) + "\n… (" + size(t.length(), "chars") + ")" : t;
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    /** {@code 1,234 chars} / {@code 5.0 MiB}. */
    public static String size(long n, String unit) {
        if (!unit.equals("bytes")) {
            return String.format(Locale.ROOT, "%,d %s", n, unit);
        }
        if (n < 1024) {
            return n + " B";
        }
        return n < 1 << 20 ? String.format(Locale.ROOT, "%.1f KiB", n / 1024.0) : String.format(Locale.ROOT, "%.1f MiB", n / 1048576.0);
    }

    /** Detected format of binary content (by magic number): PNG, JPEG, GIF, BMP, WEBP, PDF, ZIP, GZIP, or null. */
    public static String format(byte[] b) {
        if (starts(b, 0x89, 'P', 'N', 'G')) {
            return "PNG";
        }
        if (starts(b, 0xFF, 0xD8, 0xFF)) {
            return "JPEG";
        }
        if (starts(b, 'G', 'I', 'F', '8')) {
            return "GIF";
        }
        if (starts(b, 'B', 'M')) {
            return "BMP";
        }
        if (starts(b, 'R', 'I', 'F', 'F') && b.length >= 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "WEBP";
        }
        if (starts(b, '%', 'P', 'D', 'F')) {
            return "PDF";
        }
        if (starts(b, 'P', 'K', 3, 4)) {
            return "ZIP";
        }
        if (starts(b, 0x1F, 0x8B)) {
            return "GZIP";
        }
        return null;
    }

    /** True for formats JavaFX can show as an image. */
    public static boolean isImage(byte[] b) {
        String f = format(b);
        return f != null && (f.equals("PNG") || f.equals("JPEG") || f.equals("GIF") || f.equals("BMP"));
    }

    private static boolean starts(byte[] b, int... magic) {
        if (b.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((b[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** Indented JSON of a value (refs as OIDs, temporals as ISO text). */
    public static String pretty(Object v) {
        return Json.write(ObjectJson.jsonValue(v));
    }

    public static String text(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Ref<?> r) {
            return "→ " + r.oid();
        }
        if (v instanceof Instant i) {
            return i + " UTC";
        }
        if (v instanceof byte[] b) {
            String f = format(b);
            return (f == null ? "Binary" : f) + " · " + size(b.length, "bytes");
        }
        if (v instanceof DynamicObject d) {
            return d.className().substring(d.className().lastIndexOf('.') + 1) + "#" + d.oid();
        }
        if (v instanceof Map<?, ?> m) {
            return m.entrySet().stream().map(e -> e.getKey() + "=" + text(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (v instanceof List<?> l) {
            return l.stream().map(Display::text).collect(Collectors.joining(", ", "[", "]"));
        }
        if (v instanceof Double d && !d.isNaN() && !d.isInfinite()) {
            if (d == Math.rint(d)) {
                return String.format(Locale.ROOT, "%.1f", d);
            }
            return BigDecimal.valueOf(d).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(v);
    }
}
