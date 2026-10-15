# Kookaburra benchmark

| Setting | Value |
|---|---|
| objects | 10,000,000 |
| threads / phase seconds | 16 / 20 |
| JVM | Java HotSpot(TM) 64-Bit Server VM 17.0.12 |
| OS / CPUs / max heap | Mac OS X aarch64 / 12 / 8192.0 MiB |

| Section | Metric | Result | Target | Met |
|---|---|---|---|---|
| Write | bulk insert, batches of 100, ASYNC (1 thread) | 631.5 k/s (15.8 s) | ≥ 300 k/s | ✅ |
| Write | flush() remaining MemSegments | 5195.1 ms |  |  |
| Write | disk fsync (4 KiB write + force) p50 / p99 | 4.004 ms / 5.204 ms |  |  |
| Write | SYNC ceiling ≈ concurrent writers × 0.2 k/s (1 fsync per group) |  |  |  |
| Write | single insert, SYNC, 16 threads (group commit) | 3.7 k/s |  |  |
| Write | single insert latency p50 / p99, 16 threads | 4.019 ms / 8.081 ms |  |  |
| Write | single insert, SYNC, 128 threads (group commit) | 27.8 k/s |  |  |
| Write | single insert latency p50 / p99, 128 threads | 4.167 ms / 8.563 ms |  |  |
| Read | get(oid) from segment p50 / p99 | 0.75 µs / 1.25 µs | ≤ 10 µs | ✅ |
| Read | get(oid) from MemSegment + segments p50 / p99 | 0.75 µs / 2.29 µs | ≤ 2 µs | ✅ |
| Query | count() all [segments] p50 / p99 | 0.022 ms / 0.159 ms |  |  |
| Query | count(city = c) bitmap [segments] p50 / p99 | 0.238 ms / 0.317 ms | ≤ 5 ms | ✅ |
| Query | city = c AND age BETWEEN 30 AND 40, limit 100 [segments] p50 / p99 | 0.209 ms / 0.761 ms | ≤ 20 ms | ✅ |
| Query | top-10 by age DESC over 23% matches (tags ∋ 'vip') [segments] p50 / p99 | 0.403 ms / 0.589 ms | ≤ 50 ms / 1 M | ✅ |
| Query | GROUP BY city COUNT(*) [segments] p50 / p99 | 4.118 ms / 4.514 ms | ≤ 100 ms / 10 M | ✅ |
| Query | tags CONTAINS 'vip' AND active, count [segments] p50 / p99 | 160.1 ms / 167.9 ms |  |  |
| Query | unindexed score > x, count (row scan) [segments] p50 / p99 | 763.2 ms / 771.2 ms |  |  |
| Query | OQL GROUP BY city, AVG(age) WHERE age > 40 [segments] p50 / p99 | 245.8 ms / 302.3 ms |  |  |
| Query | OQL SELECT m WHERE name = '…' [segments] p50 / p99 | 0.114 ms / 0.406 ms |  |  |
| Query | count() all [after compaction] p50 / p99 | 5.67 µs / 7.00 µs |  |  |
| Query | count(city = c) bitmap [after compaction] p50 / p99 | 0.274 ms / 0.285 ms | ≤ 5 ms | ✅ |
| Query | city = c AND age BETWEEN 30 AND 40, limit 100 [after compaction] p50 / p99 | 0.219 ms / 0.283 ms | ≤ 20 ms | ✅ |
| Query | top-10 by age DESC over 23% matches (tags ∋ 'vip') [after compaction] p50 / p99 | 0.256 ms / 0.338 ms | ≤ 50 ms / 1 M | ✅ |
| Query | GROUP BY city COUNT(*) [after compaction] p50 / p99 | 4.119 ms / 4.246 ms | ≤ 100 ms / 10 M | ✅ |
| Query | tags CONTAINS 'vip' AND active, count [after compaction] p50 / p99 | 182.4 ms / 189.3 ms |  |  |
| Query | unindexed score > x, count (row scan) [after compaction] p50 / p99 | 854.9 ms / 856.7 ms |  |  |
| Query | OQL GROUP BY city, AVG(age) WHERE age > 40 [after compaction] p50 / p99 | 291.9 ms / 293.2 ms |  |  |
| Query | OQL SELECT m WHERE name = '…' [after compaction] p50 / p99 | 0.064 ms / 0.081 ms |  |  |
| Mixed | reads/s (12 readers + 4 writers, 20 s) | 0.5 k/s |  |  |
| Mixed | read latency p50 / p99 | 0.550 ms / 159.7 ms |  |  |
| Mixed | writes/s (90 % update, 10 % insert, SYNC) | 0.8 k/s |  |  |
| Mixed | write latency p50 / p99 | 4.157 ms / 22.2 ms |  |  |
| Mixed | optimistic-lock conflicts | 0 |  |  |
| Transactions | transfer (read 2, update 2), 16 threads, in memory | 134.8 k/s | ≥ 50 k/s | ✅ |
| Transactions | transfer latency p50 / p99 (incl. retries) | 0.062 ms / 2.280 ms |  |  |
| Transactions | conflict retries per commit | 0.022 |  |  |
| Transactions | hot counter increment, 16 threads (never conflicts) | 250.5 k/s | no lost updates | ✅ |
| Transactions | count(city = c) outside a transaction p50 / p99 | 0.491 ms / 0.583 ms |  |  |
| Transactions | count(city = c) in a transaction with 100 own updates p50 / p99 | 0.601 ms / 0.712 ms |  |  |
| Maintenance | compact() all collections | 11365.1 ms |  |  |
| Recovery | reopen after bulk load (segments only) | 15.7 ms |  |  |
| Recovery | reopen (no WAL to replay) | 2.392 ms |  |  |
| Storage | disk size after load | 1413.1 MiB (148 B/object) |  |  |
| Storage | segments after load | 18 |  |  |
| Storage | WAL size after load | 0.0 MiB |  |  |
| Storage | disk size after compaction | 1485.4 MiB (147 B/object) |  |  |
| Storage | segments after compaction | 4 |  |  |
| Storage | WAL size after compaction | 0.0 MiB |  |  |
| Storage | disk size after deleting everything | 0.0 MiB (no objects) |  |  |
| Storage | segments after deleting everything | 0 |  |  |
| Storage | WAL size after deleting everything | 0.0 MiB |  |  |
| Memory | heap used after reopen + GC | 6.4 MiB |  |  |
| Memory | max heap | 8192.0 MiB |  |  |
| Memory | live threads | 7 |  |  |
| Delete | OQL DELETE of all 10,631,281 objects, 50 statements (SYNC) | 1.16 M/s (9.2 s) |  |  |
| Delete | compact() after deleting everything | 34.8 ms |  |  |

**Problems**: none
