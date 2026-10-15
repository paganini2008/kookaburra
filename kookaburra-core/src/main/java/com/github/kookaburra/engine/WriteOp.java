package com.github.kookaburra.engine;

import com.github.kookaburra.mapping.EntityType;
import com.github.kookaburra.mapping.Record;

import java.util.Map;

/**
 * One write inside a batch. Records and row bytes are produced on the caller's thread.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
sealed interface WriteOp {

    /** Version check value meaning "no check". */
    long ANY = -1;

    /** {@code type} is null for {@link com.github.kookaburra.dynamic.DynamicObject}s. */
    /** {@code oid} is allocated by the caller ({@link Database#allocateOid}) before the op is submitted. */
    record Insert(EntityType<?> type, Object entity, long oid, Record record, byte[] row) implements WriteOp {
    }

    record Update(EntityType<?> type, Object entity, long oid, long expected, Record record, byte[] row)
            implements WriteOp {
    }

    record Delete(long oid, long expected, EntityType<?> type, Object entity) implements WriteOp {
    }

    /** Adds numeric deltas on the latest version; resolved into an {@link Update} by the sequencer. */
    record Increment(long oid, Map<String, Number> deltas) implements WriteOp {
    }
}
