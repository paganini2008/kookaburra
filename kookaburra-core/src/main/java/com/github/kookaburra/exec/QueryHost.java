package com.github.kookaburra.exec;

import com.github.kookaburra.Config;
import com.github.kookaburra.ObjectDB;
import com.github.kookaburra.dynamic.DynamicObject;
import com.github.kookaburra.embed.Embedder;
import com.github.kookaburra.embed.Reranker;
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

    /** The configured text embedder, or null. */
    default Embedder embedder() {
        return null;
    }

    /** The configured reranker, or null. */
    default Reranker reranker() {
        return null;
    }

    /** The embedding of {@code text} by the configured embedder; fails when none is configured. */
    default float[] embed(String text) {
        Embedder e = embedder();
        if (e == null) {
            throw new IllegalStateException("no embedder configured: set kookaburra.embedder=ollama and kookaburra.embedder-model,"
                    + " or Config.withEmbedder(...)");
        }
        return e.embed(text);
    }

    /** HNSW candidates per segment of approximate nearest-neighbour searches ({@code kookaburra.vector-ef-search}). */
    default int vectorEfSearch() {
        return Config.defaults().vectorEfSearch();
    }

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
