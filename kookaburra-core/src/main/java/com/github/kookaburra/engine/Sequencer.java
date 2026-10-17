package com.github.kookaburra.engine;

import com.github.kookaburra.ConflictException;
import com.github.kookaburra.Durability;
import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.OptimisticLockException;
import com.github.kookaburra.TransactionTimeoutException;
import com.github.kookaburra.exec.Executor;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.mapping.Record;
import com.github.kookaburra.mapping.RecordCodec;
import com.github.kookaburra.mapping.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The single write thread (storage-design §6.1): validates batches against the latest state, assigns OIDs,
 * versions and one sequence number per batch, logs them, applies them to MemSegments and publishes
 * the new {@code visibleSeq}. Group commit: one WAL sync per drained group.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class Sequencer implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(Sequencer.class);
    private static final byte[] NO_ROW = new byte[0];

    /**
     * One atomic batch and its completion (results: insert → OID, update → new version, delete → 1 / 0),
     * or a maintenance task ({@code ops == null}) that must run on the sequencer thread.
     */
    record Request(List<WriteOp> ops, Runnable task, CompletableFuture<long[]> done, TxCommit tx) {
    }

    /**
     * Commit of a transaction (docs/transaction-design.md §11): its snapshot; the OIDs it updates or deletes with
     * the sequence number each must not have changed after ({@code checkSeqs}: the snapshot, or for READ COMMITTED
     * the statement that read it); the OIDs it inserted and deleted again ({@code vanished}: never stored, but
     * foreign keys of the batch's own writes must see them as deleted); and for SERIALIZABLE what it read.
     */
    record TxCommit(TxRegistry.Entry entry, long snapshot, long[] checkOids, long[] checkSeqs, long[] vanished, ReadSet reads) {
    }

    private final Database db;
    private Wal wal = Wal.NONE;
    private final BlockingQueue<Request> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private final Map<Integer, Long> lastOidSeq = new HashMap<>();
    /** OID → seq of its last change, for transaction conflict checks; only kept while transactions are open. */
    private final ConflictTable conflicts = new ConflictTable();
    /** Before / after records of commits, kept while SERIALIZABLE transactions are open. */
    private final ChangeLog changes = new ChangeLog();
    private volatile boolean running = true;
    private long lastSeq;
    private volatile Throwable failure;

    Sequencer(Database db, long lastSeq) {
        this.db = db;
        this.lastSeq = lastSeq;
        this.thread = new Thread(this, "kookaburra-sequencer");
        this.thread.setDaemon(true);
    }

    void start(Wal log) {
        this.wal = log;
        thread.start();
    }

    Wal wal() {
        return wal;
    }

    /** Switches to a new WAL file and closes the old one. Sequencer thread only. */
    void rotateWal(Wal next) {
        Wal old = wal;
        wal = next;
        old.close();
    }

    /** Highest OID sequence per class handed out so far. Sequencer thread (or before start) only. */
    Map<Integer, Long> oidSeqs() {
        return new HashMap<>(lastOidSeq);
    }

    /**
     * Re-applies a logged batch during recovery (before {@link #start}); ops of collections whose
     * segments already contain {@code seq} are skipped.
     */
    void replay(WalRecord rec, Map<String, Long> walSeqs) {
        Schema schema = db.registry().schema();
        for (WalRecord.Op op : rec.ops()) {
            int classId = Oids.classId(op.oid());
            String collection = schema.byId(classId).collection();
            if (rec.seq() <= walSeqs.getOrDefault(collection, 0L)) {
                continue;
            }
            switch (op.kind()) {
                case WalRecord.INSERT -> {
                    db.active(collection).append(op.oid(), 1, rec.seq(),
                            RecordCodec.decode(schema, op.row()), op.row());
                    seenOid(classId, Oids.seq(op.oid()));
                }
                case WalRecord.UPDATE -> {
                    Database.Location loc = db.locate(op.oid());
                    if (loc != null) {
                        db.markDeleted(loc, rec.seq());
                    }
                    db.active(collection).append(op.oid(), op.version(), rec.seq(),
                            RecordCodec.decode(schema, op.row()), op.row());
                }
                default -> {
                    Database.Location loc = op.applied() ? db.locate(op.oid()) : null;
                    if (loc != null) {
                        db.markDeleted(loc, rec.seq());
                    }
                }
            }
        }
        lastSeq = Math.max(lastSeq, rec.seq());
    }

    /** Ensures OIDs of a class continue after {@code seq} (used by recovery). */
    void seenOid(int classId, long seq) {
        lastOidSeq.merge(classId, seq, Math::max);
    }

    CompletableFuture<long[]> submit(List<WriteOp> ops) {
        CompletableFuture<long[]> f = new CompletableFuture<>();
        if (!running) {
            f.completeExceptionally(new KookaburraException("database is closed"));
            return f;
        }
        if (failure != null) {
            f.completeExceptionally(new KookaburraException("database failed; reopen it", failure));
            return f;
        }
        queue.add(new Request(List.copyOf(ops), null, f, null));
        return f;
    }

    /** Submits a transaction's writes; they are checked against commits after its snapshot first. */
    CompletableFuture<long[]> submitTx(List<WriteOp> ops, TxCommit tx) {
        CompletableFuture<long[]> f = submitCheck();
        if (f == null) {
            f = new CompletableFuture<>();
            queue.add(new Request(List.copyOf(ops), null, f, tx));
        }
        return f;
    }

    private CompletableFuture<long[]> submitCheck() {
        if (!running) {
            return CompletableFuture.failedFuture(new KookaburraException("database is closed"));
        }
        if (failure != null) {
            return CompletableFuture.failedFuture(new KookaburraException("database failed; reopen it", failure));
        }
        return null;
    }

    /** Runs {@code task} on the sequencer thread between write groups. */
    CompletableFuture<long[]> submitTask(Runnable task) {
        CompletableFuture<long[]> f = new CompletableFuture<>();
        if (!running || failure != null) {
            f.completeExceptionally(new KookaburraException("database is closed or failed", failure));
            return f;
        }
        queue.add(new Request(null, task, f, null));
        return f;
    }

    /** Sequence number of the last applied batch. Sequencer thread only. */
    long lastSeq() {
        return lastSeq;
    }

    /** Marks the database failed (e.g. a background flush failed): further writes are rejected. */
    void fail(Throwable t) {
        failure = t;
    }

    void shutdown() {
        // No interrupt: interrupting a thread inside FileChannel I/O closes the channel.
        running = false;
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Request r;
        while ((r = queue.poll()) != null) {
            r.done().completeExceptionally(new KookaburraException("database is closed"));
        }
    }

    @Override
    public void run() {
        List<Request> group = new ArrayList<>();
        List<long[]> results = new ArrayList<>();
        while (running) {
            try {
                Request first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                group.add(first);
                int max = db.config().sequencerMaxGroup();
                queue.drainTo(group, max - 1);
                long wait = db.config().groupCommitMicros();
                if (wait > 0 && group.size() < max && db.config().durability() == Durability.SYNC) {
                    // group commit: give concurrent writers a moment to join this fsync
                    Request more = queue.poll(wait, TimeUnit.MICROSECONDS);
                    if (more != null) {
                        group.add(more);
                        queue.drainTo(group, max - group.size());
                    }
                }
                for (Request r : group) {
                    results.add(process(r));
                }
                wal.sync();
                db.publish(lastSeq);
                db.afterPublish();
                pruneConflicts();
                for (int i = 0; i < group.size(); i++) {
                    if (results.get(i) != null) {
                        group.get(i).done().complete(results.get(i));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Throwable t) {
                LOG.error("write sequencer failed; the database rejects further writes", t);
                failure = t;
                running = false;
                for (Request r : group) {
                    r.done().completeExceptionally(new KookaburraException("write failed", t));
                }
                Request r;
                while ((r = queue.poll()) != null) {
                    r.done().completeExceptionally(new KookaburraException("write failed", t));
                }
            } finally {
                group.clear();
                results.clear();
            }
        }
    }

    /** Validates and applies one batch; returns null if it was rejected (its future is completed). */
    private long[] process(Request req) {
        if (req.task() != null) {
            req.task().run();
            return new long[0];
        }
        long[] out;
        List<WriteOp> ops;
        changes.track(db.txRegistry().serializable() > 0, lastSeq + 1);
        try {
            if (req.tx() != null) {
                checkConflicts(req.tx());
            }
            ops = resolveIncrements(req.ops());
            out = validate(ops);
            Integrity.Result checked = Integrity.check(db, lastSeq, ops, out, req.tx() == null ? null : req.tx().vanished());
            ops = checked.ops();
            out = checked.out();
        } catch (KookaburraException | IllegalArgumentException | ArithmeticException e) {
            req.done().completeExceptionally(e);
            return null;
        }
        long seq = ++lastSeq;
        wal.append(seq, ops, out);
        for (int i = 0; i < ops.size(); i++) {
            apply(ops.get(i), out[i], seq);
        }
        db.maybeFreeze();
        return out;
    }

    /** First committer wins: an object changed by a commit after the snapshot fails the transaction (§11.1). */
    private void checkConflicts(TxCommit tx) {
        TxRegistry.Entry e = tx.entry();
        if (e.abortReason != null) {
            throw e.timedOut ? new TransactionTimeoutException(e.abortReason) : new ConflictException(e.abortReason);
        }
        long[] oids = tx.checkOids();
        for (int i = 0; i < oids.length; i++) {
            long oid = oids[i];
            long last = conflicts.lastSeq(oid);
            if (last > tx.checkSeqs()[i]) {
                LOG.debug("transaction {} conflicts on object {} (changed at seq {}, read at {})", e.id, oid, last, tx.checkSeqs()[i]);
                throw new ConflictException(oid, "object " + oid + " was changed by a concurrent commit (seq " + last
                        + ") after transaction " + e.id + " read it (seq " + tx.checkSeqs()[i] + ")");
            }
        }
        if (tx.reads() != null) {
            checkReads(e, tx.snapshot(), tx.reads());
        }
    }

    /**
     * SERIALIZABLE (§11.3): nothing the transaction read may have changed after its snapshot. Objects read by OID
     * are looked up in the conflict table; for each query condition, the before and after versions of the objects
     * committed since the snapshot are tested against it (a match = the query's result would differ: an update,
     * a delete or a phantom).
     */
    private void checkReads(TxRegistry.Entry e, long snapshot, ReadSet reads) {
        if (!changes.covers(snapshot)) {
            throw new ConflictException("serialization failure: transaction " + e.id
                    + " started while validation was being switched on and cannot be validated; retry it");
        }
        for (long oid : reads.oids()) {
            long last = conflicts.lastSeq(oid);
            if (last > snapshot) {
                throw new ConflictException(oid, "serialization failure: object " + oid + " read by transaction " + e.id
                        + " was changed by a concurrent commit (seq " + last + ")");
            }
        }
        for (Map.Entry<ReadSet.Query, Executor.Materializer> q : reads.queries().entrySet()) {
            ReadSet.Query query = q.getKey();
            List<ChangeLog.Change> since = changes.since(query.collection(), snapshot);
            if (!since.isEmpty() && matches(query, q.getValue(), since)) {
                throw new ConflictException("serialization failure: a concurrent commit changed the result of a query of transaction "
                        + e.id + " (" + simple(query.collection()) + " WHERE " + query.cond() + ")");
            }
        }
    }

    /** Whether a before or after version of the changes satisfies the query (errors count as a match). */
    private boolean matches(ReadSet.Query query, Executor.Materializer materializer, List<ChangeLog.Change> since) {
        try {
            MemSegment versions = new MemSegment(0, db.registry().schema().columns(query.collection()));
            for (ChangeLog.Change c : since) {
                if (c.before() != null) {
                    versions.append(c.oid(), 1, 1, c.before(), NO_ROW);
                }
                if (c.after() != null) {
                    versions.append(c.oid(), 1, 1, c.after(), NO_ROW);
                }
            }
            List<SegmentView> views = List.of(versions);
            ReadView read = new ReadView() {
                @Override
                public long seq() {
                    return 1;
                }

                @Override
                public List<SegmentView> views(String collection) {
                    return views;
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
            return new Executor(read, query.collection(), query.types(), query.cond(), materializer).count() > 0;
        } catch (RuntimeException ex) {
            LOG.debug("cannot evaluate a read predicate; treating it as changed", ex);
            return true;
        }
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** Turns increments into updates of the latest versions (logged as normal updates: deterministic replay). */
    private List<WriteOp> resolveIncrements(List<WriteOp> ops) {
        List<WriteOp> out = null;
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) instanceof WriteOp.Increment inc) {
                if (out == null) {
                    out = new ArrayList<>(ops);
                }
                Database.Location loc = db.locate(inc.oid());
                if (loc == null) {
                    throw new ConflictException(inc.oid(), "object " + inc.oid() + " was deleted before the increment committed");
                }
                Record r = Increments.apply(loc.view().record(loc.doc()), inc.deltas());
                out.set(i, new WriteOp.Update(null, null, inc.oid(), loc.version(), r, RecordCodec.encode(r)));
            }
        }
        return out == null ? ops : out;
    }

    /** Forgets conflict history no open transaction can need; evicts the oldest transaction when it overflows. */
    private void pruneConflicts() {
        if (conflicts.size() == 0 && changes.size() == 0) {
            return;
        }
        TxRegistry registry = db.txRegistry();
        if (changes.size() > 0) {
            changes.prune(registry.serializableHorizon(lastSeq));
            int max = db.config().txConflictTableMax();
            while (changes.size() > max && registry.evictOldestSerializable("change log full (" + changes.size()
                    + " entries, kookaburra.tx-conflict-table-max = " + max + ")")) {
                changes.prune(registry.serializableHorizon(lastSeq));
            }
        }
        conflicts.prune(registry.horizon(lastSeq));
        int max = db.config().txConflictTableMax();
        while (conflicts.size() > max && registry.evictOldest("conflict table full (" + conflicts.size()
                + " entries, kookaburra.tx-conflict-table-max = " + max + ")")) {
            conflicts.prune(registry.horizon(lastSeq));
        }
    }

    /** Conflict-table entries after pruning (for tests and diagnostics). Sequencer thread only. */
    int conflictEntries() {
        pruneConflicts();
        return conflicts.size();
    }

    /** Per-OID state as seen by earlier ops of the same batch. */
    private record Pending(long version, boolean deleted) {
    }

    private long[] validate(List<WriteOp> ops) {
        long[] out = new long[ops.size()];
        Map<Long, Pending> pending = new HashMap<>();
        for (int i = 0; i < ops.size(); i++) {
            WriteOp op = ops.get(i);
            if (op instanceof WriteOp.Insert ins) {
                if (Oids.classId(ins.oid()) != ins.record().classId()) {
                    throw new IllegalArgumentException("OID " + ins.oid() + " does not belong to class "
                            + db.registry().schema().byId(ins.record().classId()).name());
                }
                out[i] = ins.oid();
                pending.put(out[i], new Pending(1, false));
            } else if (op instanceof WriteOp.Update u) {
                if (Oids.classId(u.oid()) != u.record().classId()) {
                    throw new IllegalArgumentException("OID " + u.oid() + " does not belong to class "
                            + db.registry().schema().byId(u.record().classId()).name());
                }
                Pending cur = current(u.oid(), pending);
                if (cur == null || cur.deleted()) {
                    throw new OptimisticLockException(u.oid(), "object " + u.oid() + " does not exist");
                }
                if (u.expected() != WriteOp.ANY && u.expected() != cur.version()) {
                    throw new OptimisticLockException(u.oid(), "object " + u.oid() + " has version " + cur.version()
                            + ", expected " + u.expected());
                }
                out[i] = cur.version() + 1;
                pending.put(u.oid(), new Pending(out[i], false));
            } else {
                WriteOp.Delete d = (WriteOp.Delete) op;
                Pending cur = current(d.oid(), pending);
                if (cur == null || cur.deleted()) {
                    if (d.expected() != WriteOp.ANY) {
                        throw new OptimisticLockException(d.oid(), "object " + d.oid() + " does not exist");
                    }
                    out[i] = 0;
                    continue;
                }
                if (d.expected() != WriteOp.ANY && d.expected() != cur.version()) {
                    throw new OptimisticLockException(d.oid(), "object " + d.oid() + " has version " + cur.version()
                            + ", expected " + d.expected());
                }
                out[i] = 1;
                pending.put(d.oid(), new Pending(cur.version(), true));
            }
        }
        for (WriteOp op : ops) {
            if (op instanceof WriteOp.Insert ins) {
                seenOid(Oids.classId(ins.oid()), Oids.seq(ins.oid()));
            }
        }
        return out;
    }

    private Pending current(long oid, Map<Long, Pending> pending) {
        Pending p = pending.get(oid);
        if (p != null) {
            return p;
        }
        Database.Location loc = db.locate(oid);
        return loc == null ? null : new Pending(loc.version(), false);
    }

    private void apply(WriteOp op, long result, long seq) {
        Schema schema = db.registry().schema();
        if (op instanceof WriteOp.Insert ins) {
            String collection = schema.byId(ins.record().classId()).collection();
            db.active(collection).append(result, 1, seq, ins.record(), ins.row());
            changes.record(seq, collection, result, null, ins.record());
        } else if (op instanceof WriteOp.Update u) {
            Database.Location loc = db.locate(u.oid());
            String collection = schema.byId(u.record().classId()).collection();
            if (changes.active()) {
                changes.record(seq, collection, u.oid(), loc.view().record(loc.doc()), u.record());
            }
            db.markDeleted(loc, seq);
            db.active(collection).append(u.oid(), result, seq, u.record(), u.row());
            recordChange(u.oid(), seq);
        } else if (result == 1) {
            long oid = ((WriteOp.Delete) op).oid();
            Database.Location loc = db.locate(oid);
            if (changes.active()) {
                changes.record(seq, schema.byId(Oids.classId(oid)).collection(), oid, loc.view().record(loc.doc()), null);
            }
            db.markDeleted(loc, seq);
            recordChange(oid, seq);
        }
    }

    /** Every write path records here, so open transactions see autocommit and batch writes too (§11.2). */
    private void recordChange(long oid, long seq) {
        if (db.config().transactions()) {
            conflicts.record(oid, seq);
        }
    }
}
