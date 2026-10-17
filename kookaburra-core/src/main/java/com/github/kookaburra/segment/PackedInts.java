package com.github.kookaburra.segment;

import com.github.kookaburra.io.ByteSink;
import com.github.kookaburra.io.MappedFile;

/**
 * Bit-packed non-negative ints: value {@code i} at bit {@code i * bits}; 8 bytes of padding so every
 * read is a single 8-byte load (storage-design §4.2).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class PackedInts {

    private PackedInts() {
    }

    /** Bits needed to store values {@code 0..max}. */
    public static int bitsFor(int max) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(max));
    }

    public static void write(ByteSink out, int[] values, int bits) {
        long totalBits = (long) values.length * bits;
        int bytes = (int) ((totalBits + 7) >>> 3) + 8;
        byte[] b = new byte[bytes];
        for (int i = 0; i < values.length; i++) {
            long v = values[i] & 0xFFFFFFFFL;
            long bit = (long) i * bits;
            for (int j = 0; j < bits; j++) {
                if ((v & (1L << j)) != 0) {
                    long p = bit + j;
                    b[(int) (p >>> 3)] |= (byte) (1 << (p & 7));
                }
            }
        }
        out.writeBytes(b);
    }

    public static int get(MappedFile f, long offset, int bits, int index) {
        long bit = (long) index * bits;
        long word = f.getLong(offset + (bit >>> 3));
        return (int) ((word >>> (bit & 7)) & ((1L << bits) - 1));
    }
}
