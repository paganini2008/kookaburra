package com.github.kookaburra.engine;

import com.github.kookaburra.Isolation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChangeLog} coverage tracking and pruning, and the SERIALIZABLE bookkeeping of {@link TxRegistry}.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
class ChangeLogTest {

    @Test
    void logsOnlyWhileTracking() {
        ChangeLog log = new ChangeLog();
        log.record(1, "c", 7, null, null);
        assertThat(log.size()).isZero();
        assertThat(log.covers(0)).isFalse();
        log.track(true, 5);                                    // logging starts at seq 5
        assertThat(log.covers(4)).isTrue();                     // changes after 4 = from 5
        assertThat(log.covers(3)).isFalse();                    // seq 4 was not logged: cannot validate
        log.record(5, "c", 7, null, null);
        log.record(6, "d", 8, null, null);
        log.track(true, 7);                                    // still on: coverage unchanged
        assertThat(log.covers(4)).isTrue();
        assertThat(log.since("c", 4)).hasSize(1);
        assertThat(log.since(null, 5)).extracting(ChangeLog.Change::oid).containsExactly(8L);
        log.prune(5);
        assertThat(log.size()).isEqualTo(1);
        log.track(false, 8);
        assertThat(log.active()).isFalse();
        assertThat(log.size()).isZero();
        assertThat(log.covers(10)).isFalse();
    }

    @Test
    void registryCountsSerializableTransactions() {
        TxRegistry r = new TxRegistry();
        assertThat(r.evictOldestSerializable("x")).isFalse();
        TxRegistry.Entry snap = r.register(0, Isolation.SNAPSHOT);
        TxRegistry.Entry s1 = r.register(0, Isolation.SERIALIZABLE);
        TxRegistry.Entry s2 = r.register(0, Isolation.SERIALIZABLE);
        assertThat(r.serializable()).isEqualTo(2);
        assertThat(r.serializableHorizon(99)).isZero();          // pending snapshots hold everything
        snap.snapshot = 1;
        s1.snapshot = 10;
        s2.snapshot = 20;
        assertThat(r.serializableHorizon(99)).isEqualTo(10);
        assertThat(r.evictOldestSerializable("full")).isTrue();
        assertThat(s1.abortReason).contains("evicted: full");
        assertThat(r.serializableHorizon(99)).isEqualTo(20);
        r.deregister(s1);
        r.deregister(s1);                                      // twice: counted once
        assertThat(r.serializable()).isEqualTo(1);
        r.deregister(s2);
        r.deregister(snap);
        assertThat(r.serializable()).isZero();
    }
}
