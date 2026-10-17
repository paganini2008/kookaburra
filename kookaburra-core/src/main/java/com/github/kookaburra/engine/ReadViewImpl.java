package com.github.kookaburra.engine;

import com.github.kookaburra.exec.ReadView;
import com.github.kookaburra.exec.SegmentView;

import java.util.List;

/**
 * A (version, sequence) pair taken consistently (storage-design §7).
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class ReadViewImpl implements ReadView {

    private final Version version;
    private final long seq;

    ReadViewImpl(Version version, long seq) {
        this.version = version;
        this.seq = seq;
    }

    @Override
    public long seq() {
        return seq;
    }

    @Override
    public List<SegmentView> views(String collection) {
        return version.views(collection);
    }

    @Override
    public void close() {
        // Segment lifetime tracking arrives with on-disk segments.
    }
}
