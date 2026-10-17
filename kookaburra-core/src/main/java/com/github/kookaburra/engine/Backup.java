package com.github.kookaburra.engine;

import com.github.kookaburra.BackupInfo;
import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.exec.SegmentView;
import com.github.kookaburra.io.MappedFile;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.segment.Segment;
import com.github.kookaburra.segment.SegmentWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Online backup (storage-design §13): the live objects of one MVCC snapshot are written as fresh, compacted segment
 * files plus schema and commit into a new directory, like an export to a clean database. Writers are not blocked
 * (the snapshot pins nothing they need); in-memory databases can be backed up to disk the same way. The copy is
 * an ordinary database directory: restore = open it.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class Backup {

    private static final Logger LOG = LoggerFactory.getLogger(Backup.class);

    /** Documents of one view in OID order, consumed by the k-way merge. */
    private static final class Cursor {
        final SegmentView view;
        final int[] docs;
        int at;

        Cursor(SegmentView view, int[] docs) {
            this.view = view;
            this.docs = docs;
        }

        long oid() {
            return view.oid(docs[at]);
        }
    }

    private Backup() {
    }

    static BackupInfo run(Database db, Path target) {
        Path to = target.toAbsolutePath().normalize();
        checkTarget(db, to);
        long start = System.nanoTime();
        long objects = 0;
        long bytes = 0;
        int segments = 0;
        try (ReadView rv = db.openRead(); DiskStorage out = new DiskStorage(to)) {
            Schema schema = db.registry().schema();
            Map<Integer, Long> oidSeqs = db.oidSeqs();          // read after the snapshot: covers every OID in it
            out.saveSchema(schema);
            long limit = db.config().memSegmentBytes() * 2;     // rows per output segment (records are decoded in memory)
            Map<String, Commit.CollectionState> collections = new LinkedHashMap<>();
            int nextId = 1;
            for (String collection : schema.collections()) {
                PriorityQueue<Cursor> queue = new PriorityQueue<>(Comparator.comparingLong(Cursor::oid));
                for (SegmentView v : rv.views(collection)) {
                    int[] docs = v.live(rv.seq()).toArray();
                    if (v instanceof MemSegment) {               // segments are in OID order already, memtables are not
                        docs = IntStream.of(docs).boxed().sorted(Comparator.comparingLong(v::oid)).mapToInt(Integer::intValue).toArray();
                    }
                    if (docs.length > 0) {
                        queue.add(new Cursor(v, docs));
                    }
                }
                List<Commit.SegmentRef> refs = new ArrayList<>();
                List<SegmentWriter.Entry> chunk = new ArrayList<>();
                long chunkBytes = 0;
                while (!queue.isEmpty()) {
                    Cursor c = queue.poll();
                    int d = c.docs[c.at];
                    byte[] row = c.view instanceof Segment s ? s.row(d) : ((MemSegment) c.view).row(d);
                    chunk.add(new SegmentWriter.Entry(c.view.oid(d), c.view.version(d), c.view.record(d), row));
                    chunkBytes += row.length;
                    if (++c.at < c.docs.length) {
                        queue.add(c);
                    }
                    if (chunkBytes >= limit || queue.isEmpty()) {
                        byte[] seg = SegmentWriter.write(chunk, schema.columns(collection).values(), db.config().bloomFpp(),
                                db.config().autoPostingsRatio());
                        int id = nextId++;
                        try (MappedFile f = out.writeSegment(collection, id, seg)) {
                            refs.add(new Commit.SegmentRef(id, 0));
                        }
                        objects += chunk.size();
                        bytes += seg.length;
                        segments++;
                        chunk.clear();
                        chunkBytes = 0;
                    }
                }
                if (!refs.isEmpty()) {
                    collections.put(collection, new Commit.CollectionState(rv.seq(), refs));
                }
            }
            out.writeCommit(new Commit(1, rv.seq(), nextId, collections, oidSeqs));
            long millis = (System.nanoTime() - start) / 1_000_000;
            LOG.info("backup of {} at seq {} → {}: {} objects, {} segments, {} bytes, {} ms",
                    db.path() == null ? "in-memory database" : db.path(), rv.seq(), to, objects, segments, bytes, millis);
            return new BackupInfo(to, rv.seq(), objects, segments, bytes, millis);
        }
    }

    /** The target must be a new or empty directory, and not the database itself. */
    private static void checkTarget(Database db, Path to) {
        if (db.path() != null && to.equals(db.path().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("cannot back up a database into its own directory");
        }
        if (Files.exists(to)) {
            if (!Files.isDirectory(to)) {
                throw new IllegalArgumentException(to + " is a file; the backup needs a new or empty directory");
            }
            try (Stream<Path> files = Files.list(to)) {
                if (files.findAny().isPresent()) {
                    throw new IllegalArgumentException(to + " is not empty; the backup needs a new or empty directory");
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
