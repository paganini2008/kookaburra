# Kookaburra benchmark

| Setting | Value |
|---|---|
| objects | 10,000,000 |
| threads / phase seconds | 16 / 20 |
| JVM | Java HotSpot(TM) 64-Bit Server VM 17.0.12 |
| OS / CPUs / max heap | Mac OS X aarch64 / 12 / 8192.0 MiB |

| Section | Metric | Result | Target | Met |
|---|---|---|---|---|
| Vector | insert 100,000 × 384, batches of 100, ASYNC | 78.1 k/s |  |  |
| Vector | flush() incl. HNSW graphs (m=16, ef_construction=100) | 11164.3 ms |  |  |
| Vector | disk per vector (float32 data 1536 B) | 3,250 B |  |  |
| Vector | top-10 HNSW, ef=100 p50 / p99 | 1.034 ms / 1.734 ms | ≤ 10 ms | ✅ |
| Vector | top-10 exact scan p50 / p99 | 21.5 ms / 21.9 ms |  |  |
| Vector | top-10 HNSW, filter topic = t (2 %) p50 / p99 | 1.832 ms / 2.095 ms | ≤ 10 ms | ✅ |
| Vector | top-10 HNSW, filter topic < 25 (50 %) p50 / p99 | 2.725 ms / 6.151 ms | ≤ 10 ms | ✅ |
| Vector | recall@10 of HNSW vs exact (100 queries) | 0.996 | ≥ 0.95 | ✅ |
| Write | bulk insert, batches of 100, ASYNC (1 thread) | 575.7 k/s (17.4 s) | ≥ 300 k/s | ✅ |
| Write | flush() remaining MemSegments | 4590.7 ms |  |  |
| Write | disk fsync (4 KiB write + force) p50 / p99 | 4.041 ms / 5.733 ms |  |  |
| Write | SYNC ceiling ≈ concurrent writers × 0.2 k/s (1 fsync per group) |  |  |  |
| Write | single insert, SYNC, 16 threads (group commit) | 3.3 k/s |  |  |
| Write | single insert latency p50 / p99, 16 threads | 4.125 ms / 9.790 ms |  |  |
| Write | single insert, SYNC, 128 threads (group commit) | 24.4 k/s |  |  |
| Write | single insert latency p50 / p99, 128 threads | 4.991 ms / 10.9 ms |  |  |
| Read | get(oid) from segment p50 / p99 | 0.71 µs / 1.00 µs | ≤ 10 µs | ✅ |
| Read | get(oid) from MemSegment + segments p50 / p99 | 0.71 µs / 1.00 µs | ≤ 2 µs | ✅ |
| Query | count() all [segments] p50 / p99 | 0.022 ms / 0.063 ms |  |  |
| Query | count(city = c) bitmap [segments] p50 / p99 | 0.230 ms / 0.343 ms | ≤ 5 ms | ✅ |
| Query | city = c AND age BETWEEN 30 AND 40, limit 100 [segments] p50 / p99 | 0.188 ms / 0.623 ms | ≤ 20 ms | ✅ |
| Query | top-10 by age DESC over 23% matches (tags ∋ 'vip') [segments] p50 / p99 | 0.393 ms / 0.434 ms | ≤ 50 ms / 1 M | ✅ |
| Query | GROUP BY city COUNT(*) [segments] p50 / p99 | 3.941 ms / 4.155 ms | ≤ 100 ms / 10 M | ✅ |
| Query | tags CONTAINS 'vip' AND active, count [segments] p50 / p99 | 154.7 ms / 165.3 ms |  |  |
| Query | unindexed score > x, count (row scan) [segments] p50 / p99 | 775.2 ms / 820.8 ms |  |  |
| Query | OQL GROUP BY city, AVG(age) WHERE age > 40 [segments] p50 / p99 | 248.8 ms / 260.5 ms |  |  |
| Query | OQL SELECT m WHERE name = '…' [segments] p50 / p99 | 0.109 ms / 0.352 ms |  |  |
| Query | count() all [after compaction] p50 / p99 | 6.79 µs / 0.017 ms |  |  |
| Query | count(city = c) bitmap [after compaction] p50 / p99 | 0.506 ms / 0.556 ms | ≤ 5 ms | ✅ |
| Query | city = c AND age BETWEEN 30 AND 40, limit 100 [after compaction] p50 / p99 | 0.207 ms / 0.297 ms | ≤ 20 ms | ✅ |
| Query | top-10 by age DESC over 23% matches (tags ∋ 'vip') [after compaction] p50 / p99 | 0.249 ms / 0.280 ms | ≤ 50 ms / 1 M | ✅ |
| Query | GROUP BY city COUNT(*) [after compaction] p50 / p99 | 4.099 ms / 4.296 ms | ≤ 100 ms / 10 M | ✅ |
| Query | tags CONTAINS 'vip' AND active, count [after compaction] p50 / p99 | 181.2 ms / 202.6 ms |  |  |
| Query | unindexed score > x, count (row scan) [after compaction] p50 / p99 | 862.1 ms / 876.2 ms |  |  |
| Query | OQL GROUP BY city, AVG(age) WHERE age > 40 [after compaction] p50 / p99 | 294.6 ms / 299.6 ms |  |  |
| Query | OQL SELECT m WHERE name = '…' [after compaction] p50 / p99 | 0.066 ms / 0.083 ms |  |  |
| Mixed | reads/s (12 readers + 4 writers, 20 s) | 0.7 k/s |  |  |
| Mixed | read latency p50 / p99 | 0.517 ms / 113.4 ms |  |  |
| Mixed | writes/s (90 % update, 10 % insert, SYNC) | 0.8 k/s |  |  |
| Mixed | write latency p50 / p99 | 4.032 ms / 17.1 ms |  |  |
| Mixed | optimistic-lock conflicts | 0 |  |  |
| Transactions | transfer (read 2, update 2), SNAPSHOT, 16 threads, in memory | 135.7 k/s | ≥ 50 k/s | ✅ |
| Transactions | transfer latency p50 / p99 (incl. retries), SNAPSHOT | 0.063 ms / 2.252 ms |  |  |
| Transactions | conflict retries per commit, SNAPSHOT | 0.023 |  |  |
| Transactions | transfer (read 2, update 2), READ COMMITTED, 16 threads, in memory | 138.9 k/s | ≥ 50 k/s | ✅ |
| Transactions | transfer latency p50 / p99 (incl. retries), READ COMMITTED | 0.062 ms / 2.246 ms |  |  |
| Transactions | conflict retries per commit, READ COMMITTED | 0.023 |  |  |
| Transactions | transfer (read 2, update 2), SERIALIZABLE, 16 threads, in memory | 136.4 k/s | ≥ 50 k/s | ✅ |
| Transactions | transfer latency p50 / p99 (incl. retries), SERIALIZABLE | 0.065 ms / 2.260 ms |  |  |
| Transactions | conflict retries per commit, SERIALIZABLE | 0.023 |  |  |
| Transactions | hot counter increment, 16 threads (never conflicts) | 262.3 k/s | no lost updates | ✅ |
| Transactions | count(city = c) outside a transaction p50 / p99 | 0.778 ms / 0.893 ms |  |  |
| Transactions | count(city = c) in a transaction with 100 own updates p50 / p99 | 0.674 ms / 0.917 ms |  |  |
| Integrity | insert with @Unique e-mail vs none, batches of 100, in memory | 250.7 k/s vs 317.9 k/s | ≥ 50% of plain | ✅ |
| Integrity | insert with a checked @ForeignKey, batches of 100, in memory | 266.0 k/s |  |  |
| Integrity | delete with ON DELETE CASCADE (1 order each), batches of 100, in memory | 152.4 k/s |  |  |
| Maintenance | compact() all collections | 11221.8 ms |  |  |
| Maintenance | online backup of 10,556,069 objects (1478.0 MiB) | 25.7 s, 411.5 k/s |  |  |
| Recovery | reopen after bulk load (segments only) | 11.9 ms |  |  |
| Recovery | reopen (no WAL to replay) | 2.085 ms |  |  |
| Storage | disk size after load | 1413.1 MiB (148 B/object) |  |  |
| Storage | segments after load | 18 |  |  |
| Storage | WAL size after load | 0.0 MiB |  |  |
| Storage | disk size after compaction | 1477.1 MiB (147 B/object) |  |  |
| Storage | segments after compaction | 4 |  |  |
| Storage | WAL size after compaction | 0.0 MiB |  |  |
| Storage | disk size after deleting everything | 0.0 MiB (no objects) |  |  |
| Storage | segments after deleting everything | 0 |  |  |
| Storage | WAL size after deleting everything | 0.0 MiB |  |  |
| Memory | heap used after reopen + GC | 7.1 MiB |  |  |
| Memory | max heap | 8192.0 MiB |  |  |
| Memory | live threads | 7 |  |  |
| Delete | OQL DELETE of all 10,556,069 objects, 50 statements (SYNC) | 1.31 M/s (8.1 s) |  |  |
| Delete | compact() after deleting everything | 33.6 ms |  |  |

**Problems**: none
