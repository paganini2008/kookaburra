# Kookaburra: Query Java Objects with Streams, Accelerated by RoaringBitmap

*An embedded object database for Java 17+: write queries like streams, and they execute like a database.*

![Kookaburra](https://paganini2008.github.io/kookaburra/assets/logo_256.png)

## 1. Overview

**Kookaburra**: *Query Java objects like a database — bitmaps underneath, streams on top.*

Kookaburra is an embedded object database for Java 17+. You store plain annotated classes and query them with a type-safe, Stream-like API or JPQL-style OQL. Filters, sorts and aggregates run on RoaringBitmap indexes over memory-mapped segment files.

![Kookaburra Studio, the desktop admin tool](https://paganini2008.github.io/kookaburra/assets/studio-data.png)

## 2. What Problem Does It Solve

Keeping and querying your own objects usually means an ORM plus a relational database: mappings, schemas, SQL and sometimes a server. The lightweight alternative, a `List` and `stream().filter()`, scans everything on every query and keeps all data in the heap.

Kookaburra sits in between:

- **No mapping layer**: annotated objects are stored directly
- **No full scans**: conditions run on bitmap and sorted-dictionary indexes, in milliseconds on 10 million objects
- **No string field names**: `where(Book::getYear)` or a generated `Book_` metamodel
- **Not a black box**: Kookaburra Studio lets you browse, edit, query and export

## 3. Quick Start

Add the dependency:

```xml
<dependency>
    <groupId>com.github.paganini2008</groupId>
    <artifactId>kookaburra-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Store and query:

```java
try (ObjectDB db = ObjectDB.open(Path.of("data"))) {
    db.batch()
      .insert(new Book("Dune", "Frank Herbert", Genre.SCIFI, 1965, 9.99, "classic", "desert"))
      .insert(new Book("Neuromancer", "William Gibson", Genre.SCIFI, 1984, 12.50, "cyberpunk"))
      .insert(new Book("Hyperion", "Dan Simmons", Genre.SCIFI, 1989, 14.20))
      .commit();

    System.out.println(db.from(Book.class)
            .filter(where(Book::getGenre).eq(Genre.SCIFI))
            .filter(where(Book::getYear).ge(1960))
            .sorted(where(Book::getYear).desc())
            .limit(2)
            .map(where(Book::getTitle))
            .toList());
}
```

Output:

```text
[Hyperion, Neuromancer]
```

## 4. Requirements

- **Java 17+**
- Runtime dependencies: RoaringBitmap 1.6.22, the ANTLR 4.13.2 runtime, SLF4J API 2.0
- Maven 3.8+ to build
- **Studio**: Java 17+; one jar for Windows x64, Linux x64 and macOS Apple Silicon (JavaFX bundled)

## 5. How It Works

![Architecture](https://paganini2008.github.io/kookaburra/assets/architecture.png)

- **Query APIs**: typed fields, OQL and dynamic access compile to the same predicate plan.
- **Executor**: pushes conditions to Roaring postings and sorted ordinals, and walks indexes in value order for top-N.
- **Write path**: a single sequencer thread logs each group of writes to the WAL with one fsync, then publishes a new MVCC snapshot.
- **Storage**: immutable, memory-mapped segment files merged by tiered compaction.

## 6. Code Examples

**Example 1: group and aggregate**

Input: six books (4 sci-fi, 1 fantasy, 1 crime).

```java
db.from(Book.class).groupBy(where(Book::getGenre))
  .agg(Q.count().as("books"), Q.avg(where(Book::getPrice)).as("avgPrice"))
  .sortedBy(Q.count().as("books").desc())
  .toList();
```

Output:

```text
SCIFI   books=4 avg=11.36
FANTASY books=1 avg=10.00
CRIME   books=1 avg=7.40
```

**Example 2: OQL and a bulk update**

```java
db.oql("SELECT b.author, b.year FROM Book b WHERE b.year < :y ORDER BY b.year").param("y", 1950).rows();
db.oql("UPDATE Book b SET b.price = b.price * 0.9 WHERE b.genre = 'CRIME'").execute();
```

Output:

```text
J.R.R. Tolkien 1937
Raymond Chandler 1939
updated=1
```

**Example 3: generated metamodel**

```java
db.from(Book.class).filter(Book_.tags.contains("classic")).filter(Book_.price.lt(10.0)).count();
```

Output: `2`

![The OQL console in Studio](https://paganini2008.github.io/kookaburra/assets/studio-query.png)

## 7. Configuration

All settings live in one file, `kookaburra.properties`:

```properties
kookaburra.mem-segment-bytes=64MB     # in-memory segment size before flush
kookaburra.segment-max-bytes=512MB    # compaction output limit
kookaburra.durability=SYNC            # SYNC = fsync per commit group, ASYNC = page cache
kookaburra.group-commit-micros=200    # wait to batch more commits into one fsync
kookaburra.auto-postings-ratio=16     # AUTO index writes postings when rows / distinct >= ratio
kookaburra.data=                      # Studio: default database (empty = data/ next to the jar)
kookaburra.log-dir=                   # Studio: logs (empty = logs/ next to the jar)
kookaburra.log-level=INFO
```

## 8. Performance

Environment: Apple M2 Max (12 cores), 32 GB, internal SSD (fsync about 4 ms), JDK 17, `-Xmx8g`, **10 million objects**, measured with the `kookaburra-bench` harness (warm-up, p50, consistency checks after every phase).

```text
Operation (10 M objects)                         p50
-----------------------------------------------  -----------------
bulk insert, batches of 100 (ASYNC)              485 k objects/s
durable single inserts, 16 / 128 threads (SYNC)  3.1 k/s / 19.7 k/s
get(oid)                                         0.7 µs
bitmap equality count                            0.3 ms
equality + range + limit 100                     0.2 ms
top-10 by indexed column, 2.3 M matches          0.6 ms
GROUP BY city COUNT(*)                           3.9 ms
unique-key lookup (OQL)                          0.11 ms
full scan, unindexed predicate                   0.78 s
reopen / heap after reopen                       1.6 ms / 6.5 MiB
```

What tuning changed:

- **GROUP BY count**: 902 ms → 3.9 ms, by counting with `andCardinality` on postings instead of visiting rows
- **Top-10 sort**: 187 ms → 0.6 ms, by walking the index in value order and stopping at N
- **Unique-key lookup**: 13 ms → 0.11 ms, using value-sorted document lists
- **Unindexed scan**: did not finish → 0.78 s, by decoding only the needed field

## 9. Limitations & Trade-offs

- Durable single writes are bound by fsync: throughput ≈ concurrent writers ÷ fsync latency. Batch or use `ASYNC` for bulk loads.
- Single-collection queries only, no joins. Use references with `fetch`, or the semi-join `in(...)`.
- No interactive transactions. You get atomic batches, snapshots and optimistic locks.
- Not a fit for several processes writing one directory, for the SQL / JDBC / BI ecosystem, or for terabyte-scale data.

## 10. Summary

1. Stream-style code, database-style execution.
2. Type-safe fields from method references or a generated metamodel.
3. OQL covers the single-table SQL-92 core.
4. WAL + group commit, MVCC snapshots, crash recovery.
5. Sub-millisecond to a few milliseconds for common queries on 10 M objects.
6. Memory-mapped segments: instant reopen, tiny heap.
7. One Studio jar for Windows, Linux and macOS.
8. One configuration file with recommended defaults.

*Source and docs: [github.com/paganini2008/kookaburra](https://github.com/paganini2008/kookaburra) · [paganini2008.github.io/kookaburra](https://paganini2008.github.io/kookaburra/)*
