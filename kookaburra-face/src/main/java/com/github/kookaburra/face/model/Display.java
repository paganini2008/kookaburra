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
            return "bytes[" + b.length + "]";
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
