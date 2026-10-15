package com.github.kookaburra.engine;

/**
 * OID → sequence number of its last committed change, kept only for changes newer than the oldest open
 * transaction's snapshot (docs/transaction-design.md §11.2). Primitive open addressing (linear probing,
 * backward-shift deletion) plus a commit-ordered ring of {@code (seq, oid)} for pruning: about 32 bytes per
 * entry, O(1) record / lookup / prune. Sequencer thread only.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class ConflictTable {

    private long[] keys = new long[16];
    private long[] values = new long[16];
    private int count;

    private long[] ringSeq = new long[16];
    private long[] ringOid = new long[16];
    private int head;
    private int size;

    /** Records that {@code oid} changed at {@code seq} (seqs are recorded in ascending order). */
    void record(long oid, long seq) {
        put(oid, seq);
        if (size == ringSeq.length) {
            growRing();
        }
        int tail = (head + size) & (ringSeq.length - 1);
        ringSeq[tail] = seq;
        ringOid[tail] = oid;
        size++;
    }

    /** Sequence number of the last recorded change of {@code oid}, or -1. */
    long lastSeq(long oid) {
        int i = slot(oid);
        while (keys[i] != 0) {
            if (keys[i] == oid) {
                return values[i];
            }
            i = (i + 1) & (keys.length - 1);
        }
        return -1;
    }

    /** Forgets changes with {@code seq <= upTo}: no open transaction can conflict with them any more. */
    void prune(long upTo) {
        while (size > 0 && ringSeq[head] <= upTo) {
            long oid = ringOid[head];
            if (lastSeq(oid) == ringSeq[head]) {
                remove(oid);
            }
            head = (head + 1) & (ringSeq.length - 1);
            size--;
        }
        if (size == 0) {
            head = 0;
        }
    }

    /** Recorded changes not yet pruned (ring entries; the memory bound of the table). */
    int size() {
        return size;
    }

    /** Distinct OIDs in the table. */
    int distinct() {
        return count;
    }

    // ---------------------------------------------------------------- open addressing (key 0 = empty; OIDs are > 0)

    private int slot(long oid) {
        long h = oid * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & (keys.length - 1);
    }

    private void put(long oid, long seq) {
        if ((count + 1) * 2 > keys.length) {
            rehash(keys.length * 2);
        }
        int i = slot(oid);
        while (keys[i] != 0) {
            if (keys[i] == oid) {
                values[i] = seq;
                return;
            }
            i = (i + 1) & (keys.length - 1);
        }
        keys[i] = oid;
        values[i] = seq;
        count++;
    }

    private void remove(long oid) {
        int i = slot(oid);
        while (keys[i] != oid) {
            if (keys[i] == 0) {
                return;
            }
            i = (i + 1) & (keys.length - 1);
        }
        keys[i] = 0;
        count--;
        // backward shift: move later entries of the probe run into the hole so lookups never stop early
        int hole = i;
        int j = (i + 1) & (keys.length - 1);
        while (keys[j] != 0) {
            int home = slot(keys[j]);
            boolean movable = hole <= j ? home <= hole || home > j : home <= hole && home > j;
            if (movable) {
                keys[hole] = keys[j];
                values[hole] = values[j];
                keys[j] = 0;
                hole = j;
            }
            j = (j + 1) & (keys.length - 1);
        }
        if (keys.length > 16 && count * 8 < keys.length) {
            rehash(keys.length / 2);
        }
    }

    private void rehash(int capacity) {
        long[] k = keys;
        long[] v = values;
        keys = new long[capacity];
        values = new long[capacity];
        count = 0;
        for (int i = 0; i < k.length; i++) {
            if (k[i] != 0) {
                put(k[i], v[i]);
            }
        }
    }

    private void growRing() {
        long[] s = new long[ringSeq.length * 2];
        long[] o = new long[ringOid.length * 2];
        for (int i = 0; i < size; i++) {
            int from = (head + i) & (ringSeq.length - 1);
            s[i] = ringSeq[from];
            o[i] = ringOid[from];
        }
        ringSeq = s;
        ringOid = o;
        head = 0;
    }
}
