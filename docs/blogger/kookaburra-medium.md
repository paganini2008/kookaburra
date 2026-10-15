# Kookaburra: Query Java Objects with Streams, Accelerated by RoaringBitmap

*An embedded object database for Java 17+: write queries like streams, and they execute like a database.*

![Kookaburra](https://paganini2008.github.io/kookaburra/assets/logo_256.png)

## 1. Overview

**Kookaburra**: *Query Java objects like a database — bitmaps underneath, streams on top.*

Kookaburra is an embedded object database for Java 17+. You store plain annotated classes and query them with a type-safe chained API or JPQL-style OQL. Filters, sorts and aggregates run on RoaringBitmap indexes over memory-mapped segment files, with optional snapshot-isolation transactions, nested objects and large values such as images.

![Kookaburra Studio, the desktop admin tool](https://paganini2008.github.io/kookaburra/assets/studio-data.png)

**Watch the 2:33 video tutorial** (English subtitles):

[![Watch the tutorial](https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial-poster.jpg)](https://paganini2008.github.io/kookaburra/#tutorial)

[Watch on the project website](https://paganini2008.github.io/kookaburra/#tutorial) · [MP4](https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4)

## 2. What Problem Does It Solve

Keeping and querying your own objects usually means an ORM plus a relational database: mappings, schemas, SQL and sometimes a server. The lightweight alternative, a `List` and `stream().filter()`, scans everything on every query and keeps all data in the heap.

Kookaburra sits in between:

- **No mapping layer**: annotated objects are stored directly
- **No full scans**: conditions run on bitmap and sorted-dictionary indexes, in milliseconds on 10 million objects
- **No string field names**: `where(Book::getYear)` or a generated `Book_` metamodel
- **Safe multi-object changes**: optional SNAPSHOT transactions with `ConflictException` on conflicts
- **Rich models**: embedded objects at any depth, lists and maps of them, queryable by dotted paths
- **Not a black box**: Kookaburra Studio lets you browse several databases, edit in transactions, inspect nested objects, preview images, query and export

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

    List<Book> books = db.query(Book.class)
            .where(Book::getGenre).eq(Genre.SCIFI)
            .where(Book::getYear).ge(1960)
            .orderBy(Book::getYear).desc()
            .limit(2)
            .list();
    System.out.println(books.stream().map(Book::getTitle).toList());
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
db.query(Book.class).groupBy(where(Book::getGenre))
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
db.query(Book.class).filter(Book_.tags.contains("classic")).filter(Book_.price.lt(10.0)).count();
```

Output: `2`

![The OQL console in Studio](https://paganini2008.github.io/kookaburra/assets/studio-query.png)

**Example 4: transactions** (`kookaburra.transactions=true`)

Input: two accounts, ann = 100 and bob = 0.

```java
try (Transaction tx = db.begin(Isolation.SNAPSHOT)) {
    Account a = tx.get(Account.class, from);
    tx.update(a.balance(a.getBalance() - 30));
    tx.increment(Account.class, to, "balance", 30);   // atomic, never conflicts
    tx.commit();                                      // ConflictException if `a` changed meanwhile
}                                                     // not committed → rolled back
```

Output: ann = 70, bob = 30. Of two transactions that change the same object, the later commit fails with `ConflictException`.

**Example 5: nested objects**

Input: a `Shipment` with `Route route` (two levels of embedding), `List<Item> items` and `Map<String, Place> stops`.

```java
db.oql("SELECT s.code FROM Shipment s WHERE s.route.to.city = 'Perth'").list();               // [S-1]
db.query(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("pear").list(); // S-1
db.oql("SELECT s.code FROM Shipment s WHERE 'kiwi' MEMBER OF s.items.sku").list();            // [S-2]
```

A path through a list means "any element", as with JPQL `MEMBER OF`.

**Example 6: long texts and images**

```java
db.insert(new Document("scan", text, Files.readAllBytes(Path.of("scan.png"))));
```

Tested with 2-million-character Unicode texts and 5–16 MiB binaries through flush, compaction, transactions and reopen. JSON and CSV exports carry binaries as Base64.

In Studio, lists of embedded objects show as `[ 2 elements ]`, the inspector expands every level as a Key / Value / Type tree, and image fields get a preview:

![Nested objects in Studio](https://paganini2008.github.io/kookaburra/assets/studio-nested.png)

## 7. Configuration

All settings live in one file, `kookaburra.properties`:

```properties
kookaburra.mem-segment-bytes=64MB     # in-memory segment size before flush
kookaburra.segment-max-bytes=512MB    # compaction output limit
kookaburra.durability=SYNC            # SYNC = fsync per commit group, ASYNC = page cache
kookaburra.group-commit-micros=200    # wait to batch more commits into one fsync
kookaburra.auto-postings-ratio=16     # AUTO index writes postings when rows / distinct >= ratio
kookaburra.compaction-garbage-ratio=0.3  # rewrite a segment on its own at this share of deleted rows
kookaburra.transactions=false         # true enables db.begin() / db.inTransaction()
kookaburra.tx-timeout=60s             # open transactions older than this are rolled back
kookaburra.data=                      # Studio: default database (empty = data/ next to the jar)
kookaburra.log-dir=                   # Studio: logs (empty = logs/ next to the jar)
kookaburra.log-level=INFO
```

## 8. Performance

Environment: Apple M2 Max (12 cores), 32 GB, internal SSD (fsync about 4 ms), JDK 17, `-Xmx8g`, **10 million objects**, measured with the `kookaburra-bench` harness (warm-up, p50, consistency checks after every phase).

```text
Operation (10 M objects)                         p50
-----------------------------------------------  -----------------
bulk insert, batches of 100 (ASYNC)              632 k objects/s
durable single inserts, 16 / 128 threads (SYNC)  3.7 k/s / 27.8 k/s
get(oid)                                         0.75 µs
bitmap equality count                            0.24 ms
equality + range + limit 100                     0.21 ms
top-10 by indexed column, 2.3 M matches          0.40 ms
GROUP BY city COUNT(*)                           4.1 ms
unique-key lookup (OQL)                          0.11 ms
full scan, unindexed predicate                   0.76 s
transactions: transfers, 16 threads              135 k/s (0.022 retries / commit)
atomic increment of one hot counter, 16 threads  251 k/s, no lost updates
OQL DELETE of 10.6 M objects / disk afterwards   1.16 M/s / 0 B after compact()
reopen / heap after reopen                       2.4 ms / 6.4 MiB
```

What tuning changed:

- **GROUP BY count**: 902 ms → 4.1 ms, by counting with `andCardinality` on postings instead of visiting rows
- **Top-10 sort**: 187 ms → 0.40 ms, by walking the index in value order and stopping at N
- **Unique-key lookup**: 13 ms → 0.11 ms, using value-sorted document lists
- **Unindexed scan**: did not finish → 0.76 s, by decoding only the needed field
- **Space after deleting everything**: 1.5 GB kept → 0 B, with garbage-driven compaction

## 9. Limitations & Trade-offs

- Durable single writes are bound by fsync: throughput ≈ concurrent writers ÷ fsync latency. Batch or use `ASYNC` for bulk loads.
- Single-collection queries only, no joins. Use references with `fetch`, or the semi-join `in(...)`.
- Transactions are SNAPSHOT (like Oracle / PostgreSQL REPEATABLE READ), not SERIALIZABLE: write skew is possible, so guard invariants with a shared write.
- Not a fit for several processes writing one directory, for the SQL / JDBC / BI ecosystem, or for terabyte-scale data.

## 10. Summary

1. Stream-style code, database-style execution.
2. Type-safe fields from method references or a generated metamodel.
3. OQL covers the single-table SQL-92 core.
4. WAL + group commit, MVCC snapshots, crash recovery.
5. Sub-millisecond to a few milliseconds for common queries on 10 M objects.
6. Memory-mapped segments: instant reopen, tiny heap.
7. Optional SNAPSHOT transactions with read-your-writes and atomic increments.
8. Nested objects and large values (texts, images).
9. One Studio jar for Windows, Linux and macOS: several databases, transactions, a nested-object inspector.
10. Every shared test suite runs in memory and on disk, with ≥ 90 % coverage and a 10 M-object stress run.
11. One configuration file with recommended defaults.

*Source and docs: [github.com/paganini2008/kookaburra](https://github.com/paganini2008/kookaburra) · [paganini2008.github.io/kookaburra](https://paganini2008.github.io/kookaburra/)*
