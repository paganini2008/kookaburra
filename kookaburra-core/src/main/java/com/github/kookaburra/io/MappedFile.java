package com.github.kookaburra.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Read-only random access to an immutable file (storage-design §11). Little-endian. Files are at most
 * 1.5 GB, so one buffer covers a whole file on JDK 17.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class MappedFile implements AutoCloseable {

    private final ByteBuffer buf;
    private final String name;
    private final boolean mapped;
    private volatile boolean closed;

    private MappedFile(ByteBuffer buf, String name, boolean mapped) {
        this.buf = buf.order(ByteOrder.LITTLE_ENDIAN);
        this.name = name;
        this.mapped = mapped;
    }

    /** Wraps bytes held on the heap (in-memory databases). */
    public static MappedFile heap(byte[] bytes, String name) {
        return new MappedFile(ByteBuffer.wrap(bytes), name, false);
    }

    /** Maps a file read-only. */
    public static MappedFile map(Path path) {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size > Integer.MAX_VALUE) {
                throw new IllegalStateException(path + " is larger than 2 GB");
            }
            MappedByteBuffer mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
            return new MappedFile(mb, path.getFileName().toString(), true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot map " + path, e);
        }
    }

    public String name() {
        return name;
    }

    public long size() {
        return buf.capacity();
    }

    public long getLong(long pos) {
        return buf.getLong((int) pos);
    }

    public int getInt(long pos) {
        return buf.getInt((int) pos);
    }

    public byte getByte(long pos) {
        return buf.get((int) pos);
    }

    /** Independent little-endian view of {@code [pos, pos + len)}, position 0. */
    public ByteBuffer slice(long pos, int len) {
        return buf.slice((int) pos, len).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** A reader over {@code [pos, pos + len)} sharing the mapping (one buffer view, no slice). */
    public ByteSource source(long pos, int len) {
        return new ByteSource(buf, (int) pos, len);
    }

    /** Copies {@code [pos, pos + len)}. */
    public byte[] bytes(long pos, int len) {
        byte[] b = new byte[len];
        buf.get((int) pos, b, 0, len);
        return b;
    }

    public boolean isMapped() {
        return mapped;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Unmaps immediately when possible (JDK internal cleaner), otherwise leaves it to GC. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (mapped) {
            unmap(buf);
        }
    }

    private static void unmap(ByteBuffer b) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Method clean = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
            clean.invoke(f.get(null), b);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // jdk.unsupported not available: the mapping is released by GC
        }
    }
}
