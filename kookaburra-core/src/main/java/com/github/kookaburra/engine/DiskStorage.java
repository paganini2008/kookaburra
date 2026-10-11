package com.github.kookaburra.engine;

import com.github.kookaburra.KookaburraException;
import com.github.kookaburra.io.MappedFile;
import com.github.kookaburra.mapping.Schema;
import com.github.kookaburra.mapping.SchemaIO;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Database directory layout (design §4):
 * <pre>
 * LOCK · CURRENT · commit_N · schema/schema_V · wal/wal_&lt;firstSeq&gt;.log
 * col/&lt;collection&gt;/seg_&lt;id&gt;.seg · col/&lt;collection&gt;/seg_&lt;id&gt;_&lt;gen&gt;.del
 * </pre>
 * Every file is written to a temp name, fsync'ed, atomically renamed, and its directory fsync'ed.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class DiskStorage implements Storage {

    private static final Pattern SCHEMA = Pattern.compile("schema_(\\d+)");
    private static final Pattern COMMIT = Pattern.compile("commit_(\\d+)");
    private static final Pattern WAL = Pattern.compile("wal_(\\d+)\\.log");
    private static final Pattern SEG = Pattern.compile("seg_(\\d+)\\.seg");
    private static final Pattern DEL = Pattern.compile("seg_(\\d+)_(\\d+)\\.del");

    private final Path dir;
    private final FileChannel lockChannel;
    private final FileLock lock;

    DiskStorage(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir.resolve("schema"));
            Files.createDirectories(dir.resolve("wal"));
            Files.createDirectories(dir.resolve("col"));
            lockChannel = FileChannel.open(dir.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock l;
            try {
                l = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                l = null;
            }
            if (l == null) {
                lockChannel.close();
                throw new KookaburraException(dir + " is already open (LOCK is held)");
            }
            lock = l;
            for (Path p : list(dir.resolve("schema"))) {
                if (p.getFileName().toString().endsWith(".tmp")) {
                    Files.delete(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open " + dir, e);
        }
    }

    // ---------------------------------------------------------------- schema

    @Override
    public Schema loadSchema() {
        Path latest = latest(dir.resolve("schema"), SCHEMA);
        return latest == null ? Schema.empty() : SchemaIO.read(read(latest));
    }

    @Override
    public void saveSchema(Schema schema) {
        Path schemaDir = dir.resolve("schema");
        writeAtomically(schemaDir.resolve("schema_" + schema.version()), SchemaIO.write(schema));
        for (Path p : list(schemaDir)) {
            Matcher m = SCHEMA.matcher(p.getFileName().toString());
            if (m.matches() && Long.parseLong(m.group(1)) < schema.version()) {
                delete(p);
            }
        }
    }

    // ---------------------------------------------------------------- commits

    @Override
    public Commit loadCommit() {
        Path current = dir.resolve("CURRENT");
        if (!Files.exists(current)) {
            return Commit.EMPTY;
        }
        String name = new String(read(current)).trim();
        return Commit.decode(read(dir.resolve(name)));
    }

    @Override
    public void writeCommit(Commit commit) {
        String name = "commit_" + commit.id();
        writeAtomically(dir.resolve(name), commit.encode());
        writeAtomically(dir.resolve("CURRENT"), name.getBytes());
        for (Path p : list(dir)) {
            Matcher m = COMMIT.matcher(p.getFileName().toString());
            if (m.matches() && Long.parseLong(m.group(1)) < commit.id()) {
                delete(p);
            }
        }
    }

    // ---------------------------------------------------------------- segments and deletes

    private Path collectionDir(String collection) {
        Path p = dir.resolve("col").resolve(collection);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    @Override
    public MappedFile writeSegment(String collection, int id, byte[] bytes) {
        Path p = collectionDir(collection).resolve("seg_" + id + ".seg");
        writeAtomically(p, bytes);
        return MappedFile.map(p);
    }

    @Override
    public MappedFile openSegment(String collection, int id) {
        return MappedFile.map(collectionDir(collection).resolve("seg_" + id + ".seg"));
    }

    @Override
    public RoaringBitmap readDeletes(String collection, int segmentId, int gen) {
        RoaringBitmap bm = new RoaringBitmap();
        if (gen == 0) {
            return bm;
        }
        byte[] b = read(collectionDir(collection).resolve("seg_" + segmentId + "_" + gen + ".del"));
        try {
            bm.deserialize(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bm;
    }

    @Override
    public void writeDeletes(String collection, int segmentId, int gen, RoaringBitmap deleted) {
        RoaringBitmap copy = deleted.clone();
        copy.runOptimize();
        ByteBuffer b = ByteBuffer.allocate(copy.serializedSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
        copy.serialize(b);
        writeAtomically(collectionDir(collection).resolve("seg_" + segmentId + "_" + gen + ".del"), b.array());
    }

    // ---------------------------------------------------------------- WAL

    @Override
    public List<WalRecord> readWal() {
        List<WalRecord> out = new ArrayList<>();
        for (Path p : walFiles().values()) {
            out.addAll(WalFile.read(p));
        }
        return out;
    }

    private TreeMap<Long, Path> walFiles() {
        TreeMap<Long, Path> files = new TreeMap<>();
        for (Path p : list(dir.resolve("wal"))) {
            Matcher m = WAL.matcher(p.getFileName().toString());
            if (m.matches()) {
                files.put(Long.parseLong(m.group(1)), p);
            }
        }
        return files;
    }

    @Override
    public Wal openWal(long lastSeq, boolean sync) {
        Path p = dir.resolve("wal").resolve("wal_" + (lastSeq + 1) + ".log");
        WalFile w = new WalFile(p, sync);
        fsyncDir(dir.resolve("wal"));
        return w;
    }

    // ---------------------------------------------------------------- cleanup

    @Override
    public void cleanup(Commit commit, Collection<Integer> segmentsInUse) {
        Set<String> keep = new HashSet<>();
        for (var e : commit.collections().entrySet()) {
            for (Commit.SegmentRef s : e.getValue().segments()) {
                keep.add("seg_" + s.id() + "_" + s.delGen() + ".del");
            }
        }
        for (Path colDir : list(dir.resolve("col"))) {
            for (Path p : list(colDir)) {
                String n = p.getFileName().toString();
                Matcher seg = SEG.matcher(n);
                Matcher del = DEL.matcher(n);
                if ((seg.matches() && !segmentsInUse.contains(Integer.parseInt(seg.group(1))))
                        || (del.matches() && !keep.contains(n)) || n.endsWith(".tmp")) {
                    delete(p);
                }
            }
        }
        // WAL files whose records are all <= minWalSeq: a file ends where the next one starts.
        long min = commit.minWalSeq();
        TreeMap<Long, Path> wal = walFiles();
        for (var e : wal.entrySet()) {
            Long next = wal.higherKey(e.getKey());
            if (next != null && next - 1 <= min) {
                delete(e.getValue());
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    static void writeAtomically(Path target, byte[] bytes) {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            while (b.hasRemaining()) {
                ch.write(b);
            }
            ch.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + tmp, e);
        }
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rename " + tmp, e);
        }
        fsyncDir(target.getParent());
    }

    static void fsyncDir(Path d) {
        try (FileChannel ch = FileChannel.open(d, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            // Some platforms cannot open directories; rename durability then depends on the OS.
        }
    }

    private static byte[] read(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p, e);
        }
    }

    private static List<Path> list(Path d) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(d)) {
            return out;
        }
        try (DirectoryStream<Path> s = Files.newDirectoryStream(d)) {
            s.forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static Path latest(Path d, Pattern pattern) {
        Path best = null;
        long bestN = -1;
        for (Path p : list(d)) {
            Matcher m = pattern.matcher(p.getFileName().toString());
            if (m.matches() && Long.parseLong(m.group(1)) > bestN) {
                bestN = Long.parseLong(m.group(1));
                best = p;
            }
        }
        return best;
    }

    private static void delete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // best effort: retried by the next cleanup
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
            lockChannel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
