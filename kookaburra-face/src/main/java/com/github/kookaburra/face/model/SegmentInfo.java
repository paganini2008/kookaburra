package com.github.kookaburra.face.model;

/**
 * One storage segment of a collection, as listed on the Storage tab.
 *
 * @param collection simple name of the collection (root class)
 * @param label      segment label ({@code mem#3}, {@code seg#12})
 * @param kind       {@code MEMORY} (MemSegment, not yet flushed) or {@code DISK} (immutable segment file)
 * @param docs       stored documents, including deleted and superseded versions
 * @param live       documents visible at the current snapshot
 * @param bytes      segment file size; 0 for MemSegments and in-memory databases
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
public record SegmentInfo(String collection, String label, String kind, int docs, long live, long bytes) {

    /** Share of stored documents that are dead (deleted or superseded): what compaction would reclaim. */
    public double garbage() {
        return docs == 0 ? 0 : (docs - live) / (double) docs;
    }
}
