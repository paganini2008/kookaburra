package com.github.kookaburra.engine;

/**
 * OID layout: {@code 0 · classId(16) · sequence(47)} (design §3.2).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public class Oids {

    public static final int SEQ_BITS = 47;
    public static final long MAX_SEQ = (1L << SEQ_BITS) - 1;

    private Oids() {
    }

    public static long make(int classId, long seq) {
        if (classId <= 0 || classId > 0xFFFF || seq <= 0 || seq > MAX_SEQ) {
            throw new IllegalArgumentException("invalid OID parts " + classId + "/" + seq);
        }
        return ((long) classId << SEQ_BITS) | seq;
    }

    public static int classId(long oid) {
        return (int) (oid >>> SEQ_BITS);
    }

    public static long seq(long oid) {
        return oid & MAX_SEQ;
    }
}
