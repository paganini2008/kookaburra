# Kookaburra benchmark

| Setting | Value |
|---|---|
| objects | 10,000,000 |
| threads / phase seconds | 16 / 20 |
| JVM | Java HotSpot(TM) 64-Bit Server VM 17.0.12 |
| OS / CPUs / max heap | Mac OS X aarch64 / 12 / 8192.0 MiB |

| Section | Metric | Result | Target | Met |
|---|---|---|---|---|
| Write | bulk insert, batches of 100, ASYNC (1 thread) | 484.9 k/s (20.6 s) | ≥ 300 k/s | ✅ |
| Write | flush() remaining MemSegments | 5240.3 ms |  |  |
| Write | disk fsync (4 KiB write + force) p50 / p99 | 4.025 ms / 7.002 ms |  |  |
| Write | SYNC ceiling ≈ concurrent writers × 0.2 k/s (1 fsync per group) |  |  |  |
| Write | single insert, SYNC, 16 threads (group commit) | 3.1 k/s |  |  |
| Write | single insert latency p50 / p99, 16 threads | 4.853 ms / 10.4 ms |  |  |
| Write | single insert, SYNC, 128 threads (group commit) | 19.7 k/s |  |  |
| Write | single insert latency p50 / p99, 128 threads | 5.415 ms / 15.3 ms |  |  |
| Read | get(oid) from segment p50 / p99 | 0.71 µs / 2.42 µs | ≤ 10 µs | ✅ |
| Read | get(oid) from MemSegment + segments p50 / p99 | 0.67 µs / 2.00 µs | ≤ 2 µs | ✅ |
| Query | count() all [segments] p50 / p99 | 0.025 ms / 0.056 ms |  |  |
| Query | count(city = c) bitmap [segments] p50 / p99 | 0.296 ms / 0.411 ms | ≤ 5 ms | ✅ |
| Query | city = c AND age BETWEEN 30 AND 40, limit 100 [segments] p50 / p99 | 0.195 ms / 0.279 ms | ≤ 20 ms | ✅ |
| Query | top-10 by age DESC over 23% matches (tags ∋ 'vip') [segments] p50 / p99 | 0.597 ms / 0.648 ms | ≤ 50 ms / 1 M | ✅ |
| Query | GROUP BY city COUNT(*) [segments] p50 / p99 | 3.902 ms / 4.016 ms | ≤ 100 ms / 10 M | ✅ |
| Query | tags CONTAINS 'vip' AND active, count [segments] p50 / p99 | 153.3 ms / 163.0 ms |  |  |
| Query | unindexed score > x, count (row scan) [segments] p50 / p99 | 775.1 ms / 790.6 ms |  |  |
| Query | OQL GROUP BY city, AVG(age) WHERE age > 40 [segments] p50 / p99 | 252.6 ms / 298.7 ms |  |  |
| Query | OQL SELECT m WHERE name = '…' [segments] p50 / p99 | 0.112 ms / 0.476 ms |  |  |
| Query | count() all [after compaction] p50 / p99 | 7.79 µs / 0.014 ms |  |  |
| Query | count(city = c) bitmap [after compaction] p50 / p99 | 0.559 ms / 1.128 ms | ≤ 5 ms | ✅ |
| Query | city = c AND age BETWEEN 30 AND 40, limit 100 [after compaction] p50 / p99 | 0.182 ms / 0.235 ms | ≤ 20 ms | ✅ |
| Query | top-10 by age DESC over 23% matches (tags ∋ 'vip') [after compaction] p50 / p99 | 0.836 ms / 1.973 ms | ≤ 50 ms / 1 M | ✅ |
| Query | GROUP BY city COUNT(*) [after compaction] p50 / p99 | 4.365 ms / 4.623 ms | ≤ 100 ms / 10 M | ✅ |
| Query | tags CONTAINS 'vip' AND active, count [after compaction] p50 / p99 | 174.1 ms / 198.4 ms |  |  |
| Query | unindexed score > x, count (row scan) [after compaction] p50 / p99 | 852.1 ms / 858.8 ms |  |  |
| Query | OQL GROUP BY city, AVG(age) WHERE age > 40 [after compaction] p50 / p99 | 264.1 ms / 271.4 ms |  |  |
| Query | OQL SELECT m WHERE name = '…' [after compaction] p50 / p99 | 0.064 ms / 0.085 ms |  |  |
| Mixed | reads/s (12 readers + 4 writers, 20 s) | 0.6 k/s |  |  |
| Mixed | read latency p50 / p99 | 0.472 ms / 149.3 ms |  |  |
| Mixed | writes/s (90 % update, 10 % insert, SYNC) | 0.7 k/s |  |  |
| Mixed | write latency p50 / p99 | 4.108 ms / 21.5 ms |  |  |
| Mixed | optimistic-lock conflicts | 0 |  |  |
| Maintenance | compact() all collections | 10123.9 ms |  |  |
| Recovery | reopen after bulk load (segments only) | 20.6 ms |  |  |
| Recovery | reopen (no WAL to replay) | 1.552 ms |  |  |
| Storage | disk size after load | 1413.1 MiB (148 B/object) |  |  |
| Storage | segments after load | 18 |  |  |
| Storage | WAL size after load | 0.0 MiB |  |  |
| Storage | disk size after compaction | 1466.0 MiB (147 B/object) |  |  |
| Storage | segments after compaction | 4 |  |  |
| Storage | WAL size after compaction | 0.0 MiB |  |  |
| Memory | heap used after reopen + GC | 6.5 MiB |  |  |
| Memory | max heap | 8192.0 MiB |  |  |
| Memory | live threads | 7 |  |  |

**Problems**: none
