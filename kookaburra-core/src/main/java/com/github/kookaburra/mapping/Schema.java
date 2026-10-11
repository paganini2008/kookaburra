package com.github.kookaburra.mapping;

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
            }
        }
        return sb.toString();
    }
}
