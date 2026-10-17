package com.github.kookaburra.engine;

import com.github.kookaburra.BackupInfo;
import com.github.kookaburra.ConflictException;
import com.github.kookaburra.DatabaseStats;
import com.github.kookaburra.EntityListener;
import com.github.kookaburra.Isolation;
import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.Snapshot;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.TransactionTimeoutException;
import com.github.kookaburra.TxFunction;
import com.github.kookaburra.TxOptions;
import com.github.kookaburra.TxWork;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.exec.Cond;
import com.github.kookaburra.exec.Executor;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.EntityType;
import com.github.kookaburra.mapping.Record;
import com.github.kookaburra.mapping.RecordCodec;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.SchemaException;
import com.github.kookaburra.mapping.TypeRegistry;
import com.github.kookaburra.oql.OqlHost;
import com.github.kookaburra.oql.OqlQuery;
import com.github.kookaburra.query.QueryStream;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A transaction (docs/transaction-design.md): private write set, an overlay {@link MemSegment} per collection for
 * read-your-writes, masked snapshot views, optimistic commit through the sequencer. READ COMMITTED takes a fresh
 * snapshot per statement; SERIALIZABLE records what it reads ({@link ReadSet}) for validation at commit.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class TransactionImpl implements Transaction {

    private enum Kind { INSERT, UPDATE, DELETE, INCREMENT }

    /** Buffered state of one object (§9.2). */
    private static final class Entry {
        final long oid;
        final String collection;
        Kind kind;
        /** True if the object does not exist in the snapshot (inserted by this transaction). */
        boolean fresh;
        /** Version visible inside the transaction: the snapshot's (1 for inserts); +1 happens at commit. */
        long version;
        EntityType<?> type;
        Object entity;
        Record record;
        byte[] row;
        Map<String, Number> deltas;
        int overlayRow = -1;
        /** Sequence number of the read the write is based on: a commit after it conflicts. */
        long since;

        Entry(long oid, String collection) {
            this.oid = oid;
            this.collection = collection;
        }

        Entry copy() {
            Entry e = new Entry(oid, collection);
            e.kind = kind;
            e.fresh = fresh;
            e.version = version;
            e.type = type;
            e.entity = entity;
            e.record = record;
            e.row = row;
            e.deltas = deltas == null ? null : new LinkedHashMap<>(deltas);
            e.overlayRow = overlayRow;
            e.since = since;
            return e;
        }

        long bytes() {
            return row == null ? 16 : row.length + 16L;
        }
    }

    /** An object as stored in the snapshot. */
    private record Found(long version, Record record) {
    }

    private final Database db;
    private final TxRegistry.Entry reg;
    private final TxOptions options;
    /** Committed state the transaction reads: fixed for SNAPSHOT / SERIALIZABLE, renewed per statement for READ COMMITTED. */
    private ReadView base;
    private final long snapshot;
    /** SERIALIZABLE: OIDs read by lookup and query conditions. */
    private final Set<Long> readOids = new HashSet<>();
    private final Map<ReadSet.Query, Executor.Materializer> readQueries = new LinkedHashMap<>();
    private final Map<Long, Entry> writes = new LinkedHashMap<>();
    private final Map<String, MemSegment> overlays = new HashMap<>();
    private final Map<String, Set<Long>> hidden = new HashMap<>();
    private final Map<String, List<SegmentView>> viewCache = new HashMap<>();
    private final TxReadView view = new TxReadView();
    private final TxHost host = new TxHost();
    private final TxDatabase facade = new TxDatabase();
    private long bytes;
    private State state = State.ACTIVE;

    TransactionImpl(Database db, TxOptions options) {
        this.db = db;
        this.options = options;
        Duration timeout = options.timeout();
        long timeoutNanos = timeout == null ? db.config().txTimeoutMillis() * 1_000_000L : timeout.toNanos();
        // register before taking the snapshot: every later commit is then kept for this transaction (§11.2)
        this.reg = db.txRegistry().register(timeoutNanos, options.isolation());
        this.base = db.openRead();
        this.snapshot = base.seq();
        reg.snapshot = snapshot;
    }

    // ---------------------------------------------------------------- state

    @Override
    public long id() {
        return reg.id;
    }

    @Override
    public long snapshotSeq() {
        return snapshot;
    }

    @Override
    public Isolation isolation() {
        return options.isolation();
    }

    @Override
    public boolean isReadOnly() {
        return options.isReadOnly();
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public int pendingWrites() {
        return writes.size();
    }

    private void checkActive() {
        if (state != State.ACTIVE) {
            throw new IllegalStateException("transaction " + reg.id + " is " + state.name().toLowerCase().replace('_', ' '));
        }
        if (reg.timedOut || reg.expired(System.nanoTime())) {
            long age = reg.ageMillis();
            finish(State.ROLLED_BACK);
            count(Metrics.TIMEOUT);
            throw new TransactionTimeoutException("transaction " + reg.id + " was open " + age
                    + " ms, longer than its timeout, and has been rolled back");
        }
    }

    /** READ COMMITTED: the next statement reads the latest committed state (plus this transaction's writes). */
    private void statement() {
        if (options.isolation() != Isolation.READ_COMMITTED) {
            return;
        }
        ReadView fresh = db.openRead();
        if (fresh.seq() == base.seq()) {
            fresh.close();
            return;
        }
        base.close();
        base = fresh;
        viewCache.clear();
        long oldest = base.seq();
        for (Entry e : writes.values()) {
            if (!e.fresh) {
                oldest = Math.min(oldest, e.since);
            }
        }
        reg.snapshot = oldest;                         // conflicts after the oldest read still matter
    }

    private boolean serializable() {
        return options.isolation() == Isolation.SERIALIZABLE;
    }

    private void checkWritable() {
        checkActive();
        if (options.isReadOnly()) {
            throw new KookaburraException("transaction " + reg.id + " is read-only");
        }
    }

    // ---------------------------------------------------------------- reads

    @Override
    public <T> T get(Class<T> type, long oid) {
        checkActive();
        statement();
        return db.get(view, type, oid);
    }

    @Override
    public Object get(long oid) {
        checkActive();
        statement();
        return db.getAny(view, oid);
    }

    @Override
    public <T> Map<Long, T> getAll(Class<T> type, Collection<Long> oids) {
        checkActive();
        statement();
        return db.getAll(view, type, oids);
    }

    @Override
    public <T> QueryStream<T> from(Class<T> type) {
        checkActive();
        db.registry().entity(type);
        return new QueryStream<>(host, type, null);
    }

    @Override
    public OqlQuery<Object> oql(String text) {
        checkActive();
        return new OqlQuery<>(host, text, Object.class);
    }

    @Override
    public <T> OqlQuery<T> oql(String text, Class<T> resultType) {
        checkActive();
        return new OqlQuery<>(host, text, resultType);
    }

    @Override
    public DynamicCollection dynamic(String className) {
        checkActive();
        return new DynamicCollection(dynamicBackend, db.storedClass(className));
    }

    @Override
    public List<DynamicObject> referrers(long oid) {
        checkActive();
        statement();
        return db.referrers(view, oid, null);
    }

    @Override
    public <T> List<T> referrers(Class<T> type, long oid) {
        checkActive();
        statement();
        return db.referrers(view, oid, type);
    }

    // ---------------------------------------------------------------- writes

    @Override
    public long insert(Object entity) {
        checkWritable();
        return applyAll(List.of(db.insertOp(entity)))[0];
    }

    @Override
    public void update(Object entity) {
        checkWritable();
        applyAll(List.of(db.updateOp(entity)));
    }

    @Override
    public void delete(Object entity) {
        checkWritable();
        applyAll(List.of(db.deleteOp(entity)));
    }

    @Override
    public boolean delete(Class<?> type, long oid) {
        checkWritable();
        return applyAll(List.of(db.deleteOp(type, oid)))[0] == 1;
    }

    @Override
    public void increment(Class<?> type, long oid, String field, Number delta) {
        checkWritable();
        if (field == null || delta == null) {
            throw new IllegalArgumentException("field and delta must not be null");
        }
        Schema schema = db.registry().schema();
        db.registry().entity(type);
        if (!schema.subclassIds(type.getName()).contains(Oids.classId(oid))) {
            throw new IllegalArgumentException("OID " + oid + " is not a " + type.getName());
        }
        applyAll(List.of(new WriteOp.Increment(oid, Map.of(field, delta))));
    }

    /**
     * Applies writes to the write set all-or-nothing (statement-level atomicity, §6): every op is checked against
     * a staged copy first; only when all succeed do the write set, overlay and hidden sets change.
     * Results: insert → OID, update → visible version, delete → 1 / 0, increment → 0.
     */
    long[] applyAll(List<WriteOp> ops) {
        checkWritable();
        statement();
        Map<Long, Entry> staged = new LinkedHashMap<>();
        long[] out = new long[ops.size()];
        long added = 0;
        for (int i = 0; i < ops.size(); i++) {
            WriteOp op = ops.get(i);
            long oid = oidOf(op);
            Entry cur = staged.containsKey(oid) ? staged.get(oid) : writes.get(oid);
            Entry next;
            if (op instanceof WriteOp.Insert ins) {
                next = inserted(ins);
                out[i] = ins.oid();
            } else if (op instanceof WriteOp.Update u) {
                next = updated(u, cur);
                out[i] = next.version;
            } else if (op instanceof WriteOp.Delete d) {
                next = deleted(d, cur);
                out[i] = next == null ? 0 : 1;
            } else {
                next = incremented((WriteOp.Increment) op, cur);
            }
            if (next != null) {
                added += next.bytes() - (cur == null ? 0 : cur.bytes());
                staged.put(oid, next);
            }
        }
        if (bytes + added > db.config().txMaxBytes()) {
            throw new KookaburraException("transaction " + reg.id + " would buffer " + (bytes + added)
                    + " bytes, more than kookaburra.tx-max-bytes (" + db.config().txMaxBytes() + ")");
        }
        for (Entry e : staged.values()) {
            install(e);
        }
        for (WriteOp op : ops) {
            if (op instanceof WriteOp.Insert ins) {
                assignNew(ins);
            }
        }
        bytes += added;
        reg.writes = writes.size();
        return out;
    }

    private static long oidOf(WriteOp op) {
        if (op instanceof WriteOp.Insert ins) {
            return ins.oid();
        }
        if (op instanceof WriteOp.Update u) {
            return u.oid();
        }
        return op instanceof WriteOp.Delete d ? d.oid() : ((WriteOp.Increment) op).oid();
    }

    private Entry inserted(WriteOp.Insert ins) {
        Entry e = new Entry(ins.oid(), collectionOf(ins.oid()));
        e.kind = Kind.INSERT;
        e.fresh = true;
        e.version = 1;
        e.type = ins.type();
        e.entity = ins.entity();
        e.record = ins.record();
        e.row = ins.row();
        return e;
    }

    private Entry updated(WriteOp.Update u, Entry cur) {
        if (Oids.classId(u.oid()) != u.record().classId()) {
            throw new IllegalArgumentException("OID " + u.oid() + " does not belong to class "
                    + db.registry().schema().byId(u.record().classId()).name());
        }
        Entry e;
        if (cur != null) {
            if (cur.kind == Kind.DELETE) {
                throw new OptimisticLockException(u.oid(), "object " + u.oid() + " was deleted in this transaction");
            }
            e = cur.copy();
            e.kind = cur.kind == Kind.INSERT ? Kind.INSERT : Kind.UPDATE;
        } else {
            Found f = found(u.oid());
            e = new Entry(u.oid(), collectionOf(u.oid()));
            e.kind = Kind.UPDATE;
            e.version = f.version();
            e.since = base.seq();
        }
        stale(u.oid(), u.expected(), e.version);
        e.type = u.type();
        e.entity = u.entity();
        e.record = u.record();
        e.row = u.row();
        e.deltas = null;                       // the entity already carries earlier increments
        return e;
    }

    private Entry deleted(WriteOp.Delete d, Entry cur) {
        if (cur != null && cur.kind == Kind.DELETE || cur == null && find(d.oid()) == null) {
            if (d.expected() != WriteOp.ANY) {
                throw new OptimisticLockException(d.oid(), "object " + d.oid() + " does not exist");
            }
            return null;
        }
        Entry e;
        if (cur != null) {
            e = cur.copy();
        } else {
            e = new Entry(d.oid(), collectionOf(d.oid()));
            e.version = found(d.oid()).version();
            e.since = base.seq();
        }
        stale(d.oid(), d.expected(), e.version);
        e.kind = Kind.DELETE;
        e.type = d.type();
        e.entity = d.entity();
        e.record = null;
        e.row = null;
        e.deltas = null;
        return e;
    }

    private Entry incremented(WriteOp.Increment inc, Entry cur) {
        Entry e;
        if (cur != null) {
            if (cur.kind == Kind.DELETE) {
                throw new OptimisticLockException(inc.oid(), "object " + inc.oid() + " was deleted in this transaction");
            }
            e = cur.copy();
        } else {
            Found f = found(inc.oid());
            e = new Entry(inc.oid(), collectionOf(inc.oid()));
            e.kind = Kind.INCREMENT;
            e.version = f.version();
            e.since = base.seq();
            e.record = f.record();
            e.deltas = new LinkedHashMap<>();
        }
        e.record = Increments.apply(e.record, inc.deltas());
        e.row = RecordCodec.encode(e.record);
        if (e.kind == Kind.INCREMENT) {
            inc.deltas().forEach((path, delta) -> e.deltas.merge(path, delta, Increments::plus));
        }
        return e;
    }

    private static void stale(long oid, long expected, long visible) {
        if (expected != WriteOp.ANY && expected != visible) {
            throw new OptimisticLockException(oid, "object " + oid + " has version " + visible
                    + " in this transaction, the entity has version " + expected);
        }
    }

    /** Commits a staged entry to the write set, the overlay and the hidden set (cannot fail). */
    private void install(Entry e) {
        Entry old = writes.put(e.oid, e);
        MemSegment overlay = overlays.computeIfAbsent(e.collection, c -> {
            viewCache.remove(c);
            return new MemSegment(0, db.registry().schema().columns(c));
        });
        if (old != null && old.overlayRow >= 0) {
            overlay.markDeleted(old.overlayRow, 0);
        }
        e.overlayRow = e.kind == Kind.DELETE ? -1 : overlay.append(e.oid, e.version, 0, e.record, e.row);
        if (!e.fresh && hidden.computeIfAbsent(e.collection, c -> new HashSet<>()).add(e.oid)) {
            viewCache.remove(e.collection);
        }
    }

    private static void assignNew(WriteOp.Insert ins) {
        if (ins.entity() instanceof DynamicObject d) {
            d.assign(ins.oid(), 1);
        } else {
            @SuppressWarnings("unchecked")
            EntityType<Object> t = (EntityType<Object>) ins.type();
            t.setId(ins.entity(), ins.oid());
            t.setVersion(ins.entity(), 1);
        }
    }

    private String collectionOf(long oid) {
        return db.registry().schema().byId(Oids.classId(oid)).collection();
    }

    /** The object in the snapshot, or an exception as for an autocommit update of a missing object. */
    private Found found(long oid) {
        Found f = find(oid);
        if (f == null) {
            throw new OptimisticLockException(oid, "object " + oid + " does not exist");
        }
        return f;
    }

    private Found find(long oid) {
        String collection;
        try {
            collection = collectionOf(oid);
        } catch (SchemaException e) {
            return null;
        }
        for (SegmentView v : base.views(collection)) {
            int d = v.find(oid, base.seq());
            if (d == SegmentView.DELETED) {
                return null;
            }
            if (d >= 0) {
                return new Found(v.version(d), v.record(d));
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- commit / rollback

    @Override
    public void commit() {
        checkActive();
        if (reg.abortReason != null) {
            String reason = reg.abortReason;
            boolean timedOut = reg.timedOut;
            finish(State.ROLLED_BACK);
            count(timedOut ? Metrics.TIMEOUT : Metrics.CONFLICT);
            throw timedOut ? new TransactionTimeoutException(reason) : new ConflictException(reason);
        }
        List<WriteOp> ops = new ArrayList<>();
        List<Long> check = new ArrayList<>();
        List<Long> checkSeqs = new ArrayList<>();
        List<Long> vanished = new ArrayList<>();
        for (Entry e : writes.values()) {
            switch (e.kind) {
                case INSERT -> ops.add(new WriteOp.Insert(e.type, e.entity, e.oid, e.record, e.row));
                case UPDATE -> {
                    ops.add(new WriteOp.Update(e.type, e.entity, e.oid, WriteOp.ANY, e.record, e.row));
                    check.add(e.oid);
                    checkSeqs.add(e.since);
                }
                case DELETE -> {
                    if (!e.fresh) {
                        ops.add(new WriteOp.Delete(e.oid, WriteOp.ANY, e.type, e.entity));
                        check.add(e.oid);
                        checkSeqs.add(e.since);
                    } else {
                        vanished.add(e.oid);
                    }
                }
                case INCREMENT -> ops.add(new WriteOp.Increment(e.oid, Map.copyOf(e.deltas)));
            }
        }
        if (ops.isEmpty()) {
            finish(State.COMMITTED);
            return;
        }
        try {
            ReadSet reads = serializable() ? new ReadSet(Set.copyOf(readOids), new LinkedHashMap<>(readQueries))
                    : null;
            long[] res = db.commitTransaction(ops, new Sequencer.TxCommit(reg, snapshot, longs(check), longs(checkSeqs),
                    longs(vanished), reads));
            db.writeBack(ops, res);
            finish(State.COMMITTED);
            db.firePost(ops, res);
        } catch (RuntimeException e) {
            finish(State.ROLLED_BACK);
            if (e instanceof TransactionTimeoutException) {
                count(Metrics.TIMEOUT);
            } else if (e instanceof ConflictException) {
                count(Metrics.CONFLICT);
            }
            throw e;
        }
    }

    private void count(int outcome) {
        db.metrics.tx(options.isolation(), outcome);
    }

    private static long[] longs(List<Long> list) {
        return list.stream().mapToLong(Long::longValue).toArray();
    }

    @Override
    public void rollback() {
        if (state == State.ACTIVE) {
            finish(State.ROLLED_BACK);
        }
    }

    @Override
    public void close() {
        rollback();
    }

    private void finish(State end) {
        state = end;
        count(end == State.COMMITTED ? Metrics.COMMITTED : Metrics.ROLLED_BACK);
        db.txRegistry().deregister(reg);
        base.close();
        writes.clear();
        overlays.clear();
        hidden.clear();
        viewCache.clear();
        readOids.clear();
        readQueries.clear();
    }

    /** The {@link ObjectDB} that queries and OQL inside this transaction write through. */
    ObjectDB database() {
        return facade;
    }

    @Override
    public String toString() {
        return "Transaction#" + reg.id + " " + options + " @" + snapshot + " " + state + " writes=" + writes.size();
    }

    // ---------------------------------------------------------------- read view = overlay + masked snapshot

    /** The snapshot plus this transaction's writes (§10). */
    private final class TxReadView implements ReadView {

        @Override
        public long seq() {
            return base.seq();
        }

        @Override
        public List<SegmentView> views(String collection) {
            if (serializable()) {
                readQueries.putIfAbsent(new ReadSet.Query(collection, null, new Cond.Const(true)), null);   // whole collection
            }
            return cached(collection);
        }

        @Override
        public List<SegmentView> queryViews(String collection, Set<Integer> types, Cond cond, Executor.Materializer materializer) {
            if (serializable()) {
                readQueries.putIfAbsent(new ReadSet.Query(collection, types, cond), materializer);
            }
            return cached(collection);
        }

        @Override
        public List<SegmentView> pointViews(String collection, long oid) {
            if (serializable()) {
                readOids.add(oid);
            }
            return cached(collection);
        }

        private List<SegmentView> cached(String collection) {
            return viewCache.computeIfAbsent(collection, c -> {
                List<SegmentView> out = new ArrayList<>();
                MemSegment overlay = overlays.get(c);
                if (overlay != null) {
                    out.add(overlay);
                }
                Set<Long> h = hidden.get(c);
                Set<Long> fixed = h == null || h.isEmpty() ? null : Set.copyOf(h);
                for (SegmentView v : base.views(c)) {
                    out.add(fixed == null ? v : new MaskedView(v, fixed, base.seq()));
                }
                return List.copyOf(out);
            });
        }

        @Override
        public void close() {
            // owned by the transaction
        }
    }

    /** Query / OQL host: reads through the transaction's view, writes back into the transaction. */
    private final class TxHost implements OqlHost {

        @Override
        public TypeRegistry registry() {
            return db.registry();
        }

        @Override
        public ReadView openRead() {
            checkActive();
            statement();
            return view;
        }

        @Override
        public Object materialize(SegmentView v, int doc) {
            return db.materialize(v, doc);
        }

        @Override
        public DynamicObject materializeDynamic(SegmentView v, int doc) {
            return db.materializeDynamic(v, doc);
        }

        @Override
        public Map<Long, Object> getAll(ReadView v, Collection<Long> oids) {
            return db.getAll(v, oids);
        }

        @Override
        public ObjectDB database() {
            return facade;
        }

        @Override
        public ClassDef storedClass(String name) {
            return db.storedClass(name);
        }

        @Override
        public DynamicObject getDynamic(ReadView v, long oid) {
            return db.getDynamic(v, oid);
        }

        @Override
        public Object getTyped(ReadView v, long oid) {
            return db.getTyped(v, oid);
        }
    }

    private final DynamicCollection.Backend dynamicBackend = new DynamicCollection.Backend() {
        @Override
        public Schema schema() {
            return db.registry().schema();
        }

        @Override
        public QueryStream<DynamicObject> query(ClassDef def) {
            checkActive();
            return new QueryStream<>(host, QueryStream.Target.dynamic(def.name()), null);
        }

        @Override
        public DynamicObject get(ClassDef def, long oid) {
            checkActive();
            statement();
            if (!db.registry().schema().subclassIds(def.name()).contains(Oids.classId(oid))) {
                return null;
            }
            return db.getDynamic(view, oid);
        }

        @Override
        public long[] write(List<Object> inserts, List<Object> updates, List<Object> deletes) {
            checkWritable();
            List<WriteOp> ops = new ArrayList<>();
            inserts.forEach(o -> ops.add(db.insertOp(o)));
            updates.forEach(o -> ops.add(db.updateOp(o)));
            deletes.forEach(o -> ops.add(db.deleteOp(o)));
            return applyAll(ops);
        }
    };

    /** {@link ObjectDB} seen by queries and OQL inside the transaction (bulk writes land in the transaction). */
    private final class TxDatabase implements ObjectDB {

        @Override
        public long insert(Object entity) {
            return TransactionImpl.this.insert(entity);
        }

        @Override
        public void update(Object entity) {
            TransactionImpl.this.update(entity);
        }

        @Override
        public void delete(Object entity) {
            TransactionImpl.this.delete(entity);
        }

        @Override
        public boolean delete(Class<?> type, long oid) {
            return TransactionImpl.this.delete(type, oid);
        }

        @Override
        public WriteBatch batch() {
            checkWritable();
            return new TxBatch();
        }

        @Override
        public void increment(Class<?> type, long oid, String field, Number delta) {
            TransactionImpl.this.increment(type, oid, field, delta);
        }

        @Override
        public Transaction begin() {
            return db.begin();
        }

        @Override
        public Transaction begin(Isolation isolation) {
            return db.begin(isolation);
        }

        @Override
        public Transaction begin(TxOptions o) {
            return db.begin(o);
        }

        @Override
        public void inTransaction(TxWork work) {
            db.inTransaction(work);
        }

        @Override
        public void inTransaction(TxWork work, int retries) {
            db.inTransaction(work, retries);
        }

        @Override
        public <R> R inTransactionResult(TxFunction<R> work) {
            return db.inTransactionResult(work);
        }

        @Override
        public <R> R inTransactionResult(TxFunction<R> work, int retries) {
            return db.inTransactionResult(work, retries);
        }

        @Override
        public void inTransaction(TxOptions o, TxWork work) {
            db.inTransaction(o, work);
        }

        @Override
        public <R> R inTransactionResult(TxOptions o, TxFunction<R> work) {
            return db.inTransactionResult(o, work);
        }

        @Override
        public int activeTransactions() {
            return db.activeTransactions();
        }

        @Override
        public <T> void addListener(Class<T> type, EntityListener<? super T> listener) {
            db.addListener(type, listener);
        }

        @Override
        public <T> boolean removeListener(Class<T> type, EntityListener<? super T> listener) {
            return db.removeListener(type, listener);
        }

        @Override
        public Object get(long oid) {
            return TransactionImpl.this.get(oid);
        }

        @Override
        public <T> T get(Class<T> type, long oid) {
            return TransactionImpl.this.get(type, oid);
        }

        @Override
        public <T> Map<Long, T> getAll(Class<T> type, Collection<Long> oids) {
            return TransactionImpl.this.getAll(type, oids);
        }

        @Override
        public <T> QueryStream<T> from(Class<T> type) {
            return TransactionImpl.this.from(type);
        }

        @Override
        public List<DynamicObject> referrers(long oid) {
            return TransactionImpl.this.referrers(oid);
        }

        @Override
        public <T> List<T> referrers(Class<T> type, long oid) {
            return TransactionImpl.this.referrers(type, oid);
        }

        @Override
        public Snapshot snapshot() {
            throw new UnsupportedOperationException("inside a transaction: the transaction already reads a snapshot");
        }

        @Override
        public OqlQuery<Object> oql(String text) {
            return TransactionImpl.this.oql(text);
        }

        @Override
        public <T> OqlQuery<T> oql(String text, Class<T> resultType) {
            return TransactionImpl.this.oql(text, resultType);
        }

        @Override
        public DynamicCollection dynamic(String className) {
            return TransactionImpl.this.dynamic(className);
        }

        @Override
        public void flush() {
            db.flush();
        }

        @Override
        public BackupInfo backup(Path target) {
            return db.backup(target);
        }

        @Override
        public DatabaseStats stats() {
            return db.stats();
        }

        @Override
        public void compact() {
            db.compact();
        }

        @Override
        public Schema schema() {
            return db.schema();
        }

        @Override
        public Path path() {
            return db.path();
        }

        @Override
        public void close() {
            // the database is owned by the caller of begin()
        }
    }

    /** A {@link WriteBatch} inside the transaction: applied to the write set all-or-nothing on commit. */
    private final class TxBatch implements WriteBatch {
        private final List<WriteOp> ops = new ArrayList<>();

        @Override
        public WriteBatch insert(Object entity) {
            ops.add(db.insertOp(entity));
            return this;
        }

        @Override
        public WriteBatch update(Object entity) {
            ops.add(db.updateOp(entity));
            return this;
        }

        @Override
        public WriteBatch delete(Object entity) {
            ops.add(db.deleteOp(entity));
            return this;
        }

        @Override
        public WriteBatch delete(Class<?> type, long oid) {
            ops.add(db.deleteOp(type, oid));
            return this;
        }

        @Override
        public WriteBatch delete(long oid) {
            ops.add(db.deleteOp(oid));
            return this;
        }

        @Override
        public int size() {
            return ops.size();
        }

        @Override
        public void commit() {
            applyAll(ops);
            ops.clear();
        }

        @Override
        public void close() {
            ops.clear();
        }
    }
}
