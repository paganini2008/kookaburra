# Kookaburra: an Embedded Object Database for Java, with Type-Safe Queries

*An embedded object database for Java 17+: write queries like streams, and they execute like a database.*

![Kookaburra](https://paganini2008.github.io/kookaburra/assets/logo_256.png)

## 1. Overview

**Kookaburra**: *query Java objects like a database, right inside your application.*

An **embedded object database for Java 17+**: annotated classes in, answers from indexes in milliseconds.

- **Query**: type-safe chained API or SQL-style **OQL**
- **Search**: vectors (HNSW) and full text (BM25), with readable query plans
- **Data safety**: transactions at three isolation levels, unique and foreign-key constraints, exact decimals, online backup
- **Tool**: **Kookaburra Studio** for browsing, editing and querying

![Kookaburra Studio, the desktop admin tool](https://paganini2008.github.io/kookaburra/assets/studio-data.png)

**Watch the 2:58 video tutorial** (English subtitles):

[![Watch the tutorial](https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial-poster.jpg)](https://paganini2008.github.io/kookaburra/#tutorial)

[Watch on the project website](https://paganini2008.github.io/kookaburra/#tutorial) · [MP4](https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4)

## 2. What Problem Does It Solve

Keeping your own objects usually means an ORM plus a relational database; the light alternative, `stream().filter()`, scans everything on every query. Kookaburra sits in between:

- **No mapping layer**: annotated objects are stored directly
- **No full scans**: indexes answer in milliseconds on 10 million objects
- **No string field names**: `where(Book::getYear)` or a generated `Book_` metamodel
- **No duplicates or dangling references**: `@Unique` on a field or a combination of fields, `@ForeignKey` with RESTRICT, CASCADE or SET_NULL
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
- **Studio** downloads (latest, own Java runtime):
  - **Windows**: [.msi](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-windows-x64.msi)
  - **macOS Apple silicon / Intel**: [.dmg](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-macos-arm64.dmg) / [.dmg](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-macos-x64.dmg)
  - **Linux**: [.deb](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio-linux-x64.deb)
  - **Any system with Java 17+**: [.jar](https://github.com/paganini2008/kookaburra/releases/latest/download/kookaburra-studio.jar)
  - Any database directory opens with **Open database…**
- **Field types**: `boolean`, all integer types, `float` / `double`, `BigDecimal`, `String`, enums, `LocalDate` / `LocalDateTime` / `Instant`, `Ref<T>`, lists and sets of these, `@Embeddable` classes (and lists / maps of them), `Map`, `byte[]`, any `Serializable`

## 5. How It Works

![How Kookaburra fits into an application](https://paganini2008.github.io/kookaburra/assets/how-it-fits.png)

- **You annotate plain classes**; Kookaburra stores them in a data directory (or in memory). No server, no schema scripts.
- **You query** with method references or OQL; indexes answer, and `explain()` shows which one.
- **You write** objects, batches or transactions; commits are atomic and durable, constraints are checked at commit.
- **You open the directory in Studio**, or call `db.backup(dir)` for a consistent copy while running.

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

![Online backup](https://paganini2008.github.io/kookaburra/assets/backup-overview.png)

**Example 6: nested objects and large values**

```java
db.oql("SELECT s.code FROM Shipment s WHERE s.route.to.city = 'Perth'").list();        // [S-1]
db.oql("SELECT s.code FROM Shipment s WHERE 'kiwi' MEMBER OF s.items.sku").list();     // [S-2]
db.oql("SELECT s.code FROM Shipment s JOIN s.items i WHERE i.sku = 'A' AND i.qty > 5").list();  // one element meets both
db.insert(new Document("scan", text, Files.readAllBytes(Path.of("scan.png"))));        // MiB-sized values
```

**Example 7: vector search (embeddings)**

```java
@Vector(dim = 384) float[] embedding;                         // in the entity; cosine by default

List<Doc> top = db.query(Doc.class).where(Doc::getLang).eq("en")
        .nearest(Doc::getEmbedding, queryVector, 10).list();  // filter first, then the 10 closest
List<Doc> byText = db.query(Doc.class)
        .nearest(Doc::getEmbedding, "how do I bake bread at home", 10).list();   // embedded by Ollama
```
```sql
SELECT d.title, cosine_distance(d.embedding, embed('bread at home')) AS dist FROM Doc d ORDER BY dist LIMIT 10
```

- **top 10 of 100,000 vectors (384 dims), HNSW index**: Time: about 1 ms; Same as the exact answer: 99.6 %
- **top 10 of 10,000 real text embeddings (Ollama, 2560 dims)**: Time: about 2 ms; Same as the exact answer: 100 %
- **top 10 with a filter that keeps a few objects**: Time: under 1 ms; Same as the exact answer: 100 % (compared directly)

- **wider search / exact answer**: `.numCandidates(n)` / `.exact()`
- **fewer near-duplicates (MMR)**: `.diverse()`
- **a quarter of the memory for the index (int8)**: `@Vector(dim = 384, quantization = INT8)`
- **re-order by a local model (Ollama)**: `.rerank(Doc::getText)`
- **vectors + words (RRF)**: `.hybrid(Doc::getBody)`

**Example 8: full-text search**

```java
@FullText String body;                                        // in the entity; english analyzer by default

db.query(Article.class).where(Article::getBody).matches("bread oven").list();   // every word, stems match
db.query(Article.class).search(Article::getBody, "baking bread", 10).list();     // most relevant first (BM25)
```
```sql
SELECT a.title FROM Article a WHERE MATCH(a.body, 'bak*') ORDER BY SCORE(a.body, 'baking bread') DESC LIMIT 10
```

- **`english` (default)**: any language; English stop words dropped, stems (Porter)
- **`standard`**: any language, as written
- **your own**: `Analyzers.register("name", text -> words)`
- **`"fresh bread"`**: phrase, the words next to each other, in order
- **`bak*`**: prefix
- **`HIGHLIGHT(a.body, 'bread')`**: matched words marked `<em>…</em>`, fragments of long texts

**Example 9: query plans**

```java
System.out.println(db.query(Person.class).where(Person::getCity).eq("Perth").limit(10).explainAnalyze());
```

- **Plan**: `explain()`; OQL: `EXPLAIN SELECT …`; Shows: steps and the index each condition uses
- **Plan, run**: `explainAnalyze()`; OQL: `EXPLAIN ANALYZE SELECT …`; Shows: also rows in → out and time per step

![Query plan in Studio](https://paganini2008.github.io/kookaburra/assets/studio-plan.png)

**Example 10: comments, renames, Java export**

```java
@Entity @Comment("A shop customer") @Renamed("com.example.Client")
public class Customer {
    @Id long oid;
    @Renamed("fullName") @Comment("Given and family name") String name;
}

db.exportSchema(Path.of("src/main/java"), null);   // the stored schema as Java classes
```

- **Comments**: `@Comment` on a class or field; kept in the schema, shown in Studio
- **Rename**: `@Renamed("old")`; old data read under the new name
- **Export**: `db.exportSchema(dir, pkg)`; entity classes with annotations, getters and setters

![Export as Java in Studio](https://paganini2008.github.io/kookaburra/assets/studio-java-export.png)

**In Studio**:

- objects referencing the selected one, the constraints of every field, nested values as a tree
- **Stats dashboard** with live write and transaction rates
- demo products with embeddings: `ORDER BY cosine_distance(p.embedding, '[1, 0, 0, 0]')`, right-click **Find similar**, **From text…** in the editor
- demo descriptions with a full-text index: `MATCH(p.description, 'kitchen')`
- **Explain** / **Analyze**: the query plan as a diagram; **Format** tidies the statement
- **Export as Java**: preview, edit and save the schema as classes; menu bar; comments as column hints; About shows a feature map

![Backlinks in Studio](https://paganini2008.github.io/kookaburra/assets/studio-backlinks.png)

![Constraints in Studio](https://paganini2008.github.io/kookaburra/assets/studio-constraints.png)

![Stats dashboard in Studio](https://paganini2008.github.io/kookaburra/assets/studio-stats.png)

## 7. Configuration

All settings live in one file, `kookaburra.properties`:

```properties
kookaburra.durability=SYNC               # SYNC = on disk before a write returns, ASYNC = faster
kookaburra.transactions=false            # true enables db.begin() / db.inTransaction()
kookaburra.tx-isolation=SNAPSHOT         # READ_COMMITTED, SNAPSHOT or SERIALIZABLE
kookaburra.tx-timeout=60s                # open transactions older than this are rolled back
kookaburra.embedder=ollama               # text to vector through a local Ollama (none = off)
kookaburra.embedder-model=nomic-embed-text
kookaburra.data=                         # Studio: default database (empty = data/ next to the jar)
```

Tuning keys (memory, file sizes, background work) are commented in the file; the defaults suit most applications.

## 8. Performance

Environment: Apple M2 Max (12 cores), 32 GB, internal SSD, JDK 17, `-Xmx8g`, **10 million objects**, measured with the `kookaburra-bench` harness (warm-up, p50, consistency checks after every phase).

```text
Operation (10 M objects)                           p50
-------------------------------------------------  -------------------
bulk insert, batches of 100 (ASYNC)                576 k objects/s
durable single inserts, 16 / 128 threads (SYNC)    3.3 k/s / 24.4 k/s
get(oid)                                           0.7 µs
count with an indexed filter                       0.23 ms
equality + range + limit 100                       0.19 ms
top-10 by indexed column, 2.3 M matches            0.39 ms
GROUP BY city COUNT(*)                             3.9 ms
unique-key lookup (OQL)                            0.11 ms
full scan, unindexed predicate                     0.78 s
vector top 10 of 100 k × 384 dims (HNSW)           1.0 ms, 99.6 % same as exact
transfers, 16 threads: SNAPSHOT / RC / SERIAL.     136 / 139 / 136 k/s
atomic increment of one hot counter, 16 threads    262 k/s, no lost updates
insert with @Unique / without (in memory)          251 / 318 k/s
online backup of 10.5 M objects                    25.7 s, writers not blocked
OQL DELETE of all objects / disk afterwards        1.31 M/s / 0 B left
reopen / heap after reopen                         2.1 ms / 7.1 MiB
```

## 9. Limitations & Trade-offs

- Each durable single write waits for the disk; batch or use `ASYNC` for bulk loads.
- Single-collection queries only, no joins: use references with `fetch`, or the semi-join `in(...)`.
- Optimistic transactions: conflicts surface at commit and are retried, never blocked.
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
10. One Studio jar; every feature tested in memory and on disk.
11. Vector search (HNSW) and full-text search (BM25) in the same database, fused by `hybrid(...)`.
12. `explainAnalyze()` and Studio's plan diagram show rows and time of every step.

*Source and docs: [github.com/paganini2008/kookaburra](https://github.com/paganini2008/kookaburra) · [paganini2008.github.io/kookaburra](https://paganini2008.github.io/kookaburra/)*
