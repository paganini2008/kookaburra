<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/logo_256.png" width="160" alt="Kookaburra logo">
</p>

<h1 align="center">Kookaburra</h1>

<p align="center"><b>Query Java objects like a database, right inside your application.</b></p>

<p align="center">
  <a href="https://openjdk.org/projects/jdk/17/"><img src="https://img.shields.io/badge/Java-17%2B-orange?logo=openjdk" alt="Java 17+"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <a href="https://central.sonatype.com/namespace/com.github.paganini2008"><img src="https://img.shields.io/badge/Maven-com.github.paganini2008-informational" alt="Maven"></a>
  <img src="https://img.shields.io/badge/embedded-no%20server-8A2BE2" alt="Embedded, no server">
  <a href="https://paganini2008.github.io/kookaburra/"><img src="https://img.shields.io/badge/docs-GitHub%20Pages-00E5FF" alt="Docs"></a>
</p>

**Kookaburra** is an **embedded object database for Java 17+**: one library in your process, plain annotated classes in.

- **Query**: a type-safe chained API (`query(Book.class).where(Book::getYear).ge(1960).list()`) or **SQL-style OQL**; indexed filters, sorts and aggregates in milliseconds on millions of objects.
- **Search**: vector search (HNSW) and full-text search (BM25), together or fused.
- **Safety**: crash-safe files, **transactions** (READ COMMITTED, SNAPSHOT, SERIALIZABLE), **unique and foreign-key constraints**, online backup.
- **Studio**: a desktop tool to browse, edit and query any database.

<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/studio-data.png" width="860" alt="Kookaburra Studio">
</p>

<p align="center">
  <a href="https://paganini2008.github.io/kookaburra/#tutorial"><img src="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial-poster.jpg" width="560" alt="Watch the 3-minute tutorial"></a><br>
  🎬 <a href="https://paganini2008.github.io/kookaburra/#tutorial"><b>Watch the tutorial</b></a> (2:58, English subtitles) · <a href="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4">MP4</a>
</p>

---

## Features

### Query

| | Feature | What you get |
|---|---|---|
| 🧭 | **Type-safe chained queries** from method references or a generated metamodel | Refactoring-safe filters, no SQL mapping layer |
| ⚡ | **Indexes** on the fields you filter, sort and group by | Filter, count, top-N and group 10 M objects in milliseconds |
| 🔤 | **OQL**: `SELECT / UPDATE / DELETE` with SQL-92 core | Text queries for tools, configs and ad-hoc analysis |
| 🔎 | **Full-text search**: words in any language, English word forms, BM25 relevance, hybrid with vectors | Search boxes and RAG retrieval without a search server |
| 🧭 | **Query plans**: `explainAnalyze()` and `EXPLAIN ANALYZE` with rows and time per step; a diagram in Studio | See which index answers each condition and where time goes |
| 🎯 | **Vector search**: `float[]` embeddings, HNSW index, cosine / L2 / inner product, text queries through Ollama | Semantic search and RAG next to your filters, in about a millisecond |
| 🧩 | **Dynamic API** on a self-describing schema | Open any database without its classes |

### Data model

| | Feature | What you get |
|---|---|---|
| 🧬 | **Inheritance and nested objects**: embedded values at any depth, lists and maps of them | Rich domain models without flattening |
| 💰 | **Exact decimals** (`BigDecimal`), compared by value | Money without rounding errors |
| 📦 | **Large values**: long texts and binaries of tens of MiB | Documents and pictures next to their metadata |
| 🔄 | **Schema evolution**: add, remove or widen fields; rename fields and classes with `@Renamed`; `@Comment`; export the schema as Java | Change your classes without migrating data |

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
| `float[]` (`@Vector(dim = …)`) | VECTOR | nearest | float32 embeddings; dimension checked on write; searched with `nearest(...)` |
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
| Studio | Installers with their own Java runtime for Windows x64, macOS (Apple silicon, Intel) and Linux x64 (GTK 3); or the jar on any Java 17+ |

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

**4. Look at it in Studio.** Download the latest version (no Java needed for the installers):

| Windows 10 / 11 | macOS | Linux | Any OS with Java 17+ |
|---|---|---|---|
| [Installer (.msi)](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-windows-x64.msi) · [zip](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-windows-x64.zip) | [Apple silicon (.dmg)](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-macos-arm64.dmg) · [Intel (.dmg)](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-macos-x64.dmg) | [.deb](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-linux-x64.deb) · [.tar.gz](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-linux-x64.tar.gz) | [kookaburra-studio.jar](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio.jar) |

- Open any database directory with **Open database…** or `--data=PATH`.
- The installed app keeps settings, the default database and logs in `~/Kookaburra`.
- From source: `mvn -f kookaburra-face/pom.xml -P dist package -DskipTests` builds `bin/kookaburra-face.jar`.

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
| **Stats dashboard**: objects, storage, commits per second, open transactions, conflicts; live write and transaction rates; objects and storage per collection | <img src="https://paganini2008.github.io/kookaburra/assets/studio-stats.png" width="420" alt="Studio stats dashboard"> |
| OQL console: **Explain** / **Analyze** draw the query plan (index or scan per condition, rows and time per step, slowest step marked); **Format** tidies the statement | <img src="https://paganini2008.github.io/kookaburra/assets/studio-plan.png" width="420" alt="Studio query plan"> |
| Vectors and text: right-click **Find similar**, **From text…** fills a vector, `MATCH` / `SCORE` on full-text fields | |
| Menu bar (File, Edit, Query, Transaction, View, Help); **Export as Java**; field comments as column hints; About with a feature map | <img src="https://paganini2008.github.io/kookaburra/assets/studio-about.png" width="420" alt="Studio feature map"> |
| Binary fields with image preview, long texts, exact decimals; **Flush** and **Backup** in the toolbar | <img src="https://paganini2008.github.io/kookaburra/assets/studio-binary.png" width="420" alt="Studio binary field"> |

The demo database (`--demo`) has all of these:

| Class | Shows |
|---|---|
| Customer | unique e-mail, two-level address, map of contacts |
| Product | unique SKU, decimal price, PNG photo, full-text description, 4-dim embedding |
| Purchase | order lines; foreign keys: deleting a customer deletes their purchases, a purchased product cannot be deleted |

## Examples

- Outputs below are from a real run on six books (Dune, Neuromancer, Foundation, The Hobbit, Hyperion, The Big Sleep).
- The Quick Start, transaction, constraint, isolation, backup and nested-object snippets run in `ReadmeExamplesTest`, in memory and on disk.

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

| Rule | Behaviour |
|---|---|
| `@Unique(fields = {"tenant", "code"})` on the class | combination unique (repeatable, optional `name`; a null in any field is not compared) |
| When checked | at commit, against the latest data and the batch's own writes |
| `@ForeignKey(onDelete = …)` | `RESTRICT` (default) refuses the delete, `CASCADE` deletes, `SET_NULL` clears the reference |
| Listeners | `db.addListener(Order.class, listener)` |

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

### Vector search

```java
@Entity
public class Doc {
    @Id long oid;
    @Index String lang;
    String title;
    @Vector(dim = 384) float[] embedding;            // cosine by default; distance = L2 / INNER_PRODUCT
}

List<Doc> top = db.query(Doc.class)
        .where(Doc::getLang).eq("en")                // filters run first, on the indexes
        .nearest(Doc::getEmbedding, queryVector, 10) // the 10 closest
        .list();

List<Doc> byText = db.query(Doc.class)
        .nearest(Doc::getEmbedding, "how do I bake bread at home", 10)   // embedded by Ollama
        .list();
```
```sql
SELECT d.title, cosine_distance(d.embedding, embed('how do I bake bread at home')) AS dist
FROM Doc d WHERE d.lang = 'en' ORDER BY dist LIMIT 10
```

| Capability | API | OQL |
|---|---|---|
| Nearest objects (HNSW index on every `@Vector(dim)`) | `nearest(field, vector, k)` | `ORDER BY cosine_distance(d.v, :q) LIMIT k` |
| Search by text (Ollama, `kookaburra.embedder-model`) | `nearest(field, "text", k)` | `embed('text')` |
| Wider search / exact answer | `.numCandidates(n)` / `.exact()` | |
| Fewer near-duplicates (MMR) | `.diverse()` | |
| A quarter of the memory for the index | `@Vector(dim = 384, quantization = INT8)` | |
| Re-order by a local model (`kookaburra.reranker-model`) | `.rerank(Doc::getText)` | |
| Vectors + words (reciprocal rank fusion) | `.hybrid(Doc::getBody)` | |
| Distances (pgvector names) | `VectorDistance.COSINE / L2 / INNER_PRODUCT` | `cosine_distance`, `l2_distance`, `inner_product`, `vector_dims` |

| Measured | Result |
|---|---|
| top 10 of 100,000 × 384 dims | 1.0 ms, 99.6 % same as the exact answer |
| 10,000 real text embeddings (Ollama, 2560 dims) | 2.3 ms, 100 % same as exact |
| filter leaving a few objects | compared directly, never misses a match |

Studio: the demo products have 4-dim embeddings `[reading, tech, home, outdoor]`; right-click a product for **Find similar**, **From text…** in the editor fills a vector from a sentence.

### Full-text search

```java
@Entity
public class Article {
    @Id long oid;
    @FullText String body;                                   // english by default; @FullText(analyzer = "standard")
}

db.query(Article.class).where(Article::getBody).matches("bread oven").list();     // every word, stems match
db.query(Article.class).search(Article::getBody, "baking bread", 10).list();       // most relevant first (BM25)
db.query(Doc.class).nearest(Doc::getEmbedding, "baking bread", 10)
        .hybrid(Doc::getBody).list();                                               // vectors + words, fused
```
```sql
SELECT a.title, SCORE(a.body, 'baking bread') AS score FROM Article a
WHERE MATCH(a.body, 'bak*') ORDER BY SCORE(a.body, 'baking bread') DESC LIMIT 10
```

| Capability | API | OQL |
|---|---|---|
| Every word (stems match: `baking` finds `baked`) | `where(f).matches("bread oven")` | `MATCH(a.body, 'bread oven')` |
| Any word / word prefix | `where(f).matchesAny("…")` / `"bak*"` | `MATCH(a.body, 'bak*')` |
| Exact phrase | `matches("\"fresh bread\"")` | `MATCH(a.body, '"fresh bread"')` |
| Highlighted fragments (`<em>…</em>`) | `TextQuery.parse(q).highlight(text)` | `HIGHLIGHT(a.body, 'bread')` |
| Most relevant first (BM25) | `search(f, "baking bread", k)` | `ORDER BY SCORE(a.body, '…') DESC` |
| With vectors (RRF) | `nearest(v, "text", k).hybrid(f)` | |

| Analyzer | Words |
|---|---|
| `english` (default) | any language; English stop words dropped, words reduced to their stem (Porter) |
| `standard` | any language, as written (lower-cased) |
| your own | `Analyzers.register("name", text -> words)` |

Chinese, Japanese and Korean text is split into pairs of characters. Studio: demo products have descriptions, try `MATCH(p.description, 'kitchen')`.

### Query plans

```java
System.out.println(db.query(Person.class).where(Person::getCity).eq("Perth")
        .orderBy(Person::getAge).desc().limit(10).explainAnalyze());
```

| | API | OQL | Shows |
|---|---|---|---|
| Plan | `explain()` / `plan()` | `EXPLAIN SELECT …` | steps and the index each condition uses |
| Plan, run | `explainAnalyze()` / `analyze()` | `EXPLAIN ANALYZE SELECT …` | also rows in → out and time per step |
| Studio | **Explain** / **Analyze** | same | diagram, slowest step marked |

A condition marked `[scan: reads each row]` is the place for an `@Index`.

<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/studio-plan.png" width="760" alt="Query plan in Studio">
</p>

### Statistics

```java
DatabaseStats s = db.stats();          // cheap: poll it and export to your monitoring
s.objects();                           // live objects
s.writes().batches();                  // commits since open
s.transactions().total().conflicts();  // failed commits that a retry may fix
s.constraints().uniqueViolations();
```

Per collection: objects and disk size. Counters since open: writes, transactions per isolation level, constraint violations, background work and backups. Studio shows the same numbers on its **Stats** dashboard, with live rates.

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

| Condition on a list of embedded objects | API | OQL |
|---|---|---|
| any element has the value | `whereAny(Shipment::getItems, Item::getSku).contains("A")` | `'A' MEMBER OF s.items.sku` |
| one element meets every condition (`$elemMatch`) | `whereElement(Shipment::getItems, e -> e.where(Item::getSku).eq("A").where(Item::getQty).gt(5))` | `SELECT s FROM Shipment s JOIN s.items i WHERE i.sku = 'A' AND i.qty > 5` |

### Renaming fields and classes

```java
@Entity @Renamed("com.example.Client")          // the class was called Client
public class Customer {
    @Id long oid;
    @Renamed("fullName") String name;            // the field was called fullName
}
```

| Kept | |
|---|---|
| data | old rows read under the new names, nothing is rewritten |
| identity | same OIDs, references and collection; the old class name still finds the class (OQL, dynamic access) |
| indexes | used again under the new name after the next compaction |

### Comments and Java export

```java
@Entity @Comment("A shop customer")
public class Customer {
    @Id long oid;
    @Comment("Given and family name") String name;
}

SchemaExport java = db.exportSchema((String) null);              // the stored schema as Java source
db.exportSchema(Path.of("src/main/java"), "com.acme.model");     // write it; classes get @Renamed
```

| | |
|---|---|
| `@Comment` | on classes and fields; kept in the schema; Studio shows it on the Schema page and as column hints; changing it adds no schema version |
| `exportSchema` | entities, embeddables and enums with every annotation, comments, getters and setters; compiled, they open the same database unchanged |
| Studio | **Export as Java**: preview each class, edit, change the package, save |

<p align="center">
  <img src="https://paganini2008.github.io/kookaburra/assets/studio-java-export.png" width="760" alt="Export as Java in Studio">
</p>

### Large values (texts, images)

```java
@Entity class Document { @Id long oid; @Index String title; String body; byte[] image; Map<String, byte[]> thumbnails; }

db.insert(new Document("scan", text, Files.readAllBytes(Path.of("scan.png"))));   // MiB-sized values are fine
```

Tested with 2 M-character Unicode texts and 5 to 16 MiB binaries through transactions, restarts and backups. In JSON / CSV exports binaries are Base64 (as Jackson writes `byte[]`).

### Dynamic access (no entity class on the class path)

```java
DynamicCollection books = db.dynamic("Book");
books.from().where("author").startsWith("J").list().get(0).get("title");   // "The Hobbit"
```

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
| `kookaburra.durability` | `SYNC` | `SYNC`: a write returns once it is on disk; `ASYNC`: faster, the last moments may be lost on a power failure; per transaction `TxOptions.defaults().durability(ASYNC)`, per batch `db.batch().durability(ASYNC)` |
| `kookaburra.transactions` | `false` | enables `begin()` / `inTransaction()`; off = every write commits directly |
| `kookaburra.tx-isolation` | `SNAPSHOT` | isolation of `begin()` / `inTransaction`: `READ_COMMITTED`, `SNAPSHOT`, `SERIALIZABLE` |
| `kookaburra.tx-timeout` / `tx-retries` | `60s` / `3` | transaction timeout; `inTransaction` retries after a conflict |
| `kookaburra.embedder` / `embedder-model` / `embedder-url` | `ollama` / `nomic-embed-text` / `http://localhost:11434` | text to vector for `nearest(field, "text", k)`, `embed('…')` and Studio; `none` turns it off |
| `kookaburra.vector-ef-search` | `100` | how widely a vector search looks; larger = more accurate, a bit slower |
| `kookaburra.reranker` / `reranker-model` | `ollama` / `qwen3:4b` | model behind `.rerank(...)`; `none` turns it off |
| `kookaburra.data` | `<jar dir>/data` (installed app: `~/Kookaburra/data`) | Studio: default database directory |
| `kookaburra.log-dir` / `kookaburra.log-level` | `<jar dir>/logs` / `INFO` | Studio: log files |
| `kookaburra.theme` / `page-size` / `console-page-size` | `NEON` / `50` / `100` | Studio UI |

Memory, file size and background-work tuning keys are listed with comments in [`kookaburra.properties`](kookaburra.properties); the defaults suit most applications.

## Performance

| Setting | Value |
|---|---|
| Machine | Apple M2 Max (12 cores), 32 GB, internal SSD, macOS, JDK 17.0.12, `-Xmx8g` |
| Data | 10,000,000 objects: indexed `name` (unique), `city` (50 values), `age` (ORDINAL), `tags`, plain fields |
| Vectors | separate 100,000 × 384 dims (50 clusters) |
| Harness | `kookaburra-bench`: warm-up, p50 / p99, consistency checks; [full report](docs/benchmark-10m.md) |

| Operation (10 M objects) | p50 |
|---|---|
| bulk insert, batches of 100 (`ASYNC`) | **576 k objects/s** |
| durable single inserts (`SYNC`), 16 / 128 threads | 3.3 k/s / **24.4 k/s** |
| `get(oid)` | **0.7 µs** |
| `count()` where `city = ?` (indexed) | **0.23 ms** |
| `city = ? AND age BETWEEN 30 AND 40 LIMIT 100` | **0.19 ms** |
| top-10 by `age DESC` over 2.3 M matches | **0.39 ms** |
| `GROUP BY city COUNT(*)` | **3.9 ms** |
| unique-key lookup via OQL (`name = ?`) | **0.11 ms** |
| OQL `GROUP BY city, COUNT, AVG(age) WHERE age > 40` | 249 ms |
| full scan with an unindexed predicate | 0.78 s |
| transfers (read 2, update 2), 16 threads: SNAPSHOT / READ COMMITTED / SERIALIZABLE | **136 / 139 / 136 k/s**, 0.023 retries / commit |
| atomic `increment` of one hot counter, 16 threads | **262 k/s**, no lost updates |
| insert with `@Unique` / without, batches of 100 (in memory) | **251 / 318 k/s** |
| insert with a checked `@ForeignKey` / delete with ON DELETE CASCADE | 266 k/s / 152 k/s |
| online backup of 10.5 M objects (1.4 GB) | **25.7 s**, writers not blocked |
| OQL `DELETE` of all 10.5 M objects / disk space afterwards | 1.31 M objects/s / **0 B** (fully reclaimed) |
| reopen / heap after reopen | 2.1 ms / 7.1 MiB |
| disk footprint | 148 B / object |
| vector search, top 10 of 100,000 × 384 dims: HNSW / exact | **1.0 ms** (99.6 % same as exact) / 21.5 ms |
| vector search with a filter keeping 2 % / 50 % | **1.8 ms** / 2.7 ms |

| Trade-off | Consequence |
|---|---|
| Each durable single write waits for the disk | many concurrent writers share a disk flush; batch or use `ASYNC` for bulk loads |
| Indexes cost space | about 4 B per object for an index on a field with many distinct values |
| Single-collection queries | no joins across collections (references + `fetch` / semi-join `in(...)` instead) |
| Optimistic transactions | conflicts surface at commit and are retried, never blocked |

## Documentation

| Guide | Link |
|---|---|
| Video tutorial | [Watch on the website](https://paganini2008.github.io/kookaburra/#tutorial) (2:58) · regenerate with `mvn -f kookaburra-face/pom.xml test -P tutorial` (needs ffmpeg) |
| Getting Started | [Quick Start](#quick-start) |
| Architecture | [docs/architecture.md](docs/architecture.md): the layers in one picture |
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

Issues and pull requests are welcome at <https://github.com/paganini2008/kookaburra>. Run `mvn verify` before submitting:

- every test suite runs in memory and on disk;
- differential tests compare every query path with `java.util.stream`;
- a coverage gate keeps new code tested.

| Module | Build |
|---|---|
| `kookaburra-core`, `kookaburra-processor` | `mvn verify` |
| Studio | `mvn -f kookaburra-face/pom.xml verify` · `-P dist package` |
| Benchmark | `mvn -f kookaburra-bench/pom.xml package` · `java -jar kookaburra-bench/target/kookaburra-bench.jar --n=10000000` |

Licensed under the [Apache License 2.0](LICENSE). © 2026 Fred Feng ([paganini20080@gmail.com](mailto:paganini20080@gmail.com)).
