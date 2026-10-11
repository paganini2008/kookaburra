package com.github.kookaburra.engine;

import com.github.kookaburra.io.MappedFile;
import com.github.kookaburra.mapping.Schema;
import org.roaringbitmap.RoaringBitmap;

import java.util.Collection;
import java.util.List;

/**
 * Storage of an in-memory database: segments live on the heap, nothing survives {@code close()}.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class MemoryStorage implements Storage {

    @Override
    public Schema loadSchema() {
        return Schema.empty();
    }

    @Override
    public void saveSchema(Schema schema) {
    }

    @Override
    public Commit loadCommit() {
        return Commit.EMPTY;
    }

    @Override
    public void writeCommit(Commit commit) {
    }

    @Override
    public MappedFile writeSegment(String collection, int id, byte[] bytes) {
        return MappedFile.heap(bytes, "seg#" + id);
    }

    @Override
    public MappedFile openSegment(String collection, int id) {
        throw new IllegalStateException("in-memory storage has no segment files");
    }

    @Override
    public RoaringBitmap readDeletes(String collection, int segmentId, int gen) {
        return new RoaringBitmap();
    }

    @Override
    public void writeDeletes(String collection, int segmentId, int gen, RoaringBitmap deleted) {
    }

    @Override
    public List<WalRecord> readWal() {
        return List.of();
    }

    @Override
    public Wal openWal(long lastSeq, boolean sync) {
        return Wal.NONE;
    }

    @Override
    public void cleanup(Commit commit, Collection<Integer> segmentsInUse) {
    }

    @Override
    public void close() {
    }
}
