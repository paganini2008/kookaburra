package com.github.kookaburra.engine;

import com.github.kookaburra.exec.Cond;
import com.github.kookaburra.exec.Executor;

import java.util.Map;
import java.util.Set;

/**
 * What a SERIALIZABLE transaction read (docs/transaction-design.md §11.3): objects by OID and query conditions per
 * collection, with the materializer residual conditions need (a read without a known condition is recorded as
 * {@code TRUE}: the whole collection).
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
record ReadSet(Set<Long> oids, Map<Query, Executor.Materializer> queries) {

    /** One query condition: matching documents of these classes were read. */
    record Query(String collection, Set<Integer> types, Cond cond) {
    }

}
