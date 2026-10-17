package com.github.kookaburra.engine;

import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.io.ByteSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

/**
 * Contents of a {@code commit_N} file (storage-design §3, §6.4): which segments (with which delete
 * generation) make up each collection, where WAL replay starts per collection, and OID high-water marks.
 *
 * @param id            commit number N
 * @param lastSeq       sequence number of the last batch applied when the commit was written
 * @param nextSegmentId next free segment / MemSegment id
 * @param collections   per collection: replay start and segments, oldest first
 * @param oidSeqs       per class id: highest OID sequence handed out
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
record Commit(long id, long lastSeq, int nextSegmentId, Map<String, CollectionState> collections,
              Map<Integer, Long> oidSeqs) {

    /** One segment file and the generation of its persisted delete bitmap (0 = none). */
    record SegmentRef(int id, int delGen) {
    }

    /**
     * @param walSeq   WAL records of this collection with {@code seq <= walSeq} are already in segments
     * @param segments segments, oldest first
     */
    record CollectionState(long walSeq, List<SegmentRef> segments) {
    }

    static final Commit EMPTY = new Commit(0, 0, 1, Map.of(), Map.of());

    private static final long MAGIC = 0x3130544D4D4F434BL;   // "KCOMMT01"

    /** Smallest per-collection replay point: WAL records at or below it are no longer needed. */
    long minWalSeq() {
        long min = lastSeq;
        for (CollectionState c : collections.values()) {
            min = Math.min(min, c.walSeq());
        }
        return min;
    }

    byte[] encode() {
        ByteSink b = new ByteSink();
        b.writeLong(MAGIC).writeVarLong(id).writeVarLong(lastSeq).writeVarInt(nextSegmentId);
        b.writeVarInt(collections.size());
        for (Map.Entry<String, CollectionState> e : collections.entrySet()) {
            b.writeString(e.getKey()).writeVarLong(e.getValue().walSeq()).writeVarInt(e.getValue().segments().size());
            for (SegmentRef s : e.getValue().segments()) {
                b.writeVarInt(s.id()).writeVarInt(s.delGen());
            }
        }
        b.writeVarInt(oidSeqs.size());
        oidSeqs.forEach((k, v) -> b.writeVarInt(k).writeVarLong(v));
        CRC32C crc = new CRC32C();
        crc.update(b.array(), 0, b.size());
        b.writeInt((int) crc.getValue());
        return b.toByteArray();
    }

    static Commit decode(byte[] bytes) {
        if (bytes.length < 12) {
            throw new IllegalStateException("commit file truncated");
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);
        ByteSource tail = new ByteSource(bytes);
        tail.readBytes(bytes.length - 4);
        if (tail.readInt() != (int) crc.getValue()) {
            throw new IllegalStateException("commit file checksum mismatch");
        }
        ByteSource in = new ByteSource(bytes);
        if (in.readLong() != MAGIC) {
            throw new IllegalStateException("not a commit file");
        }
        long id = in.readVarLong();
        long lastSeq = in.readVarLong();
        int nextSegmentId = in.readVarInt();
        int nc = in.readVarInt();
        Map<String, CollectionState> cols = new LinkedHashMap<>();
        for (int i = 0; i < nc; i++) {
            String name = in.readString();
            long walSeq = in.readVarLong();
            int ns = in.readVarInt();
            List<SegmentRef> segs = new ArrayList<>(ns);
            for (int j = 0; j < ns; j++) {
                segs.add(new SegmentRef(in.readVarInt(), in.readVarInt()));
            }
            cols.put(name, new CollectionState(walSeq, segs));
        }
        int no = in.readVarInt();
        Map<Integer, Long> oids = new LinkedHashMap<>();
        for (int i = 0; i < no; i++) {
            oids.put(in.readVarInt(), in.readVarLong());
        }
        return new Commit(id, lastSeq, nextSegmentId, cols, oids);
    }
}
