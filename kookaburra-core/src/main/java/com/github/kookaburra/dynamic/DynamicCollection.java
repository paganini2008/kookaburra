package com.github.kookaburra.dynamic;

import com.github.kookaburra.exec.Planner;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import com.github.kookaburra.query.QueryStream;

import java.util.List;

/**
 * Schema-driven access to one stored class and its subclasses, no entity classes needed (design §9.8).
 * Obtain with {@code db.dynamic("Person")}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class DynamicCollection {

    /** What the collection needs from the database. Internal API. */
    public interface Backend {
        Schema schema();

        QueryStream<DynamicObject> query(ClassDef def);

        DynamicObject get(ClassDef def, long oid);

        long[] write(List<Object> inserts, List<Object> updates, List<Object> deletes);
    }

    private final Backend backend;
    private final ClassDef def;

    public DynamicCollection(Backend backend, ClassDef def) {
        this.backend = backend;
        this.def = def;
    }

    /** Fully qualified class name. */
    public String name() {
        return def.name();
    }

    public ClassDef def() {
        return def;
    }

    /** Current stored fields of this class (including inherited ones). */
    public List<FieldDef> fields() {
        return def.current().fields();
    }

    /** Field by dotted path; the {@code @Id} / {@code @Version} names address the OID / version. */
    public DynField field(String path) {
        return DynField.of(backend.schema(), def, path);
    }

    /** Query over this class and its subclasses. */
    public QueryStream<DynamicObject> from() {
        return backend.query(def);
    }

    /** A new, unsaved object of this class. */
    public DynamicObject newObject() {
        return new DynamicObject(def.name());
    }

    public DynamicObject get(long oid) {
        return backend.get(def, oid);
    }

    /** Inserts; sets and returns the OID. */
    public long insert(DynamicObject o) {
        return backend.write(List.of(o), List.of(), List.of())[0];
    }

    /** Stores changes; checks the version if the class has a {@code @Version} field. */
    public void update(DynamicObject o) {
        backend.write(List.of(), List.of(o), List.of());
    }

    /** Deletes; checks the version if the class has a {@code @Version} field. */
    public void delete(DynamicObject o) {
        backend.write(List.of(), List.of(), List.of(o));
    }

    /** Deletes by OID without a version check; returns whether an object was deleted. */
    public boolean delete(long oid) {
        return backend.write(List.of(), List.of(), List.of(oid))[0] == 1;
    }
}
