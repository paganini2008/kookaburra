package com.github.kookaburra.io;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Growable little-endian byte buffer with LEB128 varints. Not thread-safe.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class ByteSink {

    private byte[] buf;
    private int size;

    public ByteSink() {
        this(64);
    }

    public ByteSink(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    public int size() {
        return size;
    }

    public void reset() {
        size = 0;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }

    /** Backing array; valid up to {@link #size()}. */
    public byte[] array() {
        return buf;
    }

    private void ensure(int extra) {
        if (size + extra > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + extra));
        }
    }

    public ByteSink writeByte(int b) {
        ensure(1);
        buf[size++] = (byte) b;
        return this;
    }

    public ByteSink writeBytes(byte[] b) {
        return writeBytes(b, 0, b.length);
    }

    public ByteSink writeBytes(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, size, len);
        size += len;
        return this;
    }

    public ByteSink writeInt(int v) {
        ensure(4);
        buf[size++] = (byte) v;
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) (v >>> 16);
        buf[size++] = (byte) (v >>> 24);
        return this;
    }

    public ByteSink writeLong(long v) {
        writeInt((int) v);
        return writeInt((int) (v >>> 32));
    }

    public ByteSink writeDouble(double v) {
        return writeLong(Double.doubleToRawLongBits(v));
    }

    /** Unsigned LEB128. */
    public ByteSink writeVarLong(long v) {
        ensure(10);
        while ((v & ~0x7FL) != 0) {
            buf[size++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf[size++] = (byte) v;
        return this;
    }

    public ByteSink writeVarInt(int v) {
        return writeVarLong(v & 0xFFFFFFFFL);
    }

    /** Zig-zag signed varint. */
    public ByteSink writeZigZag(long v) {
        return writeVarLong((v << 1) ^ (v >> 63));
    }

    /** Varint length + UTF-8 bytes. */
    public ByteSink writeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(b.length);
        return writeBytes(b);
    }

    /** Varint length + bytes. */
    public ByteSink writeBlob(byte[] b) {
        writeVarInt(b.length);
        return writeBytes(b);
    }
}
