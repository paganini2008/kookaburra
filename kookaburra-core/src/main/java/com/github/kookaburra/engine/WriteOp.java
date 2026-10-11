package com.github.kookaburra.engine;

import com.github.kookaburra.mapping.EntityType;
import com.github.kookaburra.mapping.Record;

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
    record Insert(EntityType<?> type, Object entity, Record record, byte[] row) implements WriteOp {
    }

    record Update(EntityType<?> type, Object entity, long oid, long expected, Record record, byte[] row)
            implements WriteOp {
    }

    record Delete(long oid, long expected, EntityType<?> type, Object entity) implements WriteOp {
    }
}
