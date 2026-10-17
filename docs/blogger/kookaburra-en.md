# Kookaburra: an Embedded Object Database for Java, with Type-Safe Queries

![Kookaburra](https://paganini2008.github.io/kookaburra/assets/logo_256.png)

## 1. Overview

**Kookaburra**: *query Java objects like a database, right inside your application.*

An **embedded object database for Java 17+**. Annotated classes in, type-safe chained queries or SQL-style **OQL** out, answered from indexes in milliseconds. Transactions at three isolation levels, unique and foreign-key constraints, exact decimals, online backup, and a desktop tool, **Kookaburra Studio**.

![Kookaburra Studio](https://paganini2008.github.io/kookaburra/assets/studio-data.png)

**Video tutorial (2:57, English subtitles)**:

<video controls preload="metadata" playsinline width="100%" poster="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial-poster.jpg">
  <source src="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4" type="video/mp4">
</video>

If the player does not show, [watch it on the project website](https://paganini2008.github.io/kookaburra/#tutorial) or [open the MP4](https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4).

## 2. What Problem Does It Solve

Keeping your own objects usually means an ORM plus a relational database; the light alternative, `stream().filter()`, scans everything on every query. Kookaburra sits in between: **write queries like streams, run them like a database**, in your process.

| Pain point | Kookaburra |
|---|---|
| Heavy ORM / SQL mapping | store annotated objects directly |
| `stream().filter()` scans everything | indexes: milliseconds on 10 M objects |
| Field names in strings | `where(Book::getYear)` or the generated `Book_` |
| Duplicates and dangling references | `@Unique` (one field or a combination), `@ForeignKey` with RESTRICT / CASCADE / SET_NULL |
| Several objects must change together | transactions: READ COMMITTED, SNAPSHOT, SERIALIZABLE |
| Money rounding errors | exact `BigDecimal` |
| Backups of a live store | `db.backup(dir)` while writing |
| A binary store you cannot see into | Studio: browse, edit, query, back up |

## 3. Quick Start

```xml
<dependency>
    <groupId>com.github.paganini2008</groupId>
    <artifactId>kookaburra-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

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

```text
[Hyperion, Neuromancer]
```

Studio: `mvn -f kookaburra-face/pom.xml -P dist package`, then double-click `bin/kookaburra-face.jar`.

## 4. Requirements

| Item | Requirement |
|---|---|
| Java | 17+ |
| Runtime dependencies | RoaringBitmap 1.6.22, ANTLR 4.13.2 runtime, SLF4J API 2.0 |
| Build | Maven 3.8+ |
| Studio | Java 17+; one jar for Windows x64, Linux x64 and macOS Apple Silicon (JavaFX bundled) |

Supported field types: `boolean`, all integer types, `float` / `double`, **`BigDecimal`**, `String`, enums, `LocalDate` / `LocalDateTime` / `Instant`, `Ref<T>`, lists and sets of these, `@Embeddable` classes (and lists / maps of them), `Map`, `byte[]`, any `Serializable`.

## 5. How It Works

![How Kookaburra fits into an application](https://paganini2008.github.io/kookaburra/assets/how-it-fits.png)

| You | Kookaburra |
|---|---|
| annotate plain classes | stores them in a data directory (or in memory), no server |
| query with method references or OQL | answers from indexes; `explain()` shows which one |
| write objects, batches or transactions | commits atomically and durably, checks constraints |
| open the directory in Studio, or `db.backup(dir)` | visual browsing and editing, consistent copies while running |

## 6. Code Examples

**Example 1: group and aggregate**

- Input: six books (4 sci-fi, 1 fantasy, 1 crime)
- Execution:

```java
db.query(Book.class).groupBy(where(Book::getGenre))
  .agg(Q.count().as("books"), Q.avg(where(Book::getPrice)).as("avgPrice"))
  .sortedBy(Q.count().as("books").desc())
  .toList();
```

- Output:

```text
SCIFI   books=4 avg=11.36
FANTASY books=1 avg=10.00
CRIME   books=1 avg=7.40
```

**Example 2: OQL query and bulk update**

```java
db.oql("SELECT b.author, b.year FROM Book b WHERE b.year < :y ORDER BY b.year").param("y", 1950).rows();
db.oql("UPDATE Book b SET b.price = b.price * 0.9 WHERE b.genre = 'CRIME'").execute();
```

```text
J.R.R. Tolkien 1937
Raymond Chandler 1939
updated=1
```

**Example 3: constraints, decimals, callbacks**

- Input:

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

- Execution and output:

| Code | Output |
|---|---|
| `db.insert(new Order("A-1", ann, "5"))` (A-1 exists) | `ConstraintViolationException: duplicate value 'A-1' for unique Order.number` |
| `db.query(Order.class).sum(where(Order::getTotal))` over 19.90 + 10.15 | `30.05` (exact) |
| `db.referrers(Order.class, ann)` | `[A-1, A-2]` (backlinks) |
| `db.delete(Customer.class, ann)` | Ann and her orders, one atomic commit |

![Constraints](https://paganini2008.github.io/kookaburra/assets/foreign-keys.png)

**Example 4: SERIALIZABLE stops write skew**

- Input: two doctors on call; each one goes off call if the other is still on call
- Execution:

```java
db.inTransaction(TxOptions.defaults().isolation(Isolation.SERIALIZABLE), tx -> {
    long onCall = tx.query(Doctor.class).where(Doctor::isOnCall).eq(true).count();
    if (onCall > 1) {
        tx.update(tx.get(Doctor.class, me).offCall());
    }
});
```

- Output: under SNAPSHOT both commit (nobody on call); under SERIALIZABLE the second commit fails with `ConflictException` ("serialization failure"), is retried, and one doctor stays on call

![Isolation levels](https://paganini2008.github.io/kookaburra/assets/isolation.png)

**Example 5: online backup**

```java
BackupInfo info = db.backup(Path.of("backups/2026-10-17"));   // writers keep going
try (ObjectDB copy = ObjectDB.open(info.path())) { … }        // restore = open
```

![Online backup](https://paganini2008.github.io/kookaburra/assets/backup-overview.png)

**Example 6: nested objects and large values**

```java
db.oql("SELECT s.code FROM Shipment s WHERE s.route.to.city = 'Perth'").list();        // [S-1]
db.oql("SELECT s.code FROM Shipment s WHERE 'kiwi' MEMBER OF s.items.sku").list();     // [S-2]
db.insert(new Document("scan", text, Files.readAllBytes(Path.of("scan.png"))));        // MiB-sized values
```

**In Studio**: backlinks of the selected object, constraints of every field, nested values as a tree.

![Backlinks in Studio](https://paganini2008.github.io/kookaburra/assets/studio-backlinks.png)

![Constraints in Studio](https://paganini2008.github.io/kookaburra/assets/studio-constraints.png)

## 7. Configuration

One file: `kookaburra.properties`.

| Property | Default | Description |
|---|---|---|
| `kookaburra.durability` | `SYNC` | `SYNC`: a write returns once it is on disk; `ASYNC`: faster, the last moments may be lost on a power failure |
| `kookaburra.transactions` | `false` | enables transactions; off = every write commits directly |
| `kookaburra.tx-isolation` | `SNAPSHOT` | default level: `READ_COMMITTED`, `SNAPSHOT`, `SERIALIZABLE` |
| `kookaburra.tx-timeout` / `tx-retries` | `60s` / `3` | transaction timeout; `inTransaction` retries |
| `kookaburra.data` | `data/` next to the jar | Studio default database |

Tuning keys (memory, file sizes, background work) are commented in the file; the defaults suit most applications.

## 8. Performance / Comparison

**Environment**: Apple M2 Max (12 cores), 32 GB, internal SSD, JDK 17, `-Xmx8g`; **10 M objects**; harness `kookaburra-bench` (warm-up, p50 / p99, consistency checks after every phase).

| Operation (10 M objects) | p50 |
|---|---|
| bulk insert, batches of 100 (`ASYNC`) | 696 k objects/s |
| durable single inserts (`SYNC`), 16 / 128 threads | 3.2 k/s / 21.2 k/s |
| `get(oid)` | 0.6 to 1.1 µs |
| count with an indexed filter | 0.24 ms |
| equality + range + limit 100 | 0.21 ms |
| top-10 by an indexed column of 2.3 M matches | 0.39 ms |
| `GROUP BY city COUNT(*)` | 4.0 ms |
| unique-key lookup (OQL) | 0.12 ms |
| full scan with an unindexed predicate | 0.81 s |
| transfers, 16 threads: SNAPSHOT / READ COMMITTED / SERIALIZABLE | 136 / 142 / 140 k/s |
| atomic `increment` of one hot counter, 16 threads | 265 k/s, no lost updates |
| insert with `@Unique` / without (in memory) | 235 / 258 k/s |
| online backup of 10.5 M objects | 25.4 s, writers not blocked |
| OQL `DELETE` of all objects, disk space afterwards | 1.32 M objects/s, 0 B left |
| reopen / heap after reopen | 2.0 ms / 6.5 MiB |

## 9. Limitations & Trade-offs

- Each durable single write waits for the disk; batch or use `ASYNC` for bulk loads.
- Single-collection queries, no joins (references with `fetch`, or the semi-join `in(...)`).
- Optimistic transactions: conflicts surface at commit and are retried, never blocked.
- Not a fit for: several processes writing one directory, the SQL / JDBC / BI ecosystem, terabyte-scale data.

## 10. Summary

1. Stream-style code, database-style speed from indexes.
2. Method references or a generated metamodel for type-safe fields.
3. OQL covers the single-table SQL-92 core.
4. Three isolation levels; SERIALIZABLE closes write skew without locks.
5. `@Unique` and `@ForeignKey` with ON DELETE actions, checked atomically at commit.
6. Exact decimals, nested objects, large values.
7. Lifecycle callbacks and listeners, JPA style.
8. Online backup of a consistent snapshot; restore = open.
9. Sub-millisecond to a few milliseconds on 10 M objects; instant reopen, tiny heap.
10. Studio from one jar; every feature tested in memory and on disk, coverage ≥ 90 %.
