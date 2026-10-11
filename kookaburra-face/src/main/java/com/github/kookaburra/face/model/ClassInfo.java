package com.github.kookaburra.face.model;

import java.util.List;

/**
 * A stored class in the collections tree: its object count includes subclasses.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record ClassInfo(String name, String simpleName, long count, List<ClassInfo> children) {

    @Override
    public String toString() {
        return simpleName;
    }
}
