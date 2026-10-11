package com.github.kookaburra.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Sequential little-endian reader over a {@link ByteBuffer} region (absolute access; the buffer is not modified).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class ByteSource {

    private final ByteBuffer buf;
    private int pos;
    private final int end;

    public ByteSource(ByteBuffer buf, int offset, int length) {
        this.buf = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        this.pos = offset;
        this.end = offset + length;
    }

    public ByteSource(byte[] bytes) {
        this(ByteBuffer.wrap(bytes), 0, bytes.length);
    }

    public int position() {
        return pos;
    }

    public int remaining() {
        return end - pos;
    }

    private void need(int n) {
        if (pos + n > end) {
            throw new IllegalStateException("corrupt data: read past end (" + (pos + n) + " > " + end + ")");
        }
    }

    public int readByte() {
        need(1);
        return buf.get(pos++);
    }

    public byte[] readBytes(int n) {
        need(n);
        byte[] b = new byte[n];
        buf.get(pos, b, 0, n);
        pos += n;
        return b;
    }

    public int readInt() {
        need(4);
        int v = buf.getInt(pos);
        pos += 4;
        return v;
    }

    public long readLong() {
        need(8);
        long v = buf.getLong(pos);
        pos += 8;
        return v;
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public long readVarLong() {
        long v = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int b = readByte();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        throw new IllegalStateException("corrupt data: varint too long");
    }

    public int readVarInt() {
        long v = readVarLong();
        if (v > 0xFFFFFFFFL) {
            throw new IllegalStateException("corrupt data: varint overflow");
        }
        return (int) v;
    }

    public long readZigZag() {
        long v = readVarLong();
        return (v >>> 1) ^ -(v & 1);
    }

    public String readString() {
        int n = readVarInt();
        return new String(readBytes(n), StandardCharsets.UTF_8);
    }

    /** Advances over {@code n} bytes without reading them. */
    public void skip(int n) {
        need(n);
        pos += n;
    }

    /** The byte at an absolute position already passed (no bounds or cursor change beyond the source). */
    public int byteAt(int position) {
        return buf.get(position);
    }

    public byte[] readBlob() {
        return readBytes(readVarInt());
    }
}
