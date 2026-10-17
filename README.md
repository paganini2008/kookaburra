<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/logo_256.png" width="160" alt="Kookaburra logo">
</p>

<h1 align="center">Kookaburra</h1>

<p align="center"><b>Query Java objects like a database, right inside your application.</b></p>

<p align="center">
  <a href="https://openjdk.org/projects/jdk/17/"><img src="https://img.shields.io/badge/Java-17%2B-orange?logo=openjdk" alt="Java 17+"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <a href="https://central.sonatype.com/namespace/com.github.paganini2008"><img src="https://img.shields.io/badge/Maven-com.github.paganini2008-informational" alt="Maven"></a>
  <img src="https://img.shields.io/badge/coverage-%E2%89%A5%2090%25-brightgreen" alt="Coverage ≥ 90%">
  <img src="https://img.shields.io/badge/embedded-no%20server-8A2BE2" alt="Embedded, no server">
  <a href="https://paganini2008.github.io/kookaburra/"><img src="https://img.shields.io/badge/docs-GitHub%20Pages-00E5FF" alt="Docs"></a>
</p>

Kookaburra is an **embedded object database for Java 17+**. You store plain annotated objects. Then you query them with a type-safe, chained API (`query(Book.class).where(Book::getYear).ge(1960).list()`) or with **SQL-style OQL**. Indexed filters, sorts and aggregates answer in milliseconds on millions of objects. Data is crash-safe on disk, with optional **transactions** (READ COMMITTED, SNAPSHOT, SERIALIZABLE), **unique and foreign-key constraints** and online backup. **Kookaburra Studio** is a desktop admin tool for browsing, editing and querying any database.

<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/studio-data.png" width="860" alt="Kookaburra Studio">
</p>

<p align="center">
  <a href="https://paganini2008.github.io/kookaburra/#tutorial"><img src="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial-poster.jpg" width="560" alt="Watch the 3-minute tutorial"></a><br>
  🎬 <a href="https://paganini2008.github.io/kookaburra/#tutorial"><b>Watch the tutorial</b></a> (2:57, English subtitles) · <a href="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4">MP4</a>
</p>

---

## Features

### Query

| | Feature | What you get |
|---|---|---|
| 🧭 | **Type-safe chained queries** from method references or a generated metamodel | Refactoring-safe filters, no SQL mapping layer |
| ⚡ | **Indexes** on the fields you filter, sort and group by | Filter, count, top-N and group 10 M objects in milliseconds |
| 🔤 | **OQL**: `SELECT / UPDATE / DELETE` with SQL-92 core | Text queries for tools, configs and ad-hoc analysis |
| 🧩 | **Dynamic API** on a self-describing schema | Open any database without its classes |

### Data model

| | Feature | What you get |
|---|---|---|
| 🧬 | **Inheritance and nested objects**: embedded values at any depth, lists and maps of them | Rich domain models without flattening |
| 💰 | **Exact decimals** (`BigDecimal`), compared by value | Money without rounding errors |
| 📦 | **Large values**: long texts and binaries of tens of MiB | Documents and pictures next to their metadata |
| 🔄 | **Schema evolution** by layouts | Add or remove fields freely |

### Integrity and transactions

| | Feature | What you get |
|---|---|---|
| 🔑 | **`@Unique`** on a field or a combination of fields | No duplicate e-mails, SKUs, or codes per tenant |
| 🔗 | **`@ForeignKey`** with RESTRICT, CASCADE or SET_NULL, plus backlinks | No dangling references |
| 🔁 | **Transactions**: READ COMMITTED, SNAPSHOT, SERIALIZABLE, retries | Multi-object invariants without locks |
| 🪝 | **Lifecycle callbacks** (`@PrePersist` …) and listeners | Audit fields and validation in one place |

### Storage and operations

| | Feature | What you get |
|---|---|---|
| 🛡 | **Crash-safe and consistent**: committed data survives crashes, readers see consistent snapshots | Safe local persistence, no server |
| 🗜 | **Compact**: about 150 B per object on disk, deleted space reclaimed | Small heap, opens in milliseconds |
| 💾 | **Online backup** of a consistent snapshot | Back up while writing; restore = open |
| 📈 | **Statistics** with `db.stats()` | Feed your own monitoring (Micrometer, JMX, logs) |
| 🖥 | **Kookaburra Studio** desktop tool | Browse, edit, query and back up visually |

### Supported data types

| Java type | Stored as | Index | Notes |
|---|---|:-:|---|
| `boolean`, `Boolean` | BOOL | ✅ | |
| `byte`, `short`, `char`, `int` and boxed | INT32 | ✅ | |
| `long`, `Long` | INT64 | ✅ | |
| `float`, `double` and boxed | FLOAT64 | ✅ | `Double.compare` order, `NaN` greatest |
| `BigDecimal` | DECIMAL | ✅ | exact, numeric equality (`1.50` = `1.5`, read back as `1.5`) |
| `String` | STRING | ✅ | prefix queries (`startsWith`, `LIKE 'x%'`) use the index |
| `enum` | ENUM | ✅ | ordered by declaration; constants may be appended |
| `LocalDate` / `LocalDateTime` / `Instant` | DATE / DATETIME / INSTANT | ✅ | UTC |
| `Ref<T>` | REF | ✅ | reference by OID; `@ForeignKey` for integrity |
| `List<S>` / `Set<S>` of the scalars above | MULTI | ✅ | `contains`, `MEMBER OF` |
| `@Embeddable` class | EMBEDDED | ✅ per path | dotted paths at any depth (`address.geo.lat`) |
| `List` / `Set` of `@Embeddable` | EMBEDDED_LIST | scan | "any element" paths (`items.sku`) |
| `Map<K, V>` (scalar keys, scalar or `@Embeddable` values) | MAP | | |
| `byte[]` | BYTES | | images, files; Base64 in JSON / CSV exports |
| any other `Serializable` | OBJECT | | Java serialization |

## How It Works

<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/how-it-fits.png" width="860" alt="Kookaburra is a library inside your application; data lives in a directory that Studio and backups use">
</p>

| You | Kookaburra |
|---|---|
| annotate plain classes with `@Entity`, `@Index`, `@Unique`, `@ForeignKey` | stores them in a data directory (or in memory), no schema scripts, no server |
| query with method references, OQL or by class name | answers from indexes; `explain()` shows which index answers a condition |
| write single objects, batches or transactions | commits atomically and durably; constraints are checked at commit |
| open the directory in Studio, or call `db.backup(dir)` | browse and edit visually; consistent copies while the application runs |

Design notes for contributors: [docs/design.md](docs/design.md).

## Requirements

| Item | Version |
|---|---|
| Java | **17+** (JDK for building; JRE for running) |
| Runtime dependencies | RoaringBitmap 1.6.22, ANTLR 4.13.2 runtime, SLF4J API 2.0 (bring any backend) |
| Build | Maven 3.8+ |
| Studio | Java 17+ runtime; Windows x64, Linux x64 (GTK 3) or macOS Apple Silicon; JavaFX is bundled |

## Quick Start

**1. Add the dependency** (and the optional annotation processor for the `Book_` metamodel):

```xml
<dependency>
    <groupId>com.github.paganini2008</groupId>
    <artifactId>kookaburra-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```
```xml
<!-- maven-compiler-plugin → configuration -->
<annotationProcessorPaths>
    <path>
        <groupId>com.github.paganini2008</groupId>
        <artifactId>kookaburra-processor</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </path>
</annotationProcessorPaths>
```

**2. Declare an entity:**

```java
@Entity
public class Book {
    @Id long oid;
    @Version long version;
    @Index String title;
    @Index String author;
    @Index Genre genre;
    @Index(IndexKind.ORDINAL) int year;
    double price;
    @Index List<String> tags;
    // constructors, getters …
}
```

**3. Store and query:**

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

**4. Look at it in Studio:**

```bash
mvn install -DskipTests                                  # library
mvn -f kookaburra-face/pom.xml -P dist package -DskipTests  # → bin/kookaburra-face.jar
java -jar bin/kookaburra-face.jar --data=data            # or double-click the jar
```

<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/studio-splash.png" width="300" alt="Studio splash">
</p>

| Studio feature | |
|---|---|
| Several databases side by side; transactions per database: **Begin** (pick READ COMMITTED, SNAPSHOT or SERIALIZABLE), Commit, Rollback, or `BEGIN ISOLATION LEVEL …; COMMIT` scripts in the console | <img src="https://paganini2008.github.io/kookaburra/assets/studio-transaction.png" width="420" alt="Studio transaction"> |
| Nested objects: lists show as `[ 2 elements ]`, maps as `{ 3 entries }`; the Key / Value / Type inspector expands every level | <img src="https://paganini2008.github.io/kookaburra/assets/studio-nested.png" width="420" alt="Studio nested inspector"> |
| **← referenced by**: the objects pointing at the selected one (backlinks); double-click opens one | <img src="https://paganini2008.github.io/kookaburra/assets/studio-backlinks.png" width="420" alt="Studio backlinks"> |
| Constraint column (`UNIQUE`, `FK → Customer · CASCADE`); violations open a "Constraint violated" dialog | <img src="https://paganini2008.github.io/kookaburra/assets/studio-constraints.png" width="420" alt="Studio constraints"> |
| Element editor for lists of embedded objects and maps (add, remove, reorder) | <img src="https://paganini2008.github.io/kookaburra/assets/studio-nested-editor.png" width="420" alt="Studio element editor"> |
| Binary fields with image preview, long texts, exact decimals; **Flush** and **Backup** in the toolbar | <img src="https://paganini2008.github.io/kookaburra/assets/studio-binary.png" width="420" alt="Studio binary field"> |

The demo database (`--demo`) contains all of these: customers with a unique e-mail, a two-level address and a map of contacts; products with a unique SKU, a decimal price and a PNG photo; purchases with order lines and foreign keys (deleting a customer deletes their purchases, a purchased product cannot be deleted).

## Examples

All outputs below are from a real run on six books (Dune, Neuromancer, Foundation, The Hobbit, Hyperion, The Big Sleep); the Quick Start, transaction, constraint, isolation, backup and nested-object snippets are executed by `ReadmeExamplesTest`, in memory and on disk.

### Generated metamodel

| Input | Code | Output |
|---|---|---|
| books tagged `classic` under 10.00 | `db.query(Book.class).filter(Book_.tags.contains("classic")).filter(Book_.price.lt(10.0)).count()` | `2` |

### Group by and aggregates

```java
db.query(Book.class).groupBy(where(Book::getGenre))
  .agg(Q.count().as("books"), Q.avg(where(Book::getPrice)).as("avgPrice"))
  .sortedBy(Q.count().as("books").desc())
  .toList();
```
```text
SCIFI   books=4 avg=11.36
FANTASY books=1 avg=10.00
CRIME   books=1 avg=7.40
```

### OQL

```java
db.oql("SELECT b.author, b.year FROM Book b WHERE b.year < :y ORDER BY b.year")
  .param("y", 1950).rows();
db.oql("UPDATE Book b SET b.price = b.price * 0.9 WHERE b.genre = 'CRIME'").execute();
```
```text
J.R.R. Tolkien 1937
Raymond Chandler 1939
updated=1
```

### Update with optimistic locking

```java
Book dune = db.query(Book.class).where(Book::getTitle).eq("Dune").first().orElseThrow();
dune.setPrice(11.99);
db.update(dune);            // OptimisticLockException if someone updated it first
```

### Transactions (opt-in: `kookaburra.transactions=true`)

```java
try (Transaction tx = db.begin(Isolation.SNAPSHOT)) {
    Account a = tx.get(Account.class, from);
    tx.update(a.balance(a.getBalance() - 30));
    tx.increment(Account.class, to, "balance", 30);                    // atomic, never conflicts
    tx.query(Account.class).where(Account::getOwner).eq("ann").list();  // sees its own writes
    tx.commit();                                                        // ConflictException if `a` changed meanwhile
}                                                                       // not committed: rolled back
db.inTransaction(tx -> tx.increment(Account.class, to, "balance", 5));  // retried on conflicts
```

| Level | Reads | Prevents |
|---|---|---|
| `READ_COMMITTED` | latest commit, per statement | dirty reads, lost updates |
| `SNAPSHOT` (default) | one snapshot from `begin()` | plus non-repeatable reads and phantoms |
| `SERIALIZABLE` | snapshot, validated at commit | plus write skew |

<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/isolation.png" width="760" alt="Isolation levels">
</p>

### SERIALIZABLE: no write skew

Two doctors are on call; each checks that the other one still is, then goes off call. Under SNAPSHOT both commit and nobody is on call. Under SERIALIZABLE the second commit fails and `inTransaction` retries it:

```java
db.inTransaction(TxOptions.defaults().isolation(Isolation.SERIALIZABLE), tx -> {
    long onCall = tx.query(Doctor.class).where(Doctor::isOnCall).eq(true).count();
    if (onCall > 1) {
        tx.update(tx.get(Doctor.class, me).offCall());
    }
});
```
```text
second concurrent commit: ConflictException "serialization failure: a concurrent commit changed the result of a query …"
on call afterwards: 1
```

Design: [docs/transaction-design.md](docs/transaction-design.md).

### Constraints, decimals and callbacks

```java
@Entity
public class Order {
    @Id long oid;
    @Unique String number;                                            // no two orders share a number
    @ForeignKey(onDelete = OnDelete.CASCADE) Ref<Customer> customer;  // deleted with the customer
    @Index BigDecimal total;                                          // exact money, numeric order
    Instant created;

    @PrePersist void stamp() { created = Instant.now(); }            // lifecycle callback
}
```

| Input | Code | Output |
|---|---|---|
| a second order `A-1` | `db.insert(new Order("A-1", ann, "5"))` | `ConstraintViolationException: duplicate value 'A-1' for unique Order.number` |
| totals 19.90 and 10.15 | `db.query(Order.class).sum(where(Order::getTotal))` | `30.05` (exact) |
| `19.90` stored | `db.query(Order.class).where(Order::getTotal).eq(new BigDecimal("19.9")).count()` | `1` |
| Ann's backlinks | `db.referrers(Order.class, ann)` | `[A-1, A-2]` |
| delete Ann | `db.delete(Customer.class, ann)` | her orders are deleted in the same commit |

<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/foreign-keys.png" width="760" alt="Unique and foreign-key constraints">
</p>

Combinations go on the class: `@Unique(fields = {"tenant", "code"})` (repeatable, optional `name`; a null in any field is not compared). Constraints are checked when the write commits, against the latest data and the batch's own writes. `RESTRICT` (default) refuses the delete, `SET_NULL` clears the reference. Listeners: `db.addListener(Order.class, listener)`.

### Online backup

```java
BackupInfo info = db.backup(Path.of("backups/2026-10-17"));   // writers keep going
try (ObjectDB copy = ObjectDB.open(info.path())) { … }        // restore = open the directory
```
```text
BackupInfo[path=/…/backups/2026-10-17, …, objects=2, …]
```

<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/backup-overview.png" width="760" alt="Online backup">
</p>

### Statistics

```java
DatabaseStats s = db.stats();          // cheap: poll it and export to your monitoring
s.objects();                           // live objects
s.writes().batches();                  // commits since open
s.transactions().total().conflicts();  // failed commits that a retry may fix
s.constraints().uniqueViolations();
```

Per collection: objects and disk size. Counters since open: writes, transactions per isolation level, constraint violations, background work and backups. Studio shows the same numbers on its **Stats** tab.

### Nested objects

```java
@Entity class Shipment { @Id long oid; String code; Route route; List<Item> items; Map<String, Place> stops; }
@Embeddable class Route { Place from; Place to; int legs; }
@Embeddable class Place { String city; String street; Geo geo; }
@Embeddable class Item  { String sku; int qty; Money price; }

db.oql("SELECT s.code FROM Shipment s WHERE s.route.to.city = 'Perth'").list();             // [S-1]
db.query(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("pear").list(); // S-1
db.oql("SELECT s.code FROM Shipment s WHERE 'kiwi' MEMBER OF s.items.sku").list();          // [S-2]
```

Paths through lists of embedded objects mean "any element" (like MongoDB dotted paths) and are evaluated by scanning.

### Large values (texts, images)

```java
@Entity class Document { @Id long oid; @Index String title; String body; byte[] image; Map<String, byte[]> thumbnails; }

db.insert(new Document("scan", text, Files.readAllBytes(Path.of("scan.png"))));   // MiB-sized values are fine
```

Tested with 2 M-character Unicode texts and 5–16 MiB binaries through transactions, restarts and backups. In JSON / CSV exports binaries are Base64 (as Jackson writes `byte[]`).

### Dynamic access (no entity class on the class path)

```java
DynamicCollection books = db.dynamic("Book");
books.from().where("author").startsWith("J").list().get(0).get("title");   // "The Hobbit"
```

### Explain

```java
db.query(Book.class).where(Book::getGenre).eq(Genre.SCIFI)
  .where(Book::getYear).between(1950, 1970).explain();
```
The plan lists every condition with the index that answers it, cheapest first. A condition marked `RESIDUAL` has no index and reads every object: add `@Index` to that field.

### Best practices

| Do | Why |
|---|---|
| `@Index` fields you filter, sort or group on; `ORDINAL` for ranges and sorts on many distinct values | filters on unindexed fields read every object |
| Write in `WriteBatch`es; use `Durability.ASYNC` for bulk loads | one disk flush per batch instead of per object |
| Use a `Snapshot` for several reads that must agree | they all see the same moment |
| Use transactions only where several objects must change together | autocommit writes skip the conflict bookkeeping |
| Pick SERIALIZABLE when a decision depends on what other rows say (rotas, quotas) | it closes write skew; retries come from `inTransaction` |
| Declare `@Unique` / `@ForeignKey` instead of checking in code | the check and the write are one atomic step |
| Back up with `db.backup(dir)`, not by copying files | a copy of a live directory may be inconsistent |
| Prefer `increment` for counters | it never conflicts, even inside transactions |
| Keep one `ObjectDB` per directory per process | it is thread-safe and holds the directory lock |

## Configuration

All settings live in one file, [`kookaburra.properties`](kookaburra.properties): bundled defaults → file → `-Dkookaburra.*` → Studio CLI. In code, use `Config.load()` / `Config.defaults().withX(…)`.

| Property | Default | Description |
|---|---|---|
| `kookaburra.durability` | `SYNC` | `SYNC`: a write returns once it is on disk; `ASYNC`: faster, the last moments may be lost on a power failure |
| `kookaburra.transactions` | `false` | enables `begin()` / `inTransaction()`; off = every write commits directly |
| `kookaburra.tx-isolation` | `SNAPSHOT` | isolation of `begin()` / `inTransaction`: `READ_COMMITTED`, `SNAPSHOT`, `SERIALIZABLE` |
| `kookaburra.tx-timeout` / `tx-retries` | `60s` / `3` | transaction timeout; `inTransaction` retries after a conflict |
| `kookaburra.data` | `<jar dir>/data` | Studio: default database directory |
| `kookaburra.log-dir` / `kookaburra.log-level` | `<jar dir>/logs` / `INFO` | Studio: log files |
| `kookaburra.theme` / `page-size` / `console-page-size` | `NEON` / `50` / `100` | Studio UI |

Memory, file size and background-work tuning keys are listed with comments in [`kookaburra.properties`](kookaburra.properties); the defaults suit most applications.

## Performance

**Environment**: Apple M2 Max (12 cores), 32 GB, internal SSD, macOS, JDK 17.0.12, `-Xmx8g`. **Data**: 10,000,000 objects with indexed `name` (unique), `city` (50 values), `age` (ORDINAL) and `tags`, plus plain fields. Harness: `kookaburra-bench` (warm-up, p50 / p99, consistency checks). Full report: [docs/benchmark-10m.md](docs/benchmark-10m.md).

| Operation (10 M objects) | p50 |
|---|---|
| bulk insert, batches of 100 (`ASYNC`) | **696 k objects/s** |
| durable single inserts (`SYNC`), 16 / 128 threads | 3.2 k/s / **21.2 k/s** |
| `get(oid)` | **0.6 to 1.1 µs** |
| `count()` where `city = ?` (indexed) | **0.24 ms** |
| `city = ? AND age BETWEEN 30 AND 40 LIMIT 100` | **0.21 ms** |
| top-10 by `age DESC` over 2.3 M matches | **0.39 ms** |
| `GROUP BY city COUNT(*)` | **4.0 ms** |
| unique-key lookup via OQL (`name = ?`) | **0.12 ms** |
| OQL `GROUP BY city, COUNT, AVG(age) WHERE age > 40` | 244 ms |
| full scan with an unindexed predicate | 0.81 s |
| transfers (read 2, update 2), 16 threads: SNAPSHOT / READ COMMITTED / SERIALIZABLE | **136 / 142 / 140 k/s**, 0.023 retries / commit |
| atomic `increment` of one hot counter, 16 threads | **265 k/s**, no lost updates |
| insert with `@Unique` / without, batches of 100 (in memory) | **235 / 258 k/s** |
| insert with a checked `@ForeignKey` / delete with ON DELETE CASCADE | 225 k/s / 181 k/s |
| online backup of 10.5 M objects (1.4 GB) | **25.4 s**, writers not blocked |
| OQL `DELETE` of all 10.5 M objects / disk space afterwards | 1.32 M objects/s / **0 B** (fully reclaimed) |
| reopen / heap after reopen | 2.0 ms / 6.5 MiB |
| disk footprint | 148 B / object |

| Trade-off | Consequence |
|---|---|
| Each durable single write waits for the disk | many concurrent writers share a disk flush; batch or use `ASYNC` for bulk loads |
| Indexes cost space | about 4 B per object for an index on a field with many distinct values |
| Single-collection queries | no joins across collections (references + `fetch` / semi-join `in(...)` instead) |
| Optimistic transactions | conflicts surface at commit and are retried, never blocked |

## Documentation

| Guide | Link |
|---|---|
| Video tutorial | [Watch on the website](https://paganini2008.github.io/kookaburra/#tutorial) (2:57) · regenerate with `mvn -f kookaburra-face/pom.xml test -P tutorial` (needs ffmpeg) |
| Getting Started | [Quick Start](#quick-start) |
| Design notes (for contributors) | [docs/design.md](docs/design.md) |
| Transactions | [docs/transaction-design.md](docs/transaction-design.md): three isolation levels with OCC, decisions T1 to T17 |
| Storage design | [docs/storage-design.md](docs/storage-design.md): files, write path, compaction, recovery, backup |
| Developer guide | [docs/developer-guide.md](docs/developer-guide.md): build, test, conventions, docs tooling |
| Examples | [Examples](#examples) · tests under `kookaburra-core/src/test` |
| Configuration | [Configuration](#configuration) · [`kookaburra.properties`](kookaburra.properties) |
| Benchmarks | [docs/benchmark-10m.md](docs/benchmark-10m.md) |
| Website | <https://paganini2008.github.io/kookaburra/> |

**FAQ**

| Question | Answer |
|---|---|
| Must entities implement `Serializable`? | No. Only field values of otherwise unsupported types fall back to Java serialization |
| Can I query without the entity classes? | Yes: `db.dynamic("Book")`, OQL, or Studio |
| Is there a server? | No, it is embedded: one process opens a directory |
| Which time zone? | UTC everywhere |
| How do I back up and restore? | `db.backup(dir)` while running; open the directory to restore |
| Why is `1.50` read back as `1.5`? | decimals are stored by value, so `1.50` and `1.5` are equal everywhere; format with `setScale(2)` |

## Contributing & License

Issues and pull requests are welcome at <https://github.com/paganini2008/kookaburra>. Please run `mvn verify` before submitting. The build enforces **≥ 90 % line and instruction coverage** (JaCoCo) in every module, and the differential tests compare every query path against `java.util.stream`.

| Module | Build |
|---|---|
| `kookaburra-core`, `kookaburra-processor` | `mvn verify` |
| Studio | `mvn -f kookaburra-face/pom.xml verify` · `-P dist package` |
| Benchmark | `mvn -f kookaburra-bench/pom.xml package` · `java -jar kookaburra-bench/target/kookaburra-bench.jar --n=10000000` |

Licensed under the [Apache License 2.0](LICENSE). © 2026 Fred Feng ([paganini20080@gmail.com](mailto:paganini20080@gmail.com)).
