package com.github.kookaburra.mapping;

import com.github.kookaburra.annotation.OnDelete;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable snapshot of all stored classes (design §3.4). Changes produce a new instance with version + 1.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public final class Schema {

    /** Maximum class id: 16 bits of the OID. */
    public static final int MAX_CLASS_ID = 0xFFFF;

    private final int version;
    private final Map<String, ClassDef> byName;
    private final ClassDef[] byId;
    private final Map<String, Map<String, ColumnDef>> columns = new ConcurrentHashMap<>();
    private volatile List<Constraint> constraints;

    /**
     * A {@code @Unique} or {@code @ForeignKey} field of a stored class.
     *
     * @param owner      topmost class declaring it (the constraint covers its stored subclasses)
     * @param collection collection of the owner
     * @param path       dotted column path
     * @param type       stored type of the path ({@code REF} or {@code MULTI(REF)} for foreign keys)
     * @param unique     values must be unique
     * @param onDelete   foreign-key action, or null
     */
    public record Constraint(String owner, String collection, String path, TypeDef type, boolean unique, OnDelete onDelete) {

        public boolean foreignKey() {
            return onDelete != null;
        }

        /** Referenced class of a foreign key ({@code java.lang.Object} = any entity). */
        public String target() {
            return type.scalarType().name();
        }

        @Override
        public String toString() {
            return owner.substring(owner.lastIndexOf('.') + 1) + "." + path;
        }
    }

    public Schema(int version, Collection<ClassDef> classes) {
        this.version = version;
        Map<String, ClassDef> names = new LinkedHashMap<>();
        int max = 0;
        for (ClassDef c : classes) {
            names.put(c.name(), c);
            max = Math.max(max, c.id());
        }
        this.byName = Collections.unmodifiableMap(names);
        this.byId = new ClassDef[max + 1];
        for (ClassDef c : classes) {
            if (byId[c.id()] != null) {
                throw new SchemaException("duplicate class id " + c.id());
            }
            byId[c.id()] = c;
        }
    }

    public static Schema empty() {
        return new Schema(0, List.of());
    }

    public int version() {
        return version;
    }

    public Collection<ClassDef> classes() {
        return byName.values();
    }

    public ClassDef byName(String name) {
        return byName.get(name);
    }

    public ClassDef byId(int id) {
        ClassDef c = id >= 0 && id < byId.length ? byId[id] : null;
        if (c == null) {
            throw new SchemaException("unknown class id " + id);
        }
        return c;
    }

    public int nextClassId() {
        int next = byId.length == 0 ? 1 : Math.max(1, byId.length);
        if (next > MAX_CLASS_ID) {
            throw new SchemaException("too many classes");
        }
        return next;
    }

    /** Collection root names, sorted. */
    public Set<String> collections() {
        Set<String> s = new TreeSet<>();
        for (ClassDef c : byName.values()) {
            s.add(c.collection());
        }
        return s;
    }

    /** All classes of a collection, root first. */
    public List<ClassDef> classesOf(String collection) {
        List<ClassDef> list = new ArrayList<>();
        for (ClassDef c : byName.values()) {
            if (c.collection().equals(collection)) {
                list.add(c);
            }
        }
        list.sort((a, b) -> Integer.compare(depth(a), depth(b)));
        return list;
    }

    private int depth(ClassDef c) {
        int d = 0;
        for (ClassDef p = c; p.parent() != null; p = byName.get(p.parent())) {
            d++;
        }
        return d;
    }

    /** Ids of {@code className} and all its stored subclasses. */
    public Set<Integer> subclassIds(String className) {
        Set<Integer> ids = new TreeSet<>();
        for (ClassDef c : byName.values()) {
            for (ClassDef p = c; p != null; p = p.parent() == null ? null : byName.get(p.parent())) {
                if (p.name().equals(className)) {
                    ids.add(c.id());
                    break;
                }
            }
        }
        return ids;
    }

    /** Indexed columns of a collection, keyed by path. */
    public Map<String, ColumnDef> columns(String collection) {
        return columns.computeIfAbsent(collection, this::computeColumns);
    }

    private Map<String, ColumnDef> computeColumns(String collection) {
        Map<String, ColumnDef> cols = new LinkedHashMap<>();
        for (ClassDef c : classesOf(collection)) {
            collect(cols, "", c.current().fields(), c.name());
        }
        return Collections.unmodifiableMap(cols);
    }

    private static void collect(Map<String, ColumnDef> cols, String prefix, List<FieldDef> fields, String owner) {
        for (FieldDef f : fields) {
            String path = prefix + f.name();
            if (f.type().kind() == ValueType.EMBEDDED) {
                collect(cols, path + ".", f.type().fields(), owner);
            } else if (f.index() != null) {
                ColumnDef col = new ColumnDef(path, f.type(), f.index());
                ColumnDef prev = cols.putIfAbsent(path, col);
                if (prev != null && (prev.type().kind() != col.type().kind()
                        || prev.scalarType().kind() != col.scalarType().kind())) {
                    throw new SchemaException("Column '" + path + "' has type " + prev.type() + " but " + owner
                            + " declares " + col.type());
                }
            }
        }
    }

    /**
     * A class-level unique constraint over a combination of paths.
     *
     * @param owner      declaring class (the constraint covers its stored subclasses)
     * @param collection collection of the owner
     * @param def        name and paths
     * @param types      stored type of each path (same order as {@code def.paths()})
     */
    public record CompositeUnique(String owner, String collection, UniqueDef def, List<TypeDef> types) {

        /** Path used for index lookups: the first indexed one (the first path is indexed when none was). */
        public String lookupPath(Map<String, ColumnDef> columns) {
            for (String p : def.paths()) {
                if (columns.containsKey(p)) {
                    return p;
                }
            }
            return def.paths().get(0);
        }

        @Override
        public String toString() {
            String cls = owner.substring(owner.lastIndexOf('.') + 1) + "(" + String.join(", ", def.paths()) + ")";
            return def.name().isEmpty() ? cls : def.name() + ": " + cls;
        }
    }

    /** Every class-level unique constraint (combinations of fields). */
    public List<CompositeUnique> compositeUniques() {
        List<CompositeUnique> out = new ArrayList<>();
        for (ClassDef def : byName.values()) {
            for (UniqueDef u : def.uniques()) {
                List<TypeDef> types = new ArrayList<>();
                for (String p : u.paths()) {
                    FieldDef f = fieldAt(def.current().fields(), p);
                    types.add(f == null ? null : f.type());
                }
                out.add(new CompositeUnique(def.name(), def.collection(), u, types));
            }
        }
        return out;
    }

    /** Every constraint of every stored class (current layouts). */
    public List<Constraint> constraints() {
        List<Constraint> c = constraints;
        if (c == null) {
            List<Constraint> out = new ArrayList<>();
            for (ClassDef def : byName.values()) {
                collectConstraints(def, "", def.current().fields(), out);
            }
            c = List.copyOf(out);
            constraints = c;
        }
        return c;
    }

    /**
     * A path holding references: a {@code Ref} field, a collection of refs, or a ref inside embedded objects or
     * embedded lists ({@code lines.product}).
     *
     * @param many   the path yields several references per object (collection or embedded list)
     * @param target referenced class ({@code java.lang.Object} = any)
     */
    public record RefPath(String collection, String path, boolean many, String target) {
    }

    /** Every reference path of every collection (union over the current layouts of its classes). */
    public List<RefPath> refPaths() {
        Map<String, RefPath> out = new LinkedHashMap<>();
        for (ClassDef def : byName.values()) {
            collectRefs(def.collection(), "", def.current().fields(), false, out);
        }
        return List.copyOf(out.values());
    }

    private static void collectRefs(String collection, String prefix, List<FieldDef> fields, boolean many, Map<String, RefPath> out) {
        for (FieldDef f : fields) {
            String path = prefix + f.name();
            TypeDef t = f.type();
            switch (t.kind()) {
                case REF -> out.putIfAbsent(collection + "#" + path, new RefPath(collection, path, many, t.name()));
                case MULTI -> {
                    if (t.element().kind() == ValueType.REF) {
                        out.putIfAbsent(collection + "#" + path, new RefPath(collection, path, true, t.element().name()));
                    }
                }
                case EMBEDDED -> collectRefs(collection, path + ".", t.fields(), many, out);
                case EMBEDDED_LIST -> collectRefs(collection, path + ".", t.element().fields(), true, out);
                default -> {
                }
            }
        }
    }

    /** Constraints whose owner is in {@code collection}. */
    public List<Constraint> constraints(String collection) {
        List<Constraint> out = new ArrayList<>();
        for (Constraint c : constraints()) {
            if (c.collection().equals(collection)) {
                out.add(c);
            }
        }
        return out;
    }

    private void collectConstraints(ClassDef def, String prefix, List<FieldDef> fields, List<Constraint> out) {
        for (FieldDef f : fields) {
            String path = prefix + f.name();
            if (f.type().kind() == ValueType.EMBEDDED) {
                collectConstraints(def, path + ".", f.type().fields(), out);
            } else if ((f.unique() || f.onDelete() != null) && !inheritedConstraint(def, path, f)) {
                out.add(new Constraint(def.name(), def.collection(), path, f.type(), f.unique(), f.onDelete()));
            }
        }
    }

    /** True when an ancestor class declares the same constraint (it already covers {@code def}). */
    private boolean inheritedConstraint(ClassDef def, String path, FieldDef f) {
        for (ClassDef p = def.parent() == null ? null : byName.get(def.parent()); p != null;
             p = p.parent() == null ? null : byName.get(p.parent())) {
            FieldDef pf = fieldAt(p.current().fields(), path);
            if (pf != null && pf.unique() == f.unique() && pf.onDelete() == f.onDelete()) {
                return true;
            }
        }
        return false;
    }

    private static FieldDef fieldAt(List<FieldDef> fields, String path) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        for (FieldDef f : fields) {
            if (f.name().equals(head)) {
                return dot < 0 ? f : f.type().kind() == ValueType.EMBEDDED ? fieldAt(f.type().fields(), path.substring(dot + 1)) : null;
            }
        }
        return null;
    }

    /** Returns a schema with {@code def} added or replaced, version + 1. Validates column consistency. */
    public Schema with(ClassDef def) {
        Map<String, ClassDef> m = new LinkedHashMap<>(byName);
        m.put(def.name(), def);
        Schema s = new Schema(version + 1, m.values());
        s.columns(def.collection());
        return s;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Schema v").append(version).append('\n');
        for (String col : collections()) {
            sb.append("collection ").append(col).append('\n');
            for (ClassDef c : classesOf(col)) {
                sb.append("  class ").append(c.name()).append(" id=").append(c.id())
                        .append(c.parent() == null ? "" : " parent=" + c.parent()).append('\n');
                for (FieldDef f : c.current().fields()) {
                    sb.append("    ").append(f).append('\n');
                }
                for (UniqueDef u : c.uniques()) {
                    sb.append("    ").append(u).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
