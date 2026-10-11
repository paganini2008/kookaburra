package com.github.kookaburra.engine;

import com.github.kookaburra.Config;
import com.github.kookaburra.Durability;
import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.Snapshot;
import com.github.kookaburra.WriteBatch;
import com.github.kookaburra.dynamic.DynamicCollection;
import com.github.kookaburra.dynamic.DynamicObject;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    /** Frozen MemSegments not yet installed plus pending merges (for write throttling). */
    private final AtomicInteger backlog = new AtomicInteger();
    /** Ids of segments being built by the flush thread (their files must survive cleanup). */
    private final Set<Integer> building = ConcurrentHashMap.newKeySet();
    private final AtomicInteger segmentIds = new AtomicInteger();
    /** Sequencer-owned: views per collection, newest first (active MemSegment, frozen ones, segments). */
    private final Map<String, List<SegmentView>> writerViews = new HashMap<>();
    /** Sequencer-owned: frozen MemSegments to flush once their rows are published. */
    private final List<FlushJob> pendingFlush = new ArrayList<>();
    private final ExecutorService flusher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kookaburra-flush");
        t.setDaemon(true);
        return t;
    });
    private volatile Version version = Version.EMPTY;
    private volatile long visibleSeq;
    private volatile boolean closed;
    private int replayed;

    private Database(Path dir, Config config, Storage storage) {
        this.dir = dir;
        this.config = config;
        this.storage = storage;
        this.registry = new TypeRegistry(storage.loadSchema(), config.classLoader(), storage::saveSchema);
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

    WriteOp insertOp(Object entity) {
        if (entity instanceof DynamicObject d) {
            checkOpen();
            if (d.oid() != 0) {
                throw new IllegalArgumentException("object already has OID " + d.oid() + "; use update()");
            }
            Record r = DynamicCodec.toRecord(storedClass(d.className()), d);
            return new WriteOp.Insert(null, d, r, RecordCodec.encode(r));
        }
        EntityType<Object> t = type(entity);
        if (t.id(entity) != 0) {
            throw new IllegalArgumentException("object already has OID " + t.id(entity) + "; use update()");
        }
        Record r = t.toRecord(entity);
        return new WriteOp.Insert(t, entity, r, RecordCodec.encode(r));
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

    /** Submits one atomic batch, waits, and writes back OIDs / versions. */
    long[] write(List<WriteOp> ops) {
        checkOpen();
        throttle();
        long[] res = join(sequencer.submit(ops));
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
            } else if (op instanceof WriteOp.Update u) {
                if (u.entity() instanceof DynamicObject d) {
                    d.assign(d.oid(), res[i]);
                } else {
                    @SuppressWarnings("unchecked")
                    EntityType<Object> t = (EntityType<Object>) u.type();
                    t.setVersion(u.entity(), res[i]);
                }
            }
        }
        return res;
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

    // ---------------------------------------------------------------- compaction (design §13)

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
            return CompletableFuture.completedFuture(null);
        }
        compacting.addAll(run);
        backlog.incrementAndGet();
        CompactJob job = new CompactJob(collection, List.copyOf(run), sequencer.lastSeq(), new CompletableFuture<>());
        flusher.execute(() -> merge(job));
        return job.done();
    }

    /** Merges the live rows of the inputs into one segment (flush thread). */
    private void merge(CompactJob job) {
        long start = System.nanoTime();
        try {
            List<SegmentWriter.Entry> entries = new ArrayList<>();
            for (Segment s : job.inputs()) {
                s.live(job.seq()).forEach((int d) ->
                        entries.add(new SegmentWriter.Entry(s.oid(d), s.version(d), s.record(d), s.row(d))));
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
        List<SegmentView> list = writerViews.get(job.collection());
        int at = Integer.MAX_VALUE;
        for (Segment s : job.inputs()) {
            at = Math.min(at, list.indexOf(s));
        }
        list.removeAll(job.inputs());
        SegmentView replacement = merged.size() == 0 ? null : merged;
        if (replacement != null) {
            list.add(at, merged);
        }
        version = version.replaceAll(job.collection(), job.inputs(), replacement);
        for (Segment s : job.inputs()) {
            deleteState.remove(s.id());
            compacting.remove(s);
        }
        writeCommit();
        building.remove(merged.id());
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

    /** Callers wait while flushes / merges lag behind (back-pressure, design §12). */
    private void throttle() {
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
    public void flush() {
        checkOpen();
        List<CompletableFuture<Void>> jobs = Collections.synchronizedList(new ArrayList<>());
        join(sequencer.submitTask(() -> {
            for (Map.Entry<String, List<SegmentView>> e : writerViews.entrySet()) {
                if (!e.getValue().isEmpty() && e.getValue().get(0) instanceof MemSegment m && !m.frozen && m.size() > 0) {
                    jobs.add(freeze(e.getKey(), m));
                }
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
        return t.fromRecord(view.record(doc), view.oid(doc), view.version(doc));
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
                for (SegmentView v : rv.views(def.collection())) {
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
        for (SegmentView v : rv.views(def.collection())) {
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
        for (SegmentView v : rv.views(def.collection())) {
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
        for (SegmentView v : rv.views(schema.byId(classId).collection())) {
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
