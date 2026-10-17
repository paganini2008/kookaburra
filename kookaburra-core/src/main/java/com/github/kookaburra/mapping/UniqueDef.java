package com.github.kookaburra.mapping;

import java.util.List;
import java.util.Objects;

/**
 * A class-level unique constraint over a combination of field paths (design §3.6).
 *
 * @param name  constraint name, or empty for the default {@code Class(path, path)}
 * @param paths dotted field paths, in declaration order (at least one)
 *
 * @Author: Fred Feng
 * @Date: 17/10/2026
 * @Version 1.0.0
 */
public record UniqueDef(String name, List<String> paths) {

    public UniqueDef {
        name = name == null ? "" : name;
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("a unique constraint needs at least one field");
        }
    }

    @Override
    public String toString() {
        return (name.isEmpty() ? "" : name + " ") + "UNIQUE (" + String.join(", ", paths) + ")";
    }
}
