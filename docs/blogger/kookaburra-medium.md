# Kookaburra: Query Java Objects with Streams, Accelerated by RoaringBitmap

*An embedded object database for Java 17+: write queries like streams, and they execute like a database.*

![Kookaburra](https://paganini2008.github.io/kookaburra/assets/logo_256.png)

## 1. Overview

**Kookaburra**: *query Java objects like a database: bitmaps underneath, streams on top.*

An embedded object database for Java 17+. Annotated classes in, type-safe chained queries or JPQL-style OQL out, executed on RoaringBitmap indexes over memory-mapped files. Transactions at three isolation levels, unique and foreign-key constraints, exact decimals, online backup, and a desktop tool, Kookaburra Studio.

![Kookaburra Studio, the desktop admin tool](https://paganini2008.github.io/kookaburra/assets/studio-data.png)

**Watch the 2:33 video tutorial** (English subtitles):

[![Watch the tutorial](https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial-poster.jpg)](https://paganini2008.github.io/kookaburra/#tutorial)

[Watch on the project website](https://paganini2008.github.io/kookaburra/#tutorial) · [MP4](https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4)

## 2. What Problem Does It Solve

Keeping your own objects usually means an ORM plus a relational database; the light alternative, `stream().filter()`, scans everything on every query. Kookaburra sits in between:

- **No mapping layer**: annotated objects are stored directly
- **No full scans**: bitmap and ordinal indexes, milliseconds on 10 million objects
- **No string field names**: `where(Book::getYear)` or a generated `Book_` metamodel
- **No duplicates or dangling references**: `@Unique`, `@ForeignKey` with RESTRICT, CASCADE or SET_NULL
- **Safe multi-object changes**: READ COMMITTED, SNAPSHOT and SERIALIZABLE transactions
- **Exact money**: `BigDecimal` fields, compared by value
- **Backups while writing**: `db.backup(dir)`
- **Not a black box**: Kookaburra Studio browses, edits, queries and backs up any database

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
- **Field types**: `boolean`, all integer types, `float` / `double`, `BigDecimal`, `String`, enums, `LocalDate` / `LocalDateTime` / `Instant`, `Ref<T>`, lists and sets of these, `@Embeddable` classes (and lists / maps of them), `Map`, `byte[]`, any `Serializable`

## 5. How It Works

![Architecture](https://paganini2008.github.io/kookaburra/assets/architecture.png)

Every write passes one sequencer thread, which checks conflicts and constraints before the WAL:

![Write path](https://paganini2008.github.io/kookaburra/assets/write-path.png)

- **Query APIs**: typed fields, OQL and dynamic access compile to one predicate plan.
- **Executor**: conditions on Roaring postings and sorted ordinals; index-order top-N.
- **Write path**: callbacks, conflicts, constraints, then the WAL with one fsync per group.
- **Storage**: immutable memory-mapped segments merged by tiered compaction.

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

**Example 3: constraints, decimals and callbacks**

```java
@Entity
public class Order {
    @Id long oid;
    @Unique String number;
    @ForeignKey(onDelete = OnDelete.CASCADE) Ref<Customer> customer;
    @Index BigDecimal total;
    Instant created;

    @PrePersist void stamp() { created = Instant.now(); }
}
```

```text
insert a second "A-1"        → ConstraintViolationException: duplicate value 'A-1' for unique Order.number
sum(total) of 19.90 + 10.15  → 30.05 (exact)
db.referrers(Order.class, ann) → [A-1, A-2]
delete customer Ann          → her orders go in the same commit
```

![Unique and foreign-key constraints](https://paganini2008.github.io/kookaburra/assets/foreign-keys.png)

**Example 4: SERIALIZABLE stops write skew**

Two doctors are on call; each one goes off call if the other is still on call.

```java
db.inTransaction(TxOptions.defaults().isolation(Isolation.SERIALIZABLE), tx -> {
    long onCall = tx.query(Doctor.class).where(Doctor::isOnCall).eq(true).count();
    if (onCall > 1) {
        tx.update(tx.get(Doctor.class, me).offCall());
    }
});
```

Under SNAPSHOT both commit and nobody is on call. Under SERIALIZABLE the second commit fails with a "serialization failure", is retried, and one doctor stays on call.

![Isolation levels](https://paganini2008.github.io/kookaburra/assets/isolation.png)

**Example 5: online backup**

```java
BackupInfo info = db.backup(Path.of("backups/2026-10-17"));   // writers keep going
try (ObjectDB copy = ObjectDB.open(info.path())) { … }        // restore = open
```

![Online backup](https://paganini2008.github.io/kookaburra/assets/backup.png)

**Example 6: nested objects and large values**

```java
db.oql("SELECT s.code FROM Shipment s WHERE s.route.to.city = 'Perth'").list();        // [S-1]
db.oql("SELECT s.code FROM Shipment s WHERE 'kiwi' MEMBER OF s.items.sku").list();     // [S-2]
db.insert(new Document("scan", text, Files.readAllBytes(Path.of("scan.png"))));        // MiB-sized values
```

In Studio: the objects referencing the selected one, the constraints of every field, nested values as a tree.

![Backlinks in Studio](https://paganini2008.github.io/kookaburra/assets/studio-backlinks.png)

![Constraints in Studio](https://paganini2008.github.io/kookaburra/assets/studio-constraints.png)

## 7. Configuration

All settings live in one file, `kookaburra.properties`:

```properties
kookaburra.mem-segment-bytes=64MB        # in-memory segment size before flush
kookaburra.segment-max-bytes=512MB       # compaction output limit
kookaburra.durability=SYNC               # SYNC = fsync per commit group, ASYNC = page cache
kookaburra.group-commit-micros=200       # wait to batch more commits into one fsync
kookaburra.compaction-garbage-ratio=0.3  # rewrite a segment at this share of deleted rows
kookaburra.transactions=false            # true enables db.begin() / db.inTransaction()
kookaburra.tx-isolation=SNAPSHOT         # READ_COMMITTED, SNAPSHOT or SERIALIZABLE
kookaburra.tx-timeout=60s                # open transactions older than this are rolled back
kookaburra.data=                         # Studio: default database (empty = data/ next to the jar)
```

## 8. Performance

Environment: Apple M2 Max (12 cores), 32 GB, internal SSD (fsync about 4 ms), JDK 17, `-Xmx8g`, **10 million objects**, measured with the `kookaburra-bench` harness (warm-up, p50, consistency checks after every phase).

```text
Operation (10 M objects)                           p50
-------------------------------------------------  -------------------
bulk insert, batches of 100 (ASYNC)                696 k objects/s
durable single inserts, 16 / 128 threads (SYNC)    3.2 k/s / 21.2 k/s
get(oid)                                           0.6 to 1.1 µs
bitmap equality count                              0.24 ms
equality + range + limit 100                       0.21 ms
top-10 by indexed column, 2.3 M matches            0.39 ms
GROUP BY city COUNT(*)                             4.0 ms
unique-key lookup (OQL)                            0.12 ms
full scan, unindexed predicate                     0.81 s
transfers, 16 threads: SNAPSHOT / RC / SERIAL.     136 / 142 / 140 k/s
atomic increment of one hot counter, 16 threads    265 k/s, no lost updates
insert with @Unique / without (in memory)          235 / 258 k/s
online backup of 10.5 M objects                    25.4 s, writers not blocked
OQL DELETE of all objects / disk afterwards        1.32 M/s / 0 B after compact()
reopen / heap after reopen                         2.0 ms / 6.5 MiB
```

## 9. Limitations & Trade-offs

- Durable single writes are bound by fsync; batch or use `ASYNC` for bulk loads.
- Single-collection queries only, no joins: use references with `fetch`, or the semi-join `in(...)`.
- Optimistic transactions: conflicts surface at commit and are retried, never blocked.
- Constraints are single-field (no composite unique keys yet).
- Not a fit for several processes writing one directory, for the SQL / JDBC / BI ecosystem, or for terabyte-scale data.

## 10. Summary

1. Stream-style code, database-style execution.
2. Type-safe fields from method references or a generated metamodel.
3. OQL covers the single-table SQL-92 core.
4. Three isolation levels; SERIALIZABLE closes write skew without locks.
5. `@Unique` and `@ForeignKey` with ON DELETE actions, checked atomically at commit.
6. Exact decimals, nested objects, large values.
7. Lifecycle callbacks and listeners, JPA style.
8. Online backup of a consistent snapshot; restore = open.
9. Sub-millisecond to a few milliseconds on 10 M objects; instant reopen, tiny heap.
10. One Studio jar; every feature tested in memory and on disk, coverage ≥ 90 %.

*Source and docs: [github.com/paganini2008/kookaburra](https://github.com/paganini2008/kookaburra) · [paganini2008.github.io/kookaburra](https://paganini2008.github.io/kookaburra/)*
