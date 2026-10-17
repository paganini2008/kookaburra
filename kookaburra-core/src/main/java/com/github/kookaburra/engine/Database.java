package com.github.kookaburra.engine;

import com.github.kookaburra.BackupInfo;
import com.github.kookaburra.Config;
import com.github.kookaburra.ConflictException;
import com.github.kookaburra.Isolation;
import com.github.kookaburra.Durability;
import com.github.kookaburra.EntityListener;
import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.LifecycleEvent;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Snapshot;
import com.github.kookaburra.Transaction;
import com.github.kookaburra.TxFunction;
import com.github.kookaburra.TxOptions;
import com.github.kookaburra.TxWork;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.exec.Cond;
import com.github.kookaburra.exec.Executor;
import com.github.kookaburra.exec.QueryHost;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.mapping.ClassDef;
import com.github.kookaburra.mapping.DynamicCodec;
import com.github.kookaburra.mapping.EntityType;
import com.github.kookaburra.mapping.Record;
import com.github.kookaburra.mapping.RecordCodec;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.SchemaException;
import com.github.kookaburra.mapping.TypeDef;
import com.github.kookaburra.mapping.TypeRegistry;
import com.github.kookaburra.oql.OqlHost;
import com.github.kookaburra.oql.OqlQuery;
import com.github.kookaburra.query.QueryStream;
import com.github.kookaburra.segment.Segment;
import com.github.kookaburra.segment.SegmentDeletes;
import com.github.kookaburra.segment.SegmentWriter;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The database implementation: owns the schema registry, the current {@link Version}, the published
 * sequence number and the {@link Sequencer}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Database implements ObjectDB, OqlHost {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    /** Where the latest version of an object lives (sequencer's view). */
    record Location(SegmentView view, int doc, long version) {
    }

    /** A frozen MemSegment waiting to become a segment. */
    private record FlushJob(String collection, MemSegment mem, long seq, CompletableFuture<Void> done) {
    }

    /** Adjacent segments (oldest first) to merge as of {@code seq}. */
    private record CompactJob(String collection, List<Segment> inputs, long seq, CompletableFuture<Void> done) {
    }

    private final Path dir;
    private final Config config;
    private final Storage storage;
    private final TypeRegistry registry;
    private final Sequencer sequencer;
    /** Sequencer-owned: per segment id {persisted delete generation, logged deletes persisted}. */
    private final Map<Integer, int[]> deleteState = new HashMap<>();
    /** Sequencer-owned: last written commit. */
    private Commit commit;
    /** Sequencer-owned: segments currently being merged. */
    private final Set<Segment> compacting = new HashSet<>();
    /** Frozen MemSegments whose flush has not been installed yet. Sequencer thread only. */
    private final Set<FlushJob> flushing = new HashSet<>();
    /** Merges scheduled and not yet installed. Sequencer thread only. */
    private final Set<CompactJob> merging = new HashSet<>();
    /** Frozen MemSegments not yet installed plus pending merges (for write throttling). */
    private final AtomicInteger backlog = new AtomicInteger();
    /** Ids of segments being built by the flush thread (their files must survive cleanup). */
    private final Set<Integer> building = ConcurrentHashMap.newKeySet();
    private final AtomicInteger segmentIds = new AtomicInteger();
    /** Next OID sequence per class; allocated at insert time by any thread (rolled-back inserts leave gaps). */
    private final Map<Integer, AtomicLong> oidCounters = new ConcurrentHashMap<>();
    /** Sequencer-owned: views per collection, newest first (active MemSegment, frozen ones, segments). */
    private final Map<String, List<SegmentView>> writerViews = new HashMap<>();
    /** Sequencer-owned: frozen MemSegments to flush once their rows are published. */
    private final List<FlushJob> pendingFlush = new ArrayList<>();
    private final ExecutorService flusher;
    private final TxRegistry txRegistry = new TxRegistry();
    /** Registered lifecycle listeners (type, listener), in registration order. */
    private final List<Map.Entry<Class<?>, EntityListener<?>>> listeners = new CopyOnWriteArrayList<>();
    private volatile Version version = Version.EMPTY;
    private volatile long visibleSeq;
    private volatile boolean closed;
    private int replayed;

    private Database(Path dir, Config config, Storage storage) {
        this.dir = dir;
        this.config = config;
        this.storage = storage;
        this.registry = new TypeRegistry(storage.loadSchema(), config.classLoader(), this::schemaChanged);
        AtomicInteger flushThreads = new AtomicInteger();
        this.flusher = Executors.newFixedThreadPool(config.flushThreads(), r -> {
            Thread t = new Thread(r, "kookaburra-flush-" + flushThreads.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.commit = storage.loadCommit();
        this.segmentIds.set(commit.nextSegmentId() - 1);
        this.sequencer = new Sequencer(this, commit.lastSeq());
        commit.oidSeqs().forEach(sequencer::seenOid);
    }

    public static Database open(Path dir, Config config) {
        Storage storage = dir == null ? new MemoryStorage() : new DiskStorage(dir);
        Database db;
        long start = System.nanoTime();
        try {
            db = new Database(dir, config, storage);
            db.recover();
        } catch (RuntimeException e) {
            storage.close();
            LOG.error("cannot open {}", db(dir), e);
            throw e;
        }
        db.sequencer.oidSeqs().forEach((cid, seq) -> db.oidCounters.put(cid, new AtomicLong(seq)));
        db.sequencer.start(storage.openWal(db.sequencer.lastSeq(), config.durability() == Durability.SYNC));
        if (LOG.isInfoEnabled()) {
            int segments = db.writerViews.values().stream().mapToInt(List::size).sum();
            LOG.info("opened {}: {} collections, {} segments, {} WAL records replayed, seq {}, {} ms", db(dir),
                    db.writerViews.size(), segments, db.replayed, db.sequencer.lastSeq(), (System.nanoTime() - start) / 1_000_000);
        }
        return db;
    }

    /** Loads committed segments, removes orphan files and replays the WAL (before the sequencer starts). */
    private void recover() {
        Map<String, Long> walSeqs = new HashMap<>();
        Set<Integer> inUse = new HashSet<>();
        for (Map.Entry<String, Commit.CollectionState> e : commit.collections().entrySet()) {
            String collection = e.getKey();
            walSeqs.put(collection, e.getValue().walSeq());
            List<SegmentView> list = new ArrayList<>();
            for (Commit.SegmentRef ref : e.getValue().segments()) {
                RoaringBitmap base = storage.readDeletes(collection, ref.id(), ref.delGen());
                Segment seg = new Segment(ref.id(), storage.openSegment(collection, ref.id()), registry::schema,
                        new SegmentDeletes(base), config.postingsOrMax());
                deleteState.put(ref.id(), new int[]{ref.delGen(), 0});
                list.add(0, seg);
                inUse.add(ref.id());
            }
            writerViews.put(collection, list);
            for (int i = list.size() - 1; i >= 0; i--) {
                version = version.withNewest(collection, list.get(i));
            }
        }
        storage.cleanup(commit, inUse);
        for (WalRecord rec : storage.readWal()) {
            sequencer.replay(rec, walSeqs);
            replayed++;
        }
        visibleSeq = sequencer.lastSeq();
    }

    /**
     * Before a new schema is saved and published: constraints it adds must hold for the stored data (like
     * {@code ALTER TABLE ADD CONSTRAINT}); otherwise the registration fails and the schema stays as it was.
     */
    private void schemaChanged(Schema next) {
        Schema current = registry == null ? null : registry.schema();
        if (current != null && !closed) {
            for (Schema.Constraint c : next.constraints()) {
                if (!current.constraints().contains(c)) {
                    validateExisting(next, c);
                }
            }
            for (Schema.CompositeUnique c : next.compositeUniques()) {
                if (!current.compositeUniques().contains(c)) {
                    validateExisting(next, c);
                }
            }
        }
        storage.saveSchema(next);
    }

    private void validateExisting(Schema schema, Schema.CompositeUnique c) {
        Set<Integer> types = schema.subclassIds(c.owner());
        if (types.isEmpty()) {
            return;
        }
        try (ReadView rv = openRead()) {
            Map<List<Object>, Long> seen = new HashMap<>();
            new Executor(rv, c.collection(), types, new Cond.Const(true), null).forEach(h -> {
                List<Object> key = new ArrayList<>();
                for (String p : c.def().paths()) {
                    key.add(Executor.value(h, p));
                }
                long oid = h.view().oid(h.doc());
                Long other = key.contains(null) ? null : seen.putIfAbsent(key, oid);
                if (other != null) {
                    throw new SchemaException("cannot add @Unique " + c + ": objects " + other + " and " + oid + " both have " + key);
                }
                return true;
            });
        }
    }

    private void validateExisting(Schema schema, Schema.Constraint c) {
        Set<Integer> types = schema.subclassIds(c.owner());
        if (types.isEmpty()) {
            return;
        }
        try (ReadView rv = openRead()) {
            Executor ex = new Executor(rv, c.collection(), types, new Cond.Not(new Cond.IsNull(c.path())), null);
            Map<Object, Long> seen = new HashMap<>();
            ex.forEach(h -> {
                Object v = Executor.value(h, c.path());
                long oid = h.view().oid(h.doc());
                if (c.unique()) {
                    Long other = seen.putIfAbsent(v, oid);
                    if (other != null) {
                        throw new SchemaException("cannot add @Unique to " + c + ": objects " + other + " and " + oid
                                + " both have " + v);
                    }
                } else {
                    for (Object t : v instanceof List<?> l ? l : List.of(v)) {
                        if (getDynamic(rv, (Long) t) == null) {
                            throw new SchemaException("cannot add @ForeignKey to " + c + ": object " + oid + " references object "
                                    + t + ", which does not exist");
                        }
                    }
                }
                return true;
            });
        }
    }

    // ---------------------------------------------------------------- writes

    @Override
    public long insert(Object entity) {
        return write(List.of(insertOp(entity)))[0];
    }

    @Override
    public void update(Object entity) {
        write(List.of(updateOp(entity)));
    }

    @Override
    public void delete(Object entity) {
        write(List.of(deleteOp(entity)));
    }

    @Override
    public boolean delete(Class<?> type, long oid) {
        return write(List.of(deleteOp(type, oid)))[0] == 1;
    }

    @Override
    public WriteBatch batch() {
        checkOpen();
        return new Batch(this);
    }

    // ---------------------------------------------------------------- lifecycle (LifecycleEvent)

    @Override
    public <T> void addListener(Class<T> type, EntityListener<? super T> listener) {
        if (type == null || listener == null) {
            throw new IllegalArgumentException("type and listener must not be null");
        }
        listeners.add(Map.entry(type, listener));
    }

    @Override
    public <T> boolean removeListener(Class<T> type, EntityListener<? super T> listener) {
        return listeners.remove(Map.entry(type, listener));
    }

    /** Delivers {@code event} to the listeners of the entity's class, then to its own callback methods. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void fire(LifecycleEvent event, EntityType<?> type, Object entity) {
        if (type == null || entity == null) {
            return;
        }
        for (Map.Entry<Class<?>, EntityListener<?>> l : listeners) {
            if (l.getKey().isInstance(entity)) {
                ((EntityListener) l.getValue()).on(event, entity);
            }
        }
        ((EntityType<Object>) type).callback(event, entity);
    }

    /** {@code POST_*} events of committed ops (typed entities only). */
    void firePost(List<WriteOp> ops, long[] res) {
        for (int i = 0; i < ops.size(); i++) {
            WriteOp op = ops.get(i);
            if (op instanceof WriteOp.Insert ins) {
                fire(LifecycleEvent.POST_PERSIST, ins.type(), ins.entity());
            } else if (op instanceof WriteOp.Update u) {
                fire(LifecycleEvent.POST_UPDATE, u.type(), u.entity());
            } else if (op instanceof WriteOp.Delete d && res[i] == 1) {
                fire(LifecycleEvent.POST_REMOVE, d.type(), d.entity());
            }
        }
    }

    WriteOp insertOp(Object entity) {
        if (entity instanceof DynamicObject d) {
            checkOpen();
            if (d.oid() != 0) {
                throw new IllegalArgumentException("object already has OID " + d.oid() + "; use update()");
            }
            Record r = DynamicCodec.toRecord(storedClass(d.className()), d);
            return new WriteOp.Insert(null, d, allocateOid(r.classId()), r, RecordCodec.encode(r));
        }
        EntityType<Object> t = type(entity);
        if (t.id(entity) != 0) {
            throw new IllegalArgumentException("object already has OID " + t.id(entity) + "; use update()");
        }
        fire(LifecycleEvent.PRE_PERSIST, t, entity);
        Record r = t.toRecord(entity);
        return new WriteOp.Insert(t, entity, allocateOid(r.classId()), r, RecordCodec.encode(r));
    }

    WriteOp updateOp(Object entity) {
        if (entity instanceof DynamicObject d) {
            checkOpen();
            ClassDef def = storedClass(d.className());
            requireOid(d);
            Record r = DynamicCodec.toRecord(def, d);
            return new WriteOp.Update(null, d, d.oid(), versioned(def) ? d.version() : WriteOp.ANY, r, RecordCodec.encode(r));
        }
        EntityType<Object> t = type(entity);
        long oid = requireOid(t, entity);
        fire(LifecycleEvent.PRE_UPDATE, t, entity);
        Record r = t.toRecord(entity);
        return new WriteOp.Update(t, entity, oid, t.hasVersion() ? t.version(entity) : WriteOp.ANY, r, RecordCodec.encode(r));
    }

    WriteOp deleteOp(Object entity) {
        if (entity instanceof DynamicObject d) {
            checkOpen();
            requireOid(d);
            return new WriteOp.Delete(d.oid(), versioned(storedClass(d.className())) ? d.version() : WriteOp.ANY, null, d);
        }
        if (entity instanceof Long oid) {
            return new WriteOp.Delete(oid, WriteOp.ANY, null, null);
        }
        EntityType<Object> t = type(entity);
        long oid = requireOid(t, entity);
        fire(LifecycleEvent.PRE_REMOVE, t, entity);
        return new WriteOp.Delete(oid, t.hasVersion() ? t.version(entity) : WriteOp.ANY, t, entity);
    }

    WriteOp deleteOp(Class<?> type, long oid) {
        registry.entity(type);
        return new WriteOp.Delete(oid, WriteOp.ANY, null, null);
    }

    private static void requireOid(DynamicObject d) {
        if (d.oid() == 0) {
            throw new IllegalArgumentException("object has no OID; insert() it first");
        }
    }

    private boolean versioned(ClassDef def) {
        return registry.schema().byName(def.collection()).versionField() != null;
    }

    /** Stored class by fully qualified or (unique) simple name. */
    public ClassDef storedClass(String name) {
        Schema schema = registry.schema();
        ClassDef def = schema.byName(name);
        if (def != null) {
            return def;
        }
        List<ClassDef> matches = new ArrayList<>();
        for (ClassDef c : schema.classes()) {
            if (c.simpleName().equals(name)) {
                matches.add(c);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        throw new IllegalArgumentException(matches.isEmpty() ? "no stored class '" + name + "'"
                : "ambiguous class name '" + name + "': " + matches.stream().map(ClassDef::name).toList());
    }

    @SuppressWarnings("unchecked")
    private EntityType<Object> type(Object entity) {
        checkOpen();
        return (EntityType<Object>) registry.entity(entity.getClass());
    }

    private static long requireOid(EntityType<Object> t, Object entity) {
        long oid = t.id(entity);
        if (oid == 0) {
            throw new IllegalArgumentException("object has no OID; insert() it first");
        }
        return oid;
    }

    /** A fresh OID of a class; not transactional (like a database sequence). */
    long allocateOid(int classId) {
        return Oids.make(classId, oidCounters.computeIfAbsent(classId, c -> new AtomicLong()).incrementAndGet());
    }

    @Override
    public void increment(Class<?> type, long oid, String field, Number delta) {
        checkOpen();
        if (field == null || delta == null) {
            throw new IllegalArgumentException("field and delta must not be null");
        }
        registry.entity(type);
        if (!registry.schema().subclassIds(type.getName()).contains(Oids.classId(oid))) {
            throw new IllegalArgumentException("OID " + oid + " is not a " + type.getName());
        }
        write(List.of(new WriteOp.Increment(oid, Map.of(field, delta))));
    }

    // ---------------------------------------------------------------- transactions (docs/transaction-design.md)

    @Override
    public Transaction begin() {
        return begin(TxOptions.defaults().isolation(config.txIsolation()));
    }

    @Override
    public Transaction begin(Isolation isolation) {
        return begin(TxOptions.defaults().isolation(isolation));
    }

    @Override
    public Transaction begin(TxOptions options) {
        checkOpen();
        if (!config.transactions()) {
            throw new KookaburraException("transactions are not enabled: set kookaburra.transactions=true "
                    + "(by default every write commits directly)");
        }
        return new TransactionImpl(this, options);
    }

    @Override
    public void inTransaction(TxWork work) {
        inTransaction(work, config.txRetries());
    }

    @Override
    public void inTransaction(TxWork work, int retries) {
        inTransactionResult(tx -> {
            work.run(tx);
            return null;
        }, retries);
    }

    @Override
    public void inTransaction(TxOptions options, TxWork work) {
        inTransactionResult(options, tx -> {
            work.run(tx);
            return null;
        });
    }

    @Override
    public <R> R inTransactionResult(TxOptions options, TxFunction<R> work) {
        return run(options, work, config.txRetries());
    }

    @Override
    public <R> R inTransactionResult(TxFunction<R> work) {
        return inTransactionResult(work, config.txRetries());
    }

    @Override
    public <R> R inTransactionResult(TxFunction<R> work, int retries) {
        return run(TxOptions.defaults().isolation(config.txIsolation()), work, retries);
    }

    /** Runs, commits and retries on conflicts with jittered exponential back-off (1 ms … 100 ms). */
    private <R> R run(TxOptions options, TxFunction<R> work, int retries) {
        if (retries < 0) {
            throw new IllegalArgumentException("negative retries");
        }
        for (int attempt = 0; ; attempt++) {
            try (Transaction tx = begin(options)) {
                R result = work.apply(tx);
                if (tx.state() == Transaction.State.ACTIVE) {
                    tx.commit();
                }
                return result;
            } catch (ConflictException e) {
                if (attempt >= retries) {
                    throw e;
                }
                LOG.debug("transaction conflict, retry {} of {}: {}", attempt + 1, retries, e.getMessage());
                long bound = Math.min(100, 1L << Math.min(attempt + 1, 6));
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(1, bound + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @Override
    public int activeTransactions() {
        return txRegistry.size();
    }

    TxRegistry txRegistry() {
        return txRegistry;
    }

    /** Entries of the sequencer's conflict table (diagnostics). */
    int conflictEntries() {
        int[] n = new int[1];
        join(sequencer.submitTask(() -> n[0] = sequencer.conflictEntries()));
        return n[0];
    }

    /** Submits a transaction's writes for validation and commit; results as for {@link #write}. */
    long[] commitTransaction(List<WriteOp> ops, Sequencer.TxCommit tx) {
        checkOpen();
        throttle();
        return join(sequencer.submitTx(ops, tx));
    }

    /** Submits one atomic batch, waits, and writes back OIDs / versions. */
    long[] write(List<WriteOp> ops) {
        checkOpen();
        throttle();
        long[] res = join(sequencer.submit(ops));
        writeBack(ops, res);
        firePost(ops, res);
        return res;
    }

    /** Writes OIDs / new versions back into the entities of committed ops. */
    void writeBack(List<WriteOp> ops, long[] res) {
        for (int i = 0; i < ops.size(); i++) {
            WriteOp op = ops.get(i);
            if (op instanceof WriteOp.Insert ins) {
                if (ins.entity() instanceof DynamicObject d) {
                    d.assign(res[i], 1);
                } else {
                    @SuppressWarnings("unchecked")
                    EntityType<Object> t = (EntityType<Object>) ins.type();
                    t.setId(ins.entity(), res[i]);
                    t.setVersion(ins.entity(), 1);
                }
            } else if (op instanceof WriteOp.Update u && u.entity() != null) {
                if (u.entity() instanceof DynamicObject d) {
                    d.assign(d.oid(), res[i]);
                } else {
                    @SuppressWarnings("unchecked")
                    EntityType<Object> t = (EntityType<Object>) u.type();
                    t.setVersion(u.entity(), res[i]);
                }
            }
        }
    }

    // ---------------------------------------------------------------- sequencer callbacks

    /** Latest live version of an object, or null. Sequencer thread only. */
    Location locate(long oid) {
        String collection;
        try {
            collection = registry.schema().byId(Oids.classId(oid)).collection();
        } catch (SchemaException e) {
            return null;
        }
        for (SegmentView v : writerViews.getOrDefault(collection, List.of())) {
            if (v instanceof MemSegment m) {
                int row = m.latestRow(oid);
                if (row >= 0) {
                    return m.isDeleted(row) ? null : new Location(m, row, m.version(row));
                }
            } else {
                Segment seg = (Segment) v;
                int doc = seg.docOf(oid);
                if (doc >= 0) {
                    return seg.deletes().isDeleted(doc) ? null : new Location(seg, doc, seg.version(doc));
                }
            }
        }
        return null;
    }

    void markDeleted(Location loc, long seq) {
        if (loc.view() instanceof MemSegment m) {
            m.markDeleted(loc.doc(), seq);
        } else {
            ((Segment) loc.view()).deletes().add(seq, loc.doc());
        }
    }

    /** Active MemSegment of a collection, created (and published in a new version) when needed. */
    MemSegment active(String collection) {
        List<SegmentView> list = writerViews.computeIfAbsent(collection, c -> new ArrayList<>());
        if (list.isEmpty() || !(list.get(0) instanceof MemSegment m) || m.frozen) {
            MemSegment fresh = new MemSegment(segmentIds.incrementAndGet(), registry.schema().columns(collection));
            list.add(0, fresh);
            version = version.withNewest(collection, fresh);
            return fresh;
        }
        return m;
    }

    /** Freezes active MemSegments over the size threshold. Sequencer thread only. */
    void maybeFreeze() {
        for (Map.Entry<String, List<SegmentView>> e : writerViews.entrySet()) {
            if (!e.getValue().isEmpty() && e.getValue().get(0) instanceof MemSegment m && !m.frozen
                    && m.bytes() >= config.memSegmentBytes()) {
                freeze(e.getKey(), m);
            }
        }
    }

    private CompletableFuture<Void> freeze(String collection, MemSegment m) {
        m.frozen = true;
        backlog.incrementAndGet();
        FlushJob job = new FlushJob(collection, m, m.maxSeq(), new CompletableFuture<>());
        pendingFlush.add(job);
        flushing.add(job);
        return job.done();
    }

    /** Starts flushes whose rows are now published. Sequencer thread only. */
    void afterPublish() {
        for (FlushJob job : pendingFlush) {
            flusher.execute(() -> build(job));
        }
        pendingFlush.clear();
    }

    /** Builds a segment from a frozen MemSegment (flush thread), then installs it on the sequencer thread. */
    private void build(FlushJob job) {
        long start = System.nanoTime();
        try {
            MemSegment m = job.mem();
            List<SegmentWriter.Entry> entries = new ArrayList<>();
            m.live(job.seq()).forEach((int row) ->
                    entries.add(new SegmentWriter.Entry(m.oid(row), m.version(row), m.record(row), m.row(row))));
            entries.sort(Comparator.comparingLong(SegmentWriter.Entry::oid));
            byte[] bytes = SegmentWriter.write(entries, registry.schema().columns(job.collection()).values(), config.bloomFpp(),
                    config.autoPostingsRatio());
            int id = segmentIds.incrementAndGet();
            building.add(id);
            Segment seg = new Segment(id, storage.writeSegment(job.collection(), id, bytes), registry::schema,
                    new SegmentDeletes(new RoaringBitmap()), config.postingsOrMax());
            LOG.debug("flushed {} rows of {} into seg#{} ({} bytes) in {} ms", entries.size(), job.collection(), id,
                    bytes.length, (System.nanoTime() - start) / 1_000_000);
            sequencer.submitTask(() -> install(job, seg)).whenComplete((r, t) -> {
                if (t != null) {
                    job.done().completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            LOG.error("flush of {} failed; writes are stopped", job.collection(), t);
            sequencer.fail(t);
            job.done().completeExceptionally(t);
        }
    }

    /** Replaces a flushed MemSegment by its segment, carrying over later deletes. Sequencer thread only. */
    private void install(FlushJob job, Segment seg) {
        flushing.remove(job);
        deleteState.put(seg.id(), new int[]{0, 0});
        MemSegment m = job.mem();
        for (int i = 0; i < m.deletedCount(); i++) {
            long seq = m.deletedRowSeq(i);
            if (seq > job.seq()) {
                seg.deletes().add(seq, seg.docOf(m.oid(m.deletedRow(i))));
            }
        }
        List<SegmentView> list = writerViews.get(job.collection());
        int i = list.indexOf(m);
        SegmentView replacement = seg.size() == 0 ? null : seg;
        if (replacement == null) {
            list.remove(i);
        } else {
            list.set(i, seg);
        }
        version = version.replace(job.collection(), m, replacement);
        writeCommit();
        building.remove(seg.id());
        backlog.decrementAndGet();
        job.done().complete(null);
        maybeCompact(job.collection(), false);
    }

    // ---------------------------------------------------------------- compaction (storage-design §10)

    /** Size tier of a segment: segments within a factor of {@code fanIn} share a tier. */
    private int tier(Segment s) {
        double base = Math.max(1, config.memSegmentBytes() / 4.0);
        return (int) (Math.log(Math.max(1, s.fileSize() / base)) / Math.log(config.compactionFanIn()));
    }

    /**
     * Schedules a merge of adjacent same-tier segments (oldest first) once there are {@code fanIn} of
     * them, or of every idle segment when {@code all}. Sequencer thread only.
     */
    private CompletableFuture<Void> maybeCompact(String collection, boolean all) {
        List<Segment> segs = new ArrayList<>();
        for (SegmentView v : writerViews.getOrDefault(collection, List.of())) {
            if (v instanceof Segment s) {
                segs.add(0, s);   // oldest first
            }
        }
        List<Segment> run = new ArrayList<>();
        long bytes = 0;
        for (Segment s : segs) {
            boolean fits = !compacting.contains(s) && bytes + s.fileSize() <= config.segmentMaxBytes()
                    && (all || run.isEmpty() || tier(run.get(0)) == tier(s));
            if (!fits) {
                if (run.size() >= (all ? 2 : config.compactionFanIn())) {
                    break;
                }
                run.clear();
                bytes = 0;
                if (compacting.contains(s) || s.fileSize() > config.segmentMaxBytes()) {
                    continue;
                }
            }
            run.add(s);
            bytes += s.fileSize();
            if (!all && run.size() == config.compactionFanIn()) {
                break;
            }
        }
        if (run.size() < (all ? 2 : config.compactionFanIn())) {
            run = garbage(segs, all);
            if (run.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
        }
        compacting.addAll(run);
        backlog.incrementAndGet();
        CompactJob job = new CompactJob(collection, List.copyOf(run), sequencer.lastSeq(), new CompletableFuture<>());
        merging.add(job);
        flusher.execute(() -> merge(job));
        return job.done();
    }

    /**
     * Segments worth rewriting for their deletes (like Lucene's {@code deletesPctAllowed} / RocksDB's
     * deletion-triggered compaction): every idle segment without live rows (dropped, no rewrite), else the one
     * with the most garbage once it reaches {@code compaction-garbage-ratio} — or any garbage when {@code all}.
     */
    private List<Segment> garbage(List<Segment> segs, boolean all) {
        long seq = sequencer.lastSeq();
        List<Segment> empty = new ArrayList<>();
        Segment worst = null;
        double worstRatio = 0;
        for (Segment s : segs) {
            if (compacting.contains(s) || s.size() == 0) {
                continue;
            }
            int dead = s.deletes().deleted(seq).getCardinality();
            if (dead >= s.size()) {
                empty.add(s);
            } else {
                double ratio = (double) dead / s.size();
                if (dead > 0 && ratio > worstRatio && (all || ratio >= config.compactionGarbageRatio())) {
                    worst = s;
                    worstRatio = ratio;
                }
            }
        }
        return !empty.isEmpty() ? empty : worst == null ? List.of() : List.of(worst);
    }

    /** Merges the live rows of the inputs into one segment (flush thread); no live rows = the inputs are dropped. */
    private void merge(CompactJob job) {
        long start = System.nanoTime();
        try {
            List<SegmentWriter.Entry> entries = new ArrayList<>();
            for (Segment s : job.inputs()) {
                s.live(job.seq()).forEach((int d) ->
                        entries.add(new SegmentWriter.Entry(s.oid(d), s.version(d), s.record(d), s.row(d))));
            }
            if (entries.isEmpty()) {
                LOG.info("dropped {} fully deleted segment(s) of {}", job.inputs().size(), job.collection());
                sequencer.submitTask(() -> installMerge(job, null)).whenComplete((r, t) -> {
                    if (t != null) {
                        job.done().completeExceptionally(t);
                    }
                });
                return;
            }
            entries.sort(Comparator.comparingLong(SegmentWriter.Entry::oid));
            byte[] bytes = SegmentWriter.write(entries, registry.schema().columns(job.collection()).values(), config.bloomFpp(),
                    config.autoPostingsRatio());
            int id = segmentIds.incrementAndGet();
            building.add(id);
            Segment merged = new Segment(id, storage.writeSegment(job.collection(), id, bytes), registry::schema,
                    new SegmentDeletes(new RoaringBitmap()), config.postingsOrMax());
            LOG.info("compacted {} segments of {} into seg#{}: {} live rows, {} bytes, {} ms", job.inputs().size(),
                    job.collection(), id, entries.size(), bytes.length, (System.nanoTime() - start) / 1_000_000);
            sequencer.submitTask(() -> installMerge(job, merged)).whenComplete((r, t) -> {
                if (t != null) {
                    job.done().completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            LOG.error("compaction of {} failed; writes are stopped", job.collection(), t);
            sequencer.fail(t);
            job.done().completeExceptionally(t);
        }
    }

    /** Swaps the inputs for the merged segment, remapping deletes that arrived during the merge. */
    private void installMerge(CompactJob job, Segment merged) {
        merging.remove(job);
        if (merged != null) {
            List<long[]> late = new ArrayList<>();
            for (Segment s : job.inputs()) {
                for (long[] e : s.deletes().entries(Long.MAX_VALUE)) {
                    if (e[0] > job.seq()) {
                        late.add(new long[]{e[0], merged.docOf(s.oid((int) e[1]))});
                    }
                }
            }
            late.sort(Comparator.comparingLong(e -> e[0]));
            for (long[] e : late) {
                merged.deletes().add(e[0], (int) e[1]);
            }
            deleteState.put(merged.id(), new int[]{0, 0});
        }
        List<SegmentView> list = writerViews.get(job.collection());
        int at = Integer.MAX_VALUE;
        for (Segment s : job.inputs()) {
            at = Math.min(at, list.indexOf(s));
        }
        list.removeAll(job.inputs());
        SegmentView replacement = merged == null || merged.size() == 0 ? null : merged;
        if (replacement != null) {
            list.add(at, merged);
        }
        version = version.replaceAll(job.collection(), job.inputs(), replacement);
        for (Segment s : job.inputs()) {
            deleteState.remove(s.id());
            compacting.remove(s);
        }
        writeCommit();
        if (merged != null) {
            building.remove(merged.id());
        }
        backlog.decrementAndGet();
        job.done().complete(null);
        maybeCompact(job.collection(), false);
    }

    @Override
    public void compact() {
        flush();
        while (true) {
            List<CompletableFuture<Void>> jobs = Collections.synchronizedList(new ArrayList<>());
            join(sequencer.submitTask(() -> {
                for (CompactJob running : merging) {
                    jobs.add(running.done());   // merges started in the background are waited for, then looked at again
                }
                for (String c : writerViews.keySet()) {
                    CompletableFuture<Void> f = maybeCompact(c, true);
                    if (!f.isDone()) {
                        jobs.add(f);
                    }
                }
            }));
            if (jobs.isEmpty()) {
                return;
            }
            join(CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])));
        }
    }

    /** Callers wait while flushes / merges lag behind (back-pressure, storage-design §10). */
    void throttle() {
        if (backlog.get() <= config.writeBacklog()) {
            return;
        }
        long start = System.nanoTime();
        long deadline = start + 30_000_000_000L;
        while (backlog.get() > config.writeBacklog() && System.nanoTime() < deadline && !closed) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        long waited = (System.nanoTime() - start) / 1_000_000;
        if (waited >= 1000) {
            LOG.warn("write back-pressure: waited {} ms for flush / compaction (backlog {}, limit {})", waited,
                    backlog.get(), config.writeBacklog());
        }
    }

    /** Persists new deletes and the current segment set as a new commit. Sequencer thread only. */
    private void writeCommit() {
        if (storage instanceof MemoryStorage) {
            return;
        }
        long last = sequencer.lastSeq();
        Map<String, Commit.CollectionState> cols = new LinkedHashMap<>();
        Set<Integer> inUse = new HashSet<>();
        for (Map.Entry<String, List<SegmentView>> e : writerViews.entrySet()) {
            long walSeq = last;
            List<Commit.SegmentRef> refs = new ArrayList<>();
            for (SegmentView v : e.getValue()) {
                if (v instanceof MemSegment m) {
                    if (m.size() > 0) {
                        walSeq = Math.min(walSeq, m.firstSeq() - 1);
                    }
                } else {
                    Segment seg = (Segment) v;
                    int[] st = deleteState.get(seg.id());
                    int logged = seg.deletes().logged();
                    if (logged > st[1]) {
                        st[0]++;
                        storage.writeDeletes(e.getKey(), seg.id(), st[0], seg.deletes().deleted(last));
                        st[1] = logged;
                    }
                    refs.add(0, new Commit.SegmentRef(seg.id(), st[0]));
                    inUse.add(seg.id());
                }
            }
            cols.put(e.getKey(), new Commit.CollectionState(walSeq, refs));
        }
        commit = new Commit(commit.id() + 1, last, segmentIds.get() + 1, cols, sequencer.oidSeqs());
        storage.writeCommit(commit);
        sequencer.rotateWal(storage.openWal(last, config.durability() == Durability.SYNC));
        inUse.addAll(building);
        storage.cleanup(commit, inUse);
    }

    @Override
    public BackupInfo backup(Path target) {
        checkOpen();
        return Backup.run(this, target);
    }

    /** Highest OID sequence handed out per class (a backup's commit continues from these). */
    Map<Integer, Long> oidSeqs() {
        Map<Integer, Long> out = new HashMap<>();
        oidCounters.forEach((cid, n) -> out.put(cid, n.get()));
        return out;
    }

    @Override
    public void flush() {
        checkOpen();
        List<CompletableFuture<Void>> jobs = Collections.synchronizedList(new ArrayList<>());
        join(sequencer.submitTask(() -> {
            for (Map.Entry<String, List<SegmentView>> e : writerViews.entrySet()) {
                if (!e.getValue().isEmpty() && e.getValue().get(0) instanceof MemSegment m && !m.frozen && m.size() > 0) {
                    jobs.add(freeze(e.getKey(), m));
                }
            }
            for (FlushJob job : flushing) {
                jobs.add(job.done());          // flushes started earlier (size threshold) are waited for too
            }
        }));
        join(CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])));
    }

    private static <V> V join(CompletableFuture<V> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new KookaburraException("operation failed", e.getCause());
        }
    }

    /** Segment views of a collection in the current version (for tests and tools). */
    public List<SegmentView> views(String collection) {
        return version.views(collection);
    }

    /** The latest version (segment views including unpublished MemSegment rows). Sequencer thread only. */
    Version currentVersion() {
        return version;
    }

    void publish(long seq) {
        visibleSeq = seq;
    }

    // ---------------------------------------------------------------- reads

    @Override
    public TypeRegistry registry() {
        return registry;
    }

    @Override
    public ReadView openRead() {
        checkOpen();
        while (true) {
            Version v1 = version;
            long s = visibleSeq;
            if (version == v1) {
                return new ReadViewImpl(v1, s);
            }
        }
    }

    @Override
    public Object materialize(SegmentView view, int doc) {
        EntityType<?> t = registry.entity(view.classId(doc));
        if (t == null) {
            throw new KookaburraException("class " + registry.schema().byId(view.classId(doc)).name()
                    + " is not on the classpath; use dynamic access");
        }
        Object o = t.fromRecord(view.record(doc), view.oid(doc), view.version(doc));
        fire(LifecycleEvent.POST_LOAD, t, o);
        return o;
    }

    @Override
    public DynamicObject materializeDynamic(SegmentView view, int doc) {
        ClassDef def = registry.schema().byId(view.classId(doc));
        return DynamicCodec.fromRecord(def, view.record(doc), view.oid(doc), view.version(doc));
    }

    @Override
    public DynamicCollection dynamic(String className) {
        checkOpen();
        return new DynamicCollection(dynamicBackend, storedClass(className));
    }

    private final DynamicCollection.Backend dynamicBackend = new DynamicCollection.Backend() {
        @Override
        public Schema schema() {
            return registry.schema();
        }

        @Override
        public QueryStream<DynamicObject> query(ClassDef def) {
            checkOpen();
            return new QueryStream<>(Database.this, QueryStream.Target.dynamic(def.name()), null);
        }

        @Override
        public DynamicObject get(ClassDef def, long oid) {
            try (ReadView rv = openRead()) {
                Schema schema = registry.schema();
                if (!schema.subclassIds(def.name()).contains(Oids.classId(oid))) {
                    return null;
                }
                for (SegmentView v : rv.pointViews(def.collection(), oid)) {
                    int d = v.find(oid, rv.seq());
                    if (d == SegmentView.DELETED) {
                        return null;
                    }
                    if (d >= 0) {
                        return materializeDynamic(v, d);
                    }
                }
                return null;
            }
        }

        @Override
        public long[] write(List<Object> inserts, List<Object> updates, List<Object> deletes) {
            List<WriteOp> ops = new ArrayList<>();
            inserts.forEach(o -> ops.add(insertOp(o)));
            updates.forEach(o -> ops.add(updateOp(o)));
            deletes.forEach(o -> ops.add(deleteOp(o)));
            return Database.this.write(ops);
        }
    };

    @Override
    public <T> T get(Class<T> type, long oid) {
        try (ReadView rv = openRead()) {
            return get(rv, type, oid);
        }
    }

    @Override
    public Object get(long oid) {
        try (ReadView rv = openRead()) {
            return getAny(rv, oid);
        }
    }

    @Override
    public Object getTyped(ReadView view, long oid) {
        return getAny(view, oid);
    }

    @Override
    public DynamicObject getDynamic(ReadView rv, long oid) {
        ClassDef def;
        try {
            def = registry.schema().byId(Oids.classId(oid));
        } catch (SchemaException e) {
            return null;
        }
        for (SegmentView v : rv.pointViews(def.collection(), oid)) {
            int d = v.find(oid, rv.seq());
            if (d == SegmentView.DELETED) {
                return null;
            }
            if (d >= 0) {
                return materializeDynamic(v, d);
            }
        }
        return null;
    }

    @Override
    public OqlQuery<Object> oql(String text) {
        checkOpen();
        return new OqlQuery<>(this, text, Object.class);
    }

    @Override
    public <T> OqlQuery<T> oql(String text, Class<T> resultType) {
        checkOpen();
        return new OqlQuery<>(this, text, resultType);
    }

    /** Object by OID of any class, or null. */
    Object getAny(ReadView rv, long oid) {
        Schema schema = registry.schema();
        ClassDef def;
        try {
            def = schema.byId(Oids.classId(oid));
        } catch (SchemaException e) {
            return null;
        }
        for (SegmentView v : rv.pointViews(def.collection(), oid)) {
            int d = v.find(oid, rv.seq());
            if (d == SegmentView.DELETED) {
                return null;
            }
            if (d >= 0) {
                return materialize(v, d);
            }
        }
        return null;
    }

    @Override
    public Map<Long, Object> getAll(ReadView view, Collection<Long> oids) {
        Map<Long, Object> out = new LinkedHashMap<>();
        for (long oid : oids) {
            Object o = getAny(view, oid);
            if (o != null) {
                out.put(oid, o);
            }
        }
        return out;
    }

    @Override
    public ObjectDB database() {
        return this;
    }

    @Override
    public <T> Map<Long, T> getAll(Class<T> type, Collection<Long> oids) {
        try (ReadView rv = openRead()) {
            return getAll(rv, type, oids);
        }
    }

    <T> T get(ReadView rv, Class<T> type, long oid) {
        registry.entity(type);
        Schema schema = registry.schema();
        int classId = Oids.classId(oid);
        if (!schema.subclassIds(type.getName()).contains(classId)) {
            return null;
        }
        for (SegmentView v : rv.pointViews(schema.byId(classId).collection(), oid)) {
            int d = v.find(oid, rv.seq());
            if (d == SegmentView.DELETED) {
                return null;
            }
            if (d >= 0) {
                return type.cast(materialize(v, d));
            }
        }
        return null;
    }

    <T> Map<Long, T> getAll(ReadView rv, Class<T> type, Collection<Long> oids) {
        Map<Long, T> out = new LinkedHashMap<>();
        for (long oid : oids) {
            T t = get(rv, type, oid);
            if (t != null) {
                out.put(oid, t);
            }
        }
        return out;
    }

    @Override
    public <T> QueryStream<T> from(Class<T> type) {
        checkOpen();
        return new QueryStream<>(this, type, null);
    }

    @Override
    public List<DynamicObject> referrers(long oid) {
        try (ReadView rv = openRead()) {
            return referrers(rv, oid, null);
        }
    }

    @Override
    public <T> List<T> referrers(Class<T> type, long oid) {
        try (ReadView rv = openRead()) {
            return referrers(rv, oid, type);
        }
    }

    /** Referrers as of a read view: dynamic objects ({@code type == null}) or entities of {@code type}. */
    @SuppressWarnings("unchecked")
    <T> List<T> referrers(ReadView rv, long oid, Class<T> type) {
        Schema schema = registry.schema();
        Set<Integer> types = type == null ? null : schema.subclassIds(registry.entity(type).def().name());
        Map<Long, T> out = new LinkedHashMap<>();
        for (Schema.RefPath p : schema.refPaths()) {
            if (types != null && types.stream().noneMatch(id -> schema.byId(id).collection().equals(p.collection()))) {
                continue;
            }
            TypeDef ref = TypeDef.ref(p.target());
            Cond cond = p.many() ? new Cond.Contains(p.path(), ref, Set.of(oid), false) : new Cond.Eq(p.path(), ref, oid);
            new Executor(rv, p.collection(), types, cond, null).forEach(h -> {
                long ref2 = h.view().oid(h.doc());
                out.computeIfAbsent(ref2, k -> (T) (type == null ? materializeDynamic(h.view(), h.doc()) : materialize(h.view(), h.doc())));
                return true;
            });
        }
        return new ArrayList<>(out.values());
    }

    @Override
    public Snapshot snapshot() {
        return new SnapshotImpl(this, openRead());
    }

    @Override
    public Schema schema() {
        return registry.schema();
    }

    @Override
    public Path path() {
        return dir;
    }

    Config config() {
        return config;
    }

    private void checkOpen() {
        if (closed) {
            throw new KookaburraException("database is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            sequencer.shutdown();
            flusher.shutdown();
            try {
                flusher.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sequencer.wal().close();
            for (List<SegmentView> views : version.all().values()) {
                for (SegmentView v : views) {
                    if (v instanceof Segment seg) {
                        seg.close();
                    }
                }
            }
            storage.close();
            LOG.info("closed {}", db(dir));
        }
    }

    private static String db(Path dir) {
        return dir == null ? "in-memory database" : dir.toString();
    }
}
