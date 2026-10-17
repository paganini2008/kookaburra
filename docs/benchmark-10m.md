# Kookaburra benchmark

| Setting | Value |
|---|---|
| objects | 10,000,000 |
| threads / phase seconds | 16 / 20 |
| JVM | Java HotSpot(TM) 64-Bit Server VM 17.0.12 |
| OS / CPUs / max heap | Mac OS X aarch64 / 12 / 8192.0 MiB |

| Section | Metric | Result | Target | Met |
|---|---|---|---|---|
| Write | bulk insert, batches of 100, ASYNC (1 thread) | 695.5 k/s (14.4 s) | ≥ 300 k/s | ✅ |
| Write | flush() remaining MemSegments | 6778.5 ms |  |  |
| Write | disk fsync (4 KiB write + force) p50 / p99 | 4.022 ms / 5.152 ms |  |  |
| Write | SYNC ceiling ≈ concurrent writers × 0.2 k/s (1 fsync per group) |  |  |  |
| Write | single insert, SYNC, 16 threads (group commit) | 3.2 k/s |  |  |
| Write | single insert latency p50 / p99, 16 threads | 4.132 ms / 9.968 ms |  |  |
| Write | single insert, SYNC, 128 threads (group commit) | 21.2 k/s |  |  |
| Write | single insert latency p50 / p99, 128 threads | 5.089 ms / 11.9 ms |  |  |
| Read | get(oid) from segment p50 / p99 | 1.08 µs / 4.63 µs | ≤ 10 µs | ✅ |
| Read | get(oid) from MemSegment + segments p50 / p99 | 0.58 µs / 1.75 µs | ≤ 2 µs | ✅ |
| Query | count() all [segments] p50 / p99 | 0.022 ms / 0.056 ms |  |  |
| Query | count(city = c) bitmap [segments] p50 / p99 | 0.240 ms / 0.329 ms | ≤ 5 ms | ✅ |
| Query | city = c AND age BETWEEN 30 AND 40, limit 100 [segments] p50 / p99 | 0.208 ms / 0.288 ms | ≤ 20 ms | ✅ |
| Query | top-10 by age DESC over 23% matches (tags ∋ 'vip') [segments] p50 / p99 | 0.392 ms / 0.443 ms | ≤ 50 ms / 1 M | ✅ |
| Query | GROUP BY city COUNT(*) [segments] p50 / p99 | 4.021 ms / 13.5 ms | ≤ 100 ms / 10 M | ✅ |
| Query | tags CONTAINS 'vip' AND active, count [segments] p50 / p99 | 152.4 ms / 162.0 ms |  |  |
| Query | unindexed score > x, count (row scan) [segments] p50 / p99 | 814.6 ms / 821.9 ms |  |  |
| Query | OQL GROUP BY city, AVG(age) WHERE age > 40 [segments] p50 / p99 | 243.6 ms / 246.2 ms |  |  |
| Query | OQL SELECT m WHERE name = '…' [segments] p50 / p99 | 0.119 ms / 0.641 ms |  |  |
| Query | count() all [after compaction] p50 / p99 | 7.29 µs / 0.024 ms |  |  |
| Query | count(city = c) bitmap [after compaction] p50 / p99 | 0.588 ms / 0.661 ms | ≤ 5 ms | ✅ |
| Query | city = c AND age BETWEEN 30 AND 40, limit 100 [after compaction] p50 / p99 | 0.152 ms / 0.174 ms | ≤ 20 ms | ✅ |
| Query | top-10 by age DESC over 23% matches (tags ∋ 'vip') [after compaction] p50 / p99 | 0.508 ms / 0.591 ms | ≤ 50 ms / 1 M | ✅ |
| Query | GROUP BY city COUNT(*) [after compaction] p50 / p99 | 4.240 ms / 4.492 ms | ≤ 100 ms / 10 M | ✅ |
| Query | tags CONTAINS 'vip' AND active, count [after compaction] p50 / p99 | 171.5 ms / 185.7 ms |  |  |
| Query | unindexed score > x, count (row scan) [after compaction] p50 / p99 | 909.6 ms / 947.1 ms |  |  |
| Query | OQL GROUP BY city, AVG(age) WHERE age > 40 [after compaction] p50 / p99 | 291.3 ms / 320.5 ms |  |  |
| Query | OQL SELECT m WHERE name = '…' [after compaction] p50 / p99 | 0.063 ms / 0.077 ms |  |  |
| Mixed | reads/s (12 readers + 4 writers, 20 s) | 0.7 k/s |  |  |
| Mixed | read latency p50 / p99 | 0.485 ms / 114.1 ms |  |  |
| Mixed | writes/s (90 % update, 10 % insert, SYNC) | 0.8 k/s |  |  |
| Mixed | write latency p50 / p99 | 4.065 ms / 19.3 ms |  |  |
| Mixed | optimistic-lock conflicts | 0 |  |  |
| Transactions | transfer (read 2, update 2), SNAPSHOT, 16 threads, in memory | 136.2 k/s | ≥ 50 k/s | ✅ |
| Transactions | transfer latency p50 / p99 (incl. retries), SNAPSHOT | 0.062 ms / 2.272 ms |  |  |
| Transactions | conflict retries per commit, SNAPSHOT | 0.023 |  |  |
| Transactions | transfer (read 2, update 2), READ COMMITTED, 16 threads, in memory | 141.5 k/s | ≥ 50 k/s | ✅ |
| Transactions | transfer latency p50 / p99 (incl. retries), READ COMMITTED | 0.061 ms / 2.258 ms |  |  |
| Transactions | conflict retries per commit, READ COMMITTED | 0.023 |  |  |
| Transactions | transfer (read 2, update 2), SERIALIZABLE, 16 threads, in memory | 139.5 k/s | ≥ 50 k/s | ✅ |
| Transactions | transfer latency p50 / p99 (incl. retries), SERIALIZABLE | 0.063 ms / 2.266 ms |  |  |
| Transactions | conflict retries per commit, SERIALIZABLE | 0.023 |  |  |
| Transactions | hot counter increment, 16 threads (never conflicts) | 265.4 k/s | no lost updates | ✅ |
| Transactions | count(city = c) outside a transaction p50 / p99 | 0.736 ms / 0.907 ms |  |  |
| Transactions | count(city = c) in a transaction with 100 own updates p50 / p99 | 0.591 ms / 0.703 ms |  |  |
| Integrity | insert with @Unique e-mail vs none, batches of 100, in memory | 235.2 k/s vs 258.3 k/s | ≥ 50% of plain | ✅ |
| Integrity | insert with a checked @ForeignKey, batches of 100, in memory | 224.9 k/s |  |  |
| Integrity | delete with ON DELETE CASCADE (1 order each), batches of 100, in memory | 181.4 k/s |  |  |
| Maintenance | compact() all collections | 10428.5 ms |  |  |
| Maintenance | online backup of 10,489,357 objects (1470.3 MiB) | 25.4 s, 412.7 k/s |  |  |
| Recovery | reopen after bulk load (segments only) | 16.0 ms |  |  |
| Recovery | reopen (no WAL to replay) | 1.989 ms |  |  |
| Storage | disk size after load | 1413.1 MiB (148 B/object) |  |  |
| Storage | segments after load | 18 |  |  |
| Storage | WAL size after load | 0.0 MiB |  |  |
| Storage | disk size after compaction | 1469.7 MiB (147 B/object) |  |  |
| Storage | segments after compaction | 4 |  |  |
| Storage | WAL size after compaction | 0.0 MiB |  |  |
| Storage | disk size after deleting everything | 0.0 MiB (no objects) |  |  |
| Storage | segments after deleting everything | 0 |  |  |
| Storage | WAL size after deleting everything | 0.0 MiB |  |  |
| Memory | heap used after reopen + GC | 6.5 MiB |  |  |
| Memory | max heap | 8192.0 MiB |  |  |
| Memory | live threads | 7 |  |  |
| Delete | OQL DELETE of all 10,489,357 objects, 50 statements (SYNC) | 1.32 M/s (8.0 s) |  |  |
| Delete | compact() after deleting everything | 39.5 ms |  |  |

**Problems**: none
