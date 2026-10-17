package com.github.kookaburra.engine;

import com.github.kookaburra.mapping.Record;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Committed changes with their before / after records, kept while SERIALIZABLE transactions are open so their
 * read predicates can be validated at commit (docs/transaction-design.md §11.3). Logging starts when the first
 * SERIALIZABLE transaction registers and stops when the last one ends; a transaction whose snapshot predates the
 * start of continuous logging cannot be validated and is aborted (retryable). Sequencer thread only.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class ChangeLog {

    /** One committed change; {@code before} is null for inserts, {@code after} null for deletes. */
    record Change(long seq, String collection, long oid, Record before, Record after) {
    }

    private final ArrayDeque<Change> changes = new ArrayDeque<>();
    private boolean active;
    /** First sequence number logged continuously ({@code Long.MAX_VALUE} while inactive). */
    private long coveredFrom = Long.MAX_VALUE;

    /** Called before each write request with its sequence number: switches logging on or off. */
    void track(boolean on, long seq) {
        if (on && !active) {
            active = true;
            coveredFrom = seq;
        } else if (!on && active) {
            active = false;
            coveredFrom = Long.MAX_VALUE;
            changes.clear();
        }
    }

    boolean active() {
        return active;
    }

    void record(long seq, String collection, long oid, Record before, Record after) {
        if (active) {
            changes.add(new Change(seq, collection, oid, before, after));
        }
    }

    /** Whether every change after {@code snapshot} is logged. */
    boolean covers(long snapshot) {
        return active && coveredFrom <= snapshot + 1;
    }

    /** Changes of {@code collection} (all collections when null) committed after {@code snapshot}. */
    List<Change> since(String collection, long snapshot) {
        List<Change> out = new ArrayList<>();
        for (Change c : changes) {
            if (c.seq() > snapshot && (collection == null || c.collection().equals(collection))) {
                out.add(c);
            }
        }
        return out;
    }

    /** Forgets changes with {@code seq <= upTo}. */
    void prune(long upTo) {
        while (!changes.isEmpty() && changes.peekFirst().seq() <= upTo) {
            changes.pollFirst();
        }
    }

    int size() {
        return changes.size();
    }
}
