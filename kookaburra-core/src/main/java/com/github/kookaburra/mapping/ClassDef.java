package com.github.kookaburra.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A stored entity class.
 *
 * @param id           class id (high bits of OIDs)
 * @param name         fully qualified Java class name
 * @param parent       name of the parent entity class, or null for a collection root
 * @param collection   name of the collection root class
 * @param idField      Java name of the {@code @Id} field
 * @param versionField Java name of the {@code @Version} field, or null
 * @param layouts      all layouts, oldest first; the last one is current
 * @param uniques      class-level unique constraints over field combinations (declared on this class)
 * @param formerNames  earlier class names ({@code @Renamed}); the collection keeps the name it was created with
 * @param comment      {@code @Comment} text, or null
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record ClassDef(int id, String name, String parent, String collection, String idField,
                       String versionField, List<Layout> layouts, List<UniqueDef> uniques, List<String> formerNames,
                       String comment) {

    public ClassDef {
        Objects.requireNonNull(name, "name");
        layouts = List.copyOf(layouts);
        uniques = uniques == null ? List.of() : List.copyOf(uniques);
        formerNames = formerNames == null ? List.of() : List.copyOf(formerNames);
        if (layouts.isEmpty()) {
            throw new IllegalArgumentException("no layout");
        }
    }

    /** A class without a comment. */
    public ClassDef(int id, String name, String parent, String collection, String idField, String versionField,
                    List<Layout> layouts, List<UniqueDef> uniques, List<String> formerNames) {
        this(id, name, parent, collection, idField, versionField, layouts, uniques, formerNames, null);
    }

    /** The same class with another comment. */
    ClassDef withComment(String c) {
        return new ClassDef(id, name, parent, collection, idField, versionField, layouts, uniques, formerNames, c);
    }

    /** A class that was never renamed. */
    public ClassDef(int id, String name, String parent, String collection, String idField, String versionField,
                    List<Layout> layouts, List<UniqueDef> uniques) {
        this(id, name, parent, collection, idField, versionField, layouts, uniques, List.of());
    }

    /** A class without class-level unique constraints. */
    public ClassDef(int id, String name, String parent, String collection, String idField, String versionField,
                    List<Layout> layouts) {
        this(id, name, parent, collection, idField, versionField, layouts, List.of());
    }

    /** The same class with other class-level unique constraints. */
    ClassDef withUniques(List<UniqueDef> u) {
        return new ClassDef(id, name, parent, collection, idField, versionField, layouts, u, formerNames, comment);
    }

    public Layout current() {
        return layouts.get(layouts.size() - 1);
    }

    public Layout layout(int layoutId) {
        Layout l = layouts.get(layoutId);
        if (l.id() != layoutId) {
            throw new IllegalStateException("layout ids out of order in " + name);
        }
        return l;
    }

    public boolean isRoot() {
        return parent == null;
    }

    public String simpleName() {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    ClassDef withLayout(List<FieldDef> fields) {
        List<Layout> l = new ArrayList<>(layouts);
        l.add(new Layout(layouts.size(), fields));
        return new ClassDef(id, name, parent, collection, idField, versionField, l, uniques, formerNames, comment);
    }

    /** This class renamed to {@code newName}: the old name becomes a former name; id, collection and layouts stay. */
    ClassDef renamedTo(String newName) {
        List<String> former = new ArrayList<>(formerNames);
        former.add(name);
        return new ClassDef(id, newName, parent, collection, idField, versionField, layouts, uniques, former, comment);
    }

    /** The same class with other layouts (fields renamed in all of them). */
    ClassDef withLayouts(List<Layout> l) {
        return new ClassDef(id, name, parent, collection, idField, versionField, l, uniques, formerNames, comment);
    }
}
