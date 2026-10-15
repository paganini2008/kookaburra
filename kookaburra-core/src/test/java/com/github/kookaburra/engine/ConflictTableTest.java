package com.github.kookaburra.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conflict table against a reference model: records, overwrites, pruning in commit order, growth and shrinking
 * of the open-addressing map (backward-shift deletion) and of the ring.
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
class ConflictTableTest {

    @Test
    void behavesLikeAMapOfLastSeqs() {
        Random r = new Random(7);
        ConflictTable table = new ConflictTable();
        Map<Long, Long> model = new HashMap<>();
        ArrayDeque<long[]> log = new ArrayDeque<>();
        long seq = 0;
        for (int round = 0; round < 40; round++) {
            int writes = 1 + r.nextInt(400);
            for (int i = 0; i < writes; i++) {
                long oid = 1 + r.nextInt(round % 2 == 0 ? 50 : 5_000);      // dense and sparse keys: long probe runs
                seq++;
                table.record(oid, seq);
                model.put(oid, seq);
                log.add(new long[]{seq, oid});
            }
            long horizon = seq - r.nextInt(writes + 1);
            table.prune(horizon);
            while (!log.isEmpty() && log.peek()[0] <= horizon) {
                long[] e = log.poll();
                if (model.get(e[1]) == e[0]) {
                    model.remove(e[1]);
                }
            }
            assertThat(table.size()).isEqualTo(log.size());
            assertThat(table.distinct()).isEqualTo(model.size());
            for (long oid = 1; oid <= 5_000; oid += 1 + r.nextInt(7)) {
                assertThat(table.lastSeq(oid)).as("oid %d", oid).isEqualTo(model.getOrDefault(oid, -1L));
            }
        }
        table.prune(Long.MAX_VALUE);
        assertThat(table.size()).isZero();
        assertThat(table.distinct()).isZero();
        assertThat(table.lastSeq(1)).isEqualTo(-1);
        table.prune(Long.MAX_VALUE);                                        // empty prune
        table.record(42, seq + 1);
        assertThat(table.lastSeq(42)).isEqualTo(seq + 1);
    }
}
