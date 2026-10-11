package com.github.kookaburra.engine;

import com.github.kookaburra.io.MappedFile;
import com.github.kookaburra.mapping.Schema;
import org.roaringbitmap.RoaringBitmap;

import java.util.Collection;
import java.util.List;

/**
 * Persistence used by the engine: schema, commits, segment files, delete bitmaps and the WAL.
 * {@link MemoryStorage} keeps nothing; {@link DiskStorage} implements design §4.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
interface Storage extends AutoCloseable {

    Schema loadSchema();

    /** Persists a schema before it becomes visible. */
    void saveSchema(Schema schema);

    Commit loadCommit();

    /** Atomically makes {@code commit} the current state. */
    void writeCommit(Commit commit);

    /** Stores a finished segment and returns it mapped / wrapped for reading. */
    MappedFile writeSegment(String collection, int id, byte[] bytes);

    MappedFile openSegment(String collection, int id);

    /** Persisted delete bitmap of a segment for a generation (gen 0 = empty). */
    RoaringBitmap readDeletes(String collection, int segmentId, int gen);

    void writeDeletes(String collection, int segmentId, int gen, RoaringBitmap deleted);

    /** All WAL records in sequence order (torn tails are truncated). */
    List<WalRecord> readWal();

    /** Opens a fresh WAL file for records starting after {@code lastSeq}. */
    Wal openWal(long lastSeq, boolean sync);

    /** Removes files not referenced by {@code commit} and WAL files fully covered by it. */
    void cleanup(Commit commit, Collection<Integer> segmentsInUse);

    @Override
    void close();
}
