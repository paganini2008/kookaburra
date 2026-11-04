package com.github.kookaburra.mapping;

import java.util.Collection;

/**
 * Conversions to the canonical vector value: a {@code float[]} of finite components. Accepts {@code float[]},
 * {@code double[]}, a collection of numbers (JSON arrays) and the text form {@code [0.1, -2, 3e-4]} (as pgvector).
 *
 * @Author: Fred Feng
 * @Date: 04/11/2026
 * @Version 1.0.0
 */
public final class Vectors {

    private Vectors() {
    }

    /** A new canonical vector of {@code v}, checked against the dimension of {@code t} (0 = any). */
    public static float[] canonical(TypeDef t, Object v, String where) {
        float[] f = of(v, where);
        if (t.dim() > 0 && f.length != t.dim()) {
            throw new IllegalArgumentException(where + ": vector has " + f.length + " dimensions, expected " + t.dim());
        }
        return f;
    }

    /** A new canonical vector of {@code v}: finite components, at least one. */
    public static float[] of(Object v, String where) {
        float[] f;
        if (v instanceof float[] a) {
            f = a.clone();
        } else if (v instanceof double[] a) {
            f = new float[a.length];
            for (int i = 0; i < a.length; i++) {
                f[i] = (float) a[i];
            }
        } else if (v instanceof Collection<?> c) {
            f = new float[c.size()];
            int i = 0;
            for (Object e : c) {
                if (!(e instanceof Number n)) {
                    throw new IllegalArgumentException(where + ": vector component " + e + " is not a number");
                }
                f[i++] = n.floatValue();
            }
        } else if (v instanceof String s) {
            f = parse(s, where);
        } else {
            throw new IllegalArgumentException(where + ": " + v + " (" + v.getClass().getSimpleName() + ") is not a vector");
        }
        if (f.length == 0) {
            throw new IllegalArgumentException(where + ": a vector needs at least one dimension");
        }
        for (float x : f) {
            if (!Float.isFinite(x)) {
                throw new IllegalArgumentException(where + ": vector component " + x + " is not finite");
            }
        }
        return f;
    }

    private static float[] parse(String s, String where) {
        String t = s.strip();
        if (!t.startsWith("[") || !t.endsWith("]")) {
            throw new IllegalArgumentException(where + ": '" + s + "' is not a vector (write it as [1.0, 2.0, …])");
        }
        String body = t.substring(1, t.length() - 1).strip();
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] f = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                f[i] = Float.parseFloat(parts[i].strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(where + ": vector component '" + parts[i].strip() + "' is not a number");
            }
        }
        return f;
    }

    /** Text form {@code [0.1, 0.2]}; at most {@code max} components, then {@code …} and the dimension. */
    public static String format(float[] v, int max) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(v.length, max);
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ", ").append(v[i]);
        }
        if (n < v.length) {
            sb.append(", … (").append(v.length).append(" dims)");
        }
        return sb.append(']').toString();
    }
}
