package com.github.kookaburra.segment;

import com.github.kookaburra.io.MappedFile;

/**
 * Bloom filter over OIDs (storage-design §4.1), double hashing on a 64-bit mix.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Bloom {

    private Bloom() {
    }

    /** Number of bits for {@code n} keys at false-positive rate {@code fpp} (at least 64). */
    public static long bits(int n, double fpp) {
        double m = -Math.max(1, n) * Math.log(fpp) / (Math.log(2) * Math.log(2));
        return Math.max(64, (long) Math.ceil(m / 64) * 64);
    }

    public static int hashes(int n, long bits) {
        return Math.max(1, (int) Math.round((double) bits / Math.max(1, n) * Math.log(2)));
    }

    public static long[] build(long[] keys, long bits, int k) {
        long[] words = new long[(int) (bits / 64)];
        for (long key : keys) {
            long h1 = mix(key);
            long h2 = mix(h1) | 1;
            for (int i = 0; i < k; i++) {
                long bit = Long.remainderUnsigned(h1 + i * h2, bits);
                words[(int) (bit >>> 6)] |= 1L << bit;
            }
        }
        return words;
    }

    public static boolean mightContain(MappedFile f, long offset, long bits, int k, long key) {
        long h1 = mix(key);
        long h2 = mix(h1) | 1;
        for (int i = 0; i < k; i++) {
            long bit = Long.remainderUnsigned(h1 + i * h2, bits);
            if ((f.getLong(offset + (bit >>> 6) * 8) & (1L << bit)) == 0) {
                return false;
            }
        }
        return true;
    }

    /** SplitMix64 finalizer. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
