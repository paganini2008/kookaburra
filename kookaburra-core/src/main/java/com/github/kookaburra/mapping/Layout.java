package com.github.kookaburra.mapping;

import java.util.List;

/**
 * One version of a class's stored field list (superclass fields first, {@code @Id} / {@code @Version} excluded).
 * Rows record the layout they were written with, so old rows stay readable after schema changes.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record Layout(int id, List<FieldDef> fields) {

    public Layout {
        fields = List.copyOf(fields);
    }

    public int indexOf(String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    public FieldDef field(String name) {
        int i = indexOf(name);
        return i < 0 ? null : fields.get(i);
    }
}
