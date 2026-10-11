package com.github.kookaburra.face.model;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Snapshot;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.engine.Database;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.face.StudioPaths;
import com.github.kookaburra.face.demo.DemoData;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.DynamicCodec;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.oql.OqlQuery;
import com.github.kookaburra.query.Row;
import com.github.kookaburra.segment.Segment;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One open database in Studio. All access is schema-driven ({@link DynamicObject}), so any database can be
 * opened without its entity classes. Methods block; the UI calls them on a background thread.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Session implements AutoCloseable {

    private final ObjectDB db;
    private final String title;

    private Session(ObjectDB db, String title) {
        this.db = db;
        this.title = title;
    }

    /** Opens a database directory (created if missing); failures name the directory and the reason. */
    public static Session open(Path dir) {
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new IllegalArgumentException(dir + " is a file, not a directory");
        }
        try {
            return new Session(ObjectDB.open(dir, Config.fromSettings(StudioPaths.settings())), dir.toAbsolutePath().toString());
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String reason = root instanceof AccessDeniedException ? "permission denied" : root.getMessage();
            throw new IllegalStateException(e.getMessage() + (root == e || reason == null ? "" : " (" + reason + ")"), e);
        }
    }

    /** True when {@code dir} exists, holds files and is not a Kookaburra database (opening would create one there). */
    public static boolean isForeignDirectory(Path dir) {
        if (!Files.isDirectory(dir) || StudioPaths.isDatabase(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /** An in-memory database filled with the demo data set. */
    public static Session demo() {
        ObjectDB db = ObjectDB.inMemory(Config.fromSettings(StudioPaths.settings()));
        DemoData.fill(db, StudioPaths.integer(StudioPaths.DEMO_CUSTOMERS), 42);
        db.flush();
        return new Session(db, "demo (in memory)");
    }

    public String title() {
        return title;
    }

    public ObjectDB db() {
        return db;
    }

    public Schema schema() {
        return db.schema();
    }

    /** Snapshot sequence number of the latest committed write. */
    public long seq() {
        try (Snapshot s = db.snapshot()) {
            return s.seq();
        }
    }

    // ---------------------------------------------------------------- browsing

    /** Collection roots with their subclass trees and object counts. */
    public List<ClassInfo> classes() {
        Schema s = schema();
        List<ClassInfo> out = new ArrayList<>();
        for (String root : s.collections()) {
            out.add(info(s, s.byName(root)));
        }
        return out;
    }

    private ClassInfo info(Schema s, ClassDef c) {
        List<ClassInfo> children = new ArrayList<>();
        for (ClassDef x : s.classesOf(c.collection())) {
            if (c.name().equals(x.parent())) {
                children.add(info(s, x));
            }
        }
        return new ClassInfo(c.name(), c.simpleName(), db.dynamic(c.name()).from().count(), children);
    }

    /** Grid columns of a class: OID, version, then stored fields (embedded paths flattened). */
    public List<Column> columns(String className) {
        Schema s = schema();
        ClassDef c = s.byName(className);
        ClassDef root = s.byName(c.collection());
        List<Column> cols = new ArrayList<>();
        cols.add(new Column(root.idField(), ValueType.INT64, true, true));
        if (root.versionField() != null) {
            cols.add(new Column(root.versionField(), ValueType.INT64, true, true));
        }
        flatten(c.current().fields(), "", cols);
        return cols;
    }

    private static void flatten(List<FieldDef> fields, String prefix, List<Column> cols) {
        for (FieldDef f : fields) {
            if (f.type().kind() == ValueType.EMBEDDED) {
                flatten(f.type().fields(), prefix + f.name() + ".", cols);
            } else {
                ValueType t = f.type().kind();
                cols.add(new Column(prefix + f.name(), t, t.isScalar() && t != ValueType.REF, false));
            }
        }
    }

    /**
     * One page of a class (including subclasses).
     *
     * @param where   OQL condition over alias {@code x} (blank = all), e.g. {@code x.age > 30}
     * @param orderBy column path or null
     */
    public Page page(String className, String where, String orderBy, boolean descending, int pageIndex, int pageSize) {
        long start = System.nanoTime();
        String cond = where == null || where.isBlank() ? "" : " WHERE (" + where + ")";   // a condition only: no LIMIT / ORDER BY
        String order = orderBy == null ? "" : " ORDER BY x." + orderBy + (descending ? " DESC" : " ASC");
        String from = " FROM " + className + " x";
        List<DynamicObject> rows = db.oql("SELECT x" + from + cond + order, DynamicObject.class)
                .offset((long) pageIndex * pageSize).limit(pageSize).list();
        long total = (Long) db.oql("SELECT COUNT(*)" + from + cond).list().get(0);
        return new Page(columns(className), rows, total, pageIndex, pageSize, (System.nanoTime() - start) / 1_000_000);
    }

    /** Value of a grid column for an object (OID / version columns included). */
    public Object cell(DynamicObject o, Column c) {
        if (c.system()) {
            ClassDef root = schema().byName(schema().byName(o.className()).collection());
            return c.path().equals(root.idField()) ? o.oid() : o.version();
        }
        return o.get(c.path());
    }

    // ---------------------------------------------------------------- console

    /** Runs an OQL statement unpaged: SELECT → table, EXPLAIN → plan, UPDATE / DELETE → affected count. */
    public QueryOutcome run(String oql) {
        return run(oql, 0, -1);
    }

    /**
     * Runs an OQL statement; a SELECT without its own LIMIT / OFFSET returns the page
     * {@code [offset, offset + pageSize)} when {@code pageSize > 0}.
     */
    public QueryOutcome run(String oql, long offset, int pageSize) {
        long start = System.nanoTime();
        OqlQuery<Object> q = db.oql(oql);
        if (q.isExplain() && !q.isSelect()) {                 // EXPLAIN UPDATE / DELETE: plan only, nothing changes
            return new QueryOutcome(List.of(), List.of(), q.explain(), -1, (System.nanoTime() - start) / 1_000_000);
        }
        if (!q.isSelect()) {
            long n = q.execute();
            return new QueryOutcome(List.of(), List.of(), null, n, (System.nanoTime() - start) / 1_000_000);
        }
        if (q.isExplain()) {
            return new QueryOutcome(List.of(), List.of(), q.explain(), -1, (System.nanoTime() - start) / 1_000_000);
        }
        boolean paged = pageSize > 0 && !q.isSliced();
        if (paged) {
            q.offset(offset).limit(pageSize);
        }
        List<Row> rows = q.rows();
        List<String> cols = rows.isEmpty() ? List.of() : rows.get(0).names();
        List<List<Object>> data = new ArrayList<>();
        if (!rows.isEmpty() && rows.get(0).size() == 1 && rows.get(0).get(0) instanceof DynamicObject) {
            // whole objects (SELECT x / SELECT *): one column per property, union over the classes found
            Map<String, Column> columns = new LinkedHashMap<>();
            for (Row r : rows) {
                for (Column c : columns(((DynamicObject) r.get(0)).className())) {
                    columns.putIfAbsent(c.path(), c);
                }
            }
            cols = new ArrayList<>(columns.keySet());
            for (Row r : rows) {
                DynamicObject o = (DynamicObject) r.get(0);
                List<Object> l = new ArrayList<>();
                for (Column c : columns.values()) {
                    l.add(cell(o, c));
                }
                data.add(l);
            }
        } else {
            for (Row r : rows) {
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < r.size(); i++) {
                    l.add(r.get(i));
                }
                data.add(l);
            }
        }
        return new QueryOutcome(cols, data, null, -1, (System.nanoTime() - start) / 1_000_000,
                paged ? offset : 0, paged ? pageSize : -1);
    }

    public String explain(String oql) {
        return db.oql(oql).explain();
    }

    // ---------------------------------------------------------------- editing

    public DynamicObject newObject(String className) {
        return db.dynamic(className).newObject();
    }

    public DynamicObject reload(DynamicObject o) {
        return db.dynamic(o.className()).get(o.oid());
    }

    /** Checks that every value fits its stored type (throws with the field and value otherwise); nothing is written. */
    public void validate(DynamicObject o) {
        DynamicCodec.toRecord(classDef(o.className()), o);
    }

    /** Inserts a new object or updates an existing one (optimistic lock on the version). */
    public DynamicObject save(DynamicObject o) {
        if (o.oid() == 0) {
            db.dynamic(o.className()).insert(o);
        } else {
            db.dynamic(o.className()).update(o);
        }
        return o;
    }

    /** Deletes objects in one atomic batch (version-checked). */
    public void delete(List<DynamicObject> objects) {
        try (WriteBatch b = db.batch()) {
            objects.forEach(b::delete);
            b.commit();
        }
    }

    /** Metadata of a stored class (fields include inherited ones; embedded paths flattened). */
    public ClassMeta meta(String className) {
        Schema s = schema();
        ClassDef c = s.byName(className);
        ClassDef root = s.byName(c.collection());
        List<String> subs = new ArrayList<>();
        for (Integer id : s.subclassIds(c.name())) {
            if (id != c.id()) {
                subs.add(s.byId(id).simpleName());
            }
        }
        List<ClassMeta.FieldMeta> fields = new ArrayList<>();
        metaFields(s, c, c.current().fields(), "", fields);
        return new ClassMeta(c.name(), c.id(), c.parent(), c.collection(), subs, root.idField(), root.versionField(),
                c.layouts().size(), db.dynamic(c.name()).from().count(), fields);
    }

    private static void metaFields(Schema s, ClassDef c, List<FieldDef> defs, String prefix, List<ClassMeta.FieldMeta> out) {
        for (FieldDef f : defs) {
            if (f.type().kind() == ValueType.EMBEDDED) {
                out.add(new ClassMeta.FieldMeta(prefix + f.name(), f.type().toString(), "", declaring(s, c, prefix, f.name())));
                metaFields(s, c, f.type().fields(), prefix + f.name() + ".", out);
            } else {
                out.add(new ClassMeta.FieldMeta(prefix + f.name(), f.type().toString(), f.index() == null ? "" : f.index().name(),
                        declaring(s, c, prefix, f.name())));
            }
        }
    }

    /** Topmost entity class whose current layout has the (top-level) field. */
    private static String declaring(Schema s, ClassDef c, String prefix, String field) {
        String top = prefix.isEmpty() ? field : prefix.substring(0, prefix.indexOf('.'));
        ClassDef owner = c;
        for (ClassDef p = c; p != null; p = p.parent() == null ? null : s.byName(p.parent())) {
            if (p.current().field(top) != null) {
                owner = p;
            }
        }
        return owner.simpleName();
    }

    public ClassDef classDef(String className) {
        return schema().byName(className);
    }

    // ---------------------------------------------------------------- schema

    /** Stored classes and their layout history (schema versions), as text. */
    public String schemaText() {
        return schema().toString();
    }

    /** Every segment of every collection, newest first, with live counts at the current snapshot. */
    public List<SegmentInfo> segments() {
        List<SegmentInfo> out = new ArrayList<>();
        if (db instanceof Database d) {
            try (Snapshot snap = db.snapshot()) {
                for (String col : schema().collections()) {
                    String name = col.substring(col.lastIndexOf('.') + 1);
                    for (SegmentView v : d.views(col)) {
                        boolean disk = v instanceof Segment;
                        out.add(new SegmentInfo(name, v.label(), disk ? "DISK" : "MEMORY", v.size(),
                                v.live(snap.seq()).getLongCardinality(), v instanceof Segment seg ? seg.fileSize() : 0));
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void close() {
        db.close();
    }
}
