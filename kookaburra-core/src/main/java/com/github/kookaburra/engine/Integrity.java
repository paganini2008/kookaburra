package com.github.kookaburra.engine;

import com.github.kookaburra.ConstraintViolationException;
import com.github.kookaburra.exec.ColumnIndex;
import com.github.kookaburra.exec.Cond;
import com.github.kookaburra.exec.Executor;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.mapping.FieldDef;
import com.github.kookaburra.mapping.Record;
import com.github.kookaburra.mapping.RecordCodec;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.ValueType;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constraint checks of one batch on the sequencer thread (design §6.5): {@code @Unique} values and
 * {@code @ForeignKey} references against the latest committed state plus the batch's own writes. Deleting a
 * referenced object applies the foreign key's action; cascaded deletes and cleared references are appended to the
 * batch, so they are logged, applied and published with it (one sequence number, atomic).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class Integrity {

    /** The batch after the checks: the caller's ops (possibly with rewritten records), then the cascaded ones. */
    record Result(List<WriteOp> ops, long[] out) {
    }

    /** Final state of an object deleted by the batch. */
    private static final Object DELETED = new Object();

    private final Database db;
    private final Schema schema;
    private final long seq;
    private final List<WriteOp> ops;
    private final List<Long> out = new ArrayList<>();
    /** Final state per OID touched by the batch: its new {@link Record} or {@link #DELETED}. */
    private final Map<Long, Object> finals = new LinkedHashMap<>();
    /** Index in {@link #ops} of the op that produces an OID's final record (to rewrite it for SET_NULL). */
    private final Map<Long, Integer> producer = new HashMap<>();

    private Integrity(Database db, Schema schema, long seq, List<WriteOp> ops, long[] out) {
        this.db = db;
        this.schema = schema;
        this.seq = seq;
        this.ops = new ArrayList<>(ops);
        for (long o : out) {
            this.out.add(o);
        }
    }

    /**
     * Checks the batch (may append cascaded writes); returns it unchanged when the schema has no constraints.
     * {@code vanished}: OIDs a transaction inserted and deleted again (treated as deleted). Sequencer thread only.
     */
    static Result check(Database db, long seq, List<WriteOp> ops, long[] out, long[] vanished) {
        Schema schema = db.registry().schema();
        if (schema.constraints().isEmpty()) {
            return new Result(ops, out);
        }
        Integrity i = new Integrity(db, schema, seq, ops, out);
        i.collect();
        if (vanished != null) {
            for (long oid : vanished) {
                i.finals.putIfAbsent(oid, DELETED);
            }
        }
        i.deletes();
        i.references();
        i.unique();
        return new Result(i.ops, i.out.stream().mapToLong(Long::longValue).toArray());
    }

    private void collect() {
        for (int i = 0; i < ops.size(); i++) {
            WriteOp op = ops.get(i);
            if (op instanceof WriteOp.Insert ins) {
                finals.put(ins.oid(), ins.record());
                producer.put(ins.oid(), i);
            } else if (op instanceof WriteOp.Update u) {
                finals.put(u.oid(), u.record());
                producer.put(u.oid(), i);
            } else if (op instanceof WriteOp.Delete d && out.get(i) == 1) {
                finals.put(d.oid(), DELETED);
                producer.remove(d.oid());
            }
        }
    }

    // ---------------------------------------------------------------- foreign keys: deletes

    /** Applies {@code ON DELETE} for every object the batch deletes, including cascaded ones. */
    private void deletes() {
        List<Schema.Constraint> fks = schema.constraints().stream().filter(Schema.Constraint::foreignKey).toList();
        if (fks.isEmpty()) {
            return;
        }
        Deque<Long> work = new ArrayDeque<>();
        finals.forEach((oid, state) -> {
            if (state == DELETED) {
                work.add(oid);
            }
        });
        while (!work.isEmpty()) {
            long target = work.poll();
            int classId = Oids.classId(target);
            for (Schema.Constraint c : fks) {
                if (!targets(c, classId)) {
                    continue;
                }
                for (long ref : referrers(c, target)) {
                    Object state = finals.get(ref);
                    if (state == DELETED) {
                        continue;
                    }
                    switch (c.onDelete()) {
                        case RESTRICT -> throw new ConstraintViolationException(ConstraintViolationException.Kind.FOREIGN_KEY,
                                c.owner(), c.path(), ref, "cannot delete object " + target + ": object " + ref + " references it through "
                                + c + " (ON DELETE RESTRICT)");
                        case CASCADE -> {
                            ops.add(new WriteOp.Delete(ref, WriteOp.ANY, null, null));
                            out.add(1L);
                            finals.put(ref, DELETED);
                            producer.remove(ref);
                            work.add(ref);
                        }
                        default -> clear(c, ref, target, state);
                    }
                }
            }
        }
    }

    /** Whether a foreign key can point at objects of {@code classId}. */
    private boolean targets(Schema.Constraint c, int classId) {
        String target = c.target();
        return target == null || target.equals(Object.class.getName()) || schema.subclassIds(target).contains(classId);
    }

    /** Live objects whose {@code c.path} references {@code target}: committed ones plus the batch's new records. */
    private Set<Long> referrers(Schema.Constraint c, long target) {
        Set<Integer> types = schema.subclassIds(c.owner());
        TypeDef scalar = c.type().scalarType();
        Cond.Leaf cond = c.type().kind() == ValueType.MULTI ? new Cond.Contains(c.path(), scalar, Set.of(target), false)
                : new Cond.Eq(c.path(), scalar, target);
        Set<Long> found = new LinkedHashSet<>(find(c.collection(), types, cond, target));
        found.removeIf(oid -> finals.containsKey(oid) && !(finals.get(oid) instanceof Record r && refs(r, c).contains(target)));
        finals.forEach((oid, state) -> {
            if (state instanceof Record r && types.contains(r.classId()) && refs(r, c).contains(target)) {
                found.add(oid);
            }
        });
        return found;
    }

    /** SET_NULL: the referrer's record without the reference, as a new op or by rewriting the batch's own op. */
    private void clear(Schema.Constraint c, long ref, long target, Object state) {
        Record base;
        if (state instanceof Record r) {
            base = r;
        } else {
            Database.Location loc = db.locate(ref);
            if (loc == null) {
                return;
            }
            base = loc.view().record(loc.doc());
        }
        Record cleared = without(base, c.path(), target);
        byte[] row = RecordCodec.encode(cleared);
        Integer at = producer.get(ref);
        if (at != null) {
            WriteOp op = ops.get(at);
            ops.set(at, op instanceof WriteOp.Insert ins ? new WriteOp.Insert(ins.type(), ins.entity(), ins.oid(), cleared, row)
                    : new WriteOp.Update(((WriteOp.Update) op).type(), ((WriteOp.Update) op).entity(), ref,
                    ((WriteOp.Update) op).expected(), cleared, row));
        } else {
            Database.Location loc = db.locate(ref);
            ops.add(new WriteOp.Update(null, null, ref, WriteOp.ANY, cleared, row));
            out.add(loc.version() + 1);
            producer.put(ref, ops.size() - 1);
        }
        finals.put(ref, cleared);
    }

    /** A copy of {@code r} where the reference at {@code path} to {@code target} is removed (null / element dropped). */
    static Record without(Record r, String path, long target) {
        Object[] values = r.values().clone();
        strip(values, r.layout().fields(), path, target);
        return new Record(r.classId(), r.layout(), values);
    }

    private static void strip(Object[] values, List<FieldDef> fields, String path, long target) {
        int dot = path.indexOf('.');
        String head = dot < 0 ? path : path.substring(0, dot);
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).name().equals(head) || values[i] == null) {
                continue;
            }
            if (dot >= 0) {
                Object[] nested = ((Object[]) values[i]).clone();
                strip(nested, fields.get(i).type().fields(), path.substring(dot + 1), target);
                values[i] = nested;
            } else if (values[i] instanceof List<?> list) {
                List<Object> kept = new ArrayList<>(list);
                kept.removeIf(v -> v instanceof Long l && l == target);
                values[i] = kept;
            } else if (values[i] instanceof Long l && l == target) {
                values[i] = null;
            }
        }
    }

    /** Referenced OIDs of a record at a foreign-key path (empty when the layout has no such path). */
    private static List<Long> refs(Record r, Schema.Constraint c) {
        Object v = r.get(c.path());
        if (v instanceof Long l) {
            return List.of(l);
        }
        if (v instanceof List<?> list) {
            List<Long> out = new ArrayList<>(list.size());
            for (Object e : list) {
                out.add((Long) e);
            }
            return out;
        }
        return List.of();
    }

    // ---------------------------------------------------------------- foreign keys: inserts / updates

    /** Every reference written by the batch must point at an existing object of the right class. */
    private void references() {
        for (Schema.Constraint c : schema.constraints()) {
            if (!c.foreignKey()) {
                continue;
            }
            Set<Integer> types = schema.subclassIds(c.owner());
            for (Map.Entry<Long, Object> e : finals.entrySet()) {
                if (!(e.getValue() instanceof Record r) || !types.contains(r.classId())) {
                    continue;
                }
                for (long target : refs(r, c)) {
                    Object state = finals.get(target);
                    boolean exists = state instanceof Record || state == null && exists(target);
                    if (!exists || !targets(c, Oids.classId(target))) {
                        throw new ConstraintViolationException(ConstraintViolationException.Kind.FOREIGN_KEY, c.owner(), c.path(),
                                target, "object " + e.getKey() + " references object " + target + " through " + c + ", which "
                                + (exists ? "is not a " + c.target() : "does not exist"));
                    }
                }
            }
        }
    }

    private boolean exists(long oid) {
        return db.locate(oid) != null;                // null for unknown class ids too
    }

    // ---------------------------------------------------------------- unique

    private void unique() {
        for (Schema.Constraint c : schema.constraints()) {
            if (!c.unique()) {
                continue;
            }
            Set<Integer> types = schema.subclassIds(c.owner());
            Map<Object, Long> seen = new HashMap<>();
            for (Map.Entry<Long, Object> e : finals.entrySet()) {
                if (!(e.getValue() instanceof Record r) || !types.contains(r.classId())) {
                    continue;
                }
                Object v = r.get(c.path());
                if (v == null) {
                    continue;
                }
                long oid = e.getKey();
                Long other = seen.putIfAbsent(v, oid);
                if (other != null) {
                    throw duplicate(c, v, other);
                }
                for (long holder : find(c.collection(), types, new Cond.Eq(c.path(), c.type().scalarType(), v), v)) {
                    if (holder != oid && !finals.containsKey(holder)) {
                        throw duplicate(c, v, holder);
                    }
                }
            }
        }
    }

    private static ConstraintViolationException duplicate(Schema.Constraint c, Object v, long holder) {
        return new ConstraintViolationException(ConstraintViolationException.Kind.UNIQUE, c.owner(), c.path(), holder,
                "duplicate value " + (v instanceof String ? "'" + v + "'" : v) + " for unique " + c + " (already used by object "
                        + holder + ")");
    }

    // ---------------------------------------------------------------- lookup

    /**
     * OIDs of the live committed objects whose {@code path} holds {@code key} (latest state, sequencer view).
     * A point lookup: index candidates per segment, each confirmed as the version visible at {@code seq}, so the
     * cost follows the matches, not the collection size. Views without a column for the path are scanned.
     */
    private List<Long> find(String collection, Set<Integer> types, Cond.Leaf cond, Object key) {
        List<Long> oids = new ArrayList<>();
        for (SegmentView v : db.currentVersion().views(collection)) {
            ColumnIndex idx = v.index(cond.path());
            RoaringBitmap docs = idx == null ? null : idx.eq(key, RoaringBitmap.bitmapOfRange(0, v.size()));
            if (docs == null) {
                ReadView one = new ReadViewImpl(new Version(Map.of(collection, List.of(v))), seq);
                new Executor(one, collection, types, cond, null).forEach(h -> {
                    oids.add(h.view().oid(h.doc()));
                    return true;
                });
                continue;
            }
            docs.forEach((int d) -> {
                long oid = v.oid(d);
                if (types.contains(v.classId(d)) && v.find(oid, seq) == d && cond.test(v.value(d, cond.path()))) {
                    oids.add(oid);
                }
            });
        }
        return oids;
    }
}
