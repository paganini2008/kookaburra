package com.github.kookaburra.exec;

import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.mapping.TypeRegistry;

import java.util.Collection;
import java.util.Map;

/**
 * What queries need from the database. Internal API.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public interface QueryHost {

    TypeRegistry registry();

    /** Opens a read view on the latest committed state; the caller must close it. */
    ReadView openRead();

    /** Java object of a document (subclass instance of the stored class). */
    Object materialize(SegmentView view, int doc);

    /** Schema-driven object of a document (no entity class needed). */
    DynamicObject materializeDynamic(SegmentView view, int doc);

    /** Objects by OID as of a read view (missing ones are absent). */
    Map<Long, Object> getAll(ReadView view, Collection<Long> oids);

    /** The database, for write-backs ({@code updateEach} / {@code deleteAll}). */
    ObjectDB database();
}
