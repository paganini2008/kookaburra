# Kookaburra: Embedded Object Database Design

> Status: **living document**, updated together with the code. See [§20 Changelog](#20-changelog).
>
> Related: [architecture.md](architecture.md) (layers, one picture) · [storage-design.md](storage-design.md) (segments, write path, compaction, recovery, backup) · [transaction-design.md](transaction-design.md) (transactions) · [developer-guide.md](developer-guide.md) (build, test, conventions)

---

## 1. Goals / Non-goals

| Goals | Non-goals (v1) |
|---|---|
| Pure-Java embedded object DB, **JDK 17+** | Full SQL, network server |
| **Positioning: single-collection (single-table) queries with SQL-92 core semantics**: `SELECT` (expressions, `DISTINCT`), `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, aggregates (`COUNT [DISTINCT]`, `SUM`, `AVG`, `MIN`, `MAX`), `LIMIT`/`OFFSET` | Multi-table `JOIN`, sub-queries in `FROM`, `UNION`, window functions, DDL |
| Object CRUD | Distributed / replicated storage |
| Bitmap-accelerated queries on **Apache RoaringBitmap**; runtime deps: RoaringBitmap + ANTLR 4 runtime (OQL) | Joins other than reference navigation |
| Type-safe, Stream-like query API (Speedment-inspired) | Implementing `java.util.stream.Stream` itself (interop only, §9.7) |
| Write throughput via LSM write path; read/write balanced; high concurrency | Cross-process access to the same DB directory |
| Snapshot isolation, optimistic locking, atomic batches | BSI index (reserved, §5.3) |
| Polymorphic queries over class hierarchies | |
| **OQL**: JPQL-style object query language compiled to the same Plan (§9.9) | |
| **Dynamic access** without entity classes, from the stored schema (§9.8) | |
| **Kookaburra Studio**: standalone JavaFX admin app, CRUD + query console (§17) | |

---

## 2. Architecture

The layers in one picture: [architecture.md](architecture.md). Components and their dependencies:

```mermaid
flowchart TB
    subgraph APP["Standalone app"]
        ST["Kookaburra Studio<br/>JavaFX · AtlantaFX · Ikonli · RichTextFX"]
    end
    subgraph API["API layer"]
        DB["ObjectDB / WriteBatch / Snapshot"]
        QS["QueryStream&lt;T&gt;"]
        OQL["OQL parser"]
        DYN["DynamicObject API"]
    end
    subgraph META["Field model"]
        F["Field / Pred / Order<br/>Person_ (generated) · where(Person::getAge)"]
    end
    subgraph MAP["Mapping layer"]
        SC["Schema · TypeRegistry · Codec"]
    end
    subgraph QRY["Query layer"]
        PL["Planner → Plan"] --> EX["Executor<br/>filter · sort · top-N · group · merge"]
    end
    subgraph ENG["Engine layer"]
        WR["Sequencer (write thread)"] --> WAL["Shared WAL<br/>group commit"]
        WR --> MEM["MemSegment<br/>(in-memory columns)"]
        VER["Version / Snapshot"]
        CMP["Flush · Compaction · Commit"]
    end
    subgraph STO["Storage layer"]
        SEG["Segment files (immutable)"]
        DEL["Delete bitmaps"]
        MF["MappedFile"]
    end
    QS --> F --> PL
    ST --> OQL --> PL
    ST --> DYN --> F
    DYN --> WR
    DB --> WR
    EX --> VER
    VER --> MEM
    VER --> SEG
    SC -.-> WR
    SC -.-> EX
    CMP --> SEG
    SEG --> MF
    DEL --> MF
```

**Key idea:** every segment (on-disk *or* in-memory) exposes the same `SegmentView` interface → one executor for all.

---

## 3. Data Model

### 3.1 Collections & inheritance

- **Collection** = one root `@Entity` + all its `@Entity` subclasses (single-table inheritance).
- Each concrete class has a **class id**; every collection has a built-in `$type` column (dict + posting).
- `from(Animal.class)` → all rows; `from(Dog.class)` → `OR(post[$type=Dog], post[$type=Puppy])`.

```mermaid
classDiagram
    class Animal {
        <<root entity>>
        String name
        int age
    }
    class Dog {
        String breed
    }
    class Cat {
        boolean indoor
    }
    class Puppy {
        String trainer
    }
    Animal <|-- Dog
    Animal <|-- Cat
    Dog <|-- Puppy
    note for Animal "Collection Animal<br>columns: $type, name, age, breed, indoor, trainer<br>subclass-only columns are null for other classes"
```

### 3.2 OID layout

```
 63 62            47 46                                           0
┌──┬───────────────┬──────────────────────────────────────────────┐
│0 │ classId (16)  │ per-class sequence (47)                       │
└──┴───────────────┴──────────────────────────────────────────────┘
```
- Always positive. Class id → concrete class → collection, so `get(Animal.class, oid)` needs no lookup to find the collection.
- Same-class objects cluster in OID order → `$type` postings become runs (compress well).

### 3.3 Entity mapping

```java
@Entity
public class Person {
    @Id      long oid;                          // assigned by DB
    @Version long version;                      // optimistic lock
    @Unique                   String email;     // unique constraint (implies an index)
    @Index                    String city;      // AUTO: BITMAP or ORDINAL per segment
    @Index                    BigDecimal salary;// exact decimal, numeric order
    @Index(ORDINAL)           int age;          // range / sort, no posting
                              String bio;       // row only
                              Address home;     // @Embeddable → paths home.city, home.zip
    @ForeignKey(onDelete = CASCADE)
    Ref<Company> employer;                      // stored as OID, lazy; checked reference
    @Index                    List<String> tags;// multi-valued
    @Transient                String cache;     // not persisted (same for `transient`)

    @PrePersist void created() { … }            // lifecycle callback (§3.7)
}

@Embeddable
public class Address {
    @Index String city;
           String zip;
}
```

| Java type | ValueType | Column key encoding | Notes |
|---|---|---|---|
| `boolean` / `Boolean` | BOOL | 1 byte | |
| `byte` `short` `char` `int` / boxed | INT32 | sortable 4 B | |
| `long` / `Long` | INT64 | sortable 8 B | |
| `float` `double` / boxed | FLOAT64 | sortable 8 B (§11) | |
| `BigDecimal` | DECIMAL | (scale, unscaled bytes), numeric order | exact; stored without trailing zeros (D49) |
| `String` | STRING | UTF-16 order (§11) | prefix-compressed dict |
| `enum` | ENUM | name; sort = declaration order | dict→ordinal permutation at query time |
| `LocalDate` | DATE | epochDay (8 B) | |
| `LocalDateTime` | DATETIME | (seconds, nanos) 16 B | |
| `Instant` | INSTANT | (seconds, nanos) 16 B | |
| `Ref<T>` | REF | OID (8 B) | not sortable |
| `List<S>` / `Set<S>` of scalar S | MULTI(S) | per element | |
| `@Embeddable` class | EMBEDDED | flattened paths | |
| `List` / `Set` of `@Embeddable` | EMBEDDED_LIST | n/a | row only |
| `Map<S1, S2>` of scalars | MAP | n/a | row only |
| `byte[]` | BYTES | n/a | row only |
| `float[]`, `@Vector(dim, distance)` | VECTOR | n/a | row only: `varint n · n × float32`; dimension checked on write (0 = any), finite components; searched by distance (§10.6) |
| any other `Serializable` (incl. `Map<String,Object>`, `UUID`) | OBJECT | n/a | row only, Java serialization |

Not supported: `record` entities (immutable, no id back-fill), `final` persistent fields, entities without a no-arg constructor, fields hiding superclass fields. Null collection elements / map keys are rejected.

Values are **normalized** before comparison: `short` / `byte` / `char` → `Integer`, `float` → `Double`, `BigDecimal` → numerically canonical (`1.50` → `1.5`, `100` never `1E+2`).

### 3.4 Self-describing schema

The schema file alone must be enough to decode, query and edit every row (Studio and dynamic access have no entity classes).

```
schema_V
└─ collection Animal
   ├─ class Animal  id=1  parent=-           abstract=false
   ├─ class Dog     id=2  parent=Animal
   │   └─ field breed  STRING  index=AUTO  nullable
   ├─ fields (per class, in declaration order)
   │   name STRING AUTO · age INT32 (no index) · home EMBEDDED(Address) · tags MULTI(STRING) AUTO
   │   level ENUM(LOW, MEDIUM, HIGH)       ← constants in declaration order (= sort order)
   │   employer REF(Company)
   └─ embeddables: Address { city STRING AUTO, zip STRING (no index) }
```

### 3.5 Schema evolution

Each class keeps a list of **layouts** (field lists). A changed Java class appends a new layout; rows store `(classId, layoutId)` and are decoded by field name into the current class.

```mermaid
flowchart LR
    L0["Person layout 0<br/>name, age:INT32, removed"] --> L1["Person layout 1<br/>name, age:INT64, city"]
    R0["row (1, 0)"] -.decode by name.-> OBJ["Person: name, age widened, city = null"]
    L1 -.current.-> OBJ
```

| Change | Handling |
|---|---|
| Add field | Old segments: column missing → reads as `null` / default |
| Remove field | Ignored on read; dropped at compaction |
| Widen `int → long` (INT32 → INT64, also inside collections / maps) | Widened on read |
| Enum: constants appended | OK; order = declaration order. Reordering / removing constants is rejected |
| Index kind added / changed | New layout; old segments simply lack the column (planner falls back to residual) |
| `@Unique` / `@ForeignKey` added | Stored data is checked first (like `ALTER TABLE ADD CONSTRAINT`): duplicates or dangling references fail the registration and the schema stays unchanged |
| Add subclass | New class id; old segments have no rows of it |
| Rename a field: `@Renamed("old")` on the field (entity or embeddable, any depth) | the former name is rewritten to the new one in every stored layout (rows decode by position, so old rows read under the new name); in-memory rows are decoded again; old segments' columns keep the old path until a merge (queries read the rows meanwhile); both names stored → rejected |
| Rename a class: `@Renamed("com.old.Name")` on the class | the class definition takes the new name, keeps id, OIDs, layouts and its collection (directory); the old name is kept as a **former name** (schema format 5) so references, OQL and dynamic access by the old name still find it; a renamed parent is followed by its subclasses |
| Comment added / changed: `@Comment` on a class or field | stored in the schema (class and field definitions, format 5); a change that touches only comments replaces the current layout instead of appending one, so no schema version is added |
| Any other type change | Rejected at open → explicit migration |

**Export as Java** (`db.exportSchema(targetPackage)` → `SchemaExport`, `exportSchema(dir, pkg)` writes it): one file per top-level class; entities (parent, `@Id`, `@Version`, class `@Unique`), the embeddables and enums they use, every field with its annotations (`@Index(kind)` unless implied by a constraint, `@Unique`, `@ForeignKey(onDelete)`, `@FullText(analyzer)`, `@Vector(dim, distance, quantization)`, `@Comment` plus Javadoc), wrapper types (the schema keeps no primitive / boxed difference), getters and setters. Nested classes (`Outer$Inner`) are written nested, missing outer classes as holders. Compiled, the classes reproduce the stored field lists exactly, so opening the database with them adds no layout (D69). A target package moves every class and adds `@Renamed(storedName)`: opening renames the stored classes. Warnings: serialized types not in `java.*`, references to classes that are not stored, embeddables stored with different fields.

### 3.6 Constraints

![Constraints](assets/foreign-keys.png)

| Annotation | On | Rule | Violation |
|---|---|---|---|
| `@Unique` | scalar field (also inside `@Embeddable`, not in embedded lists) | no two live objects of the declaring class and its subclasses share a non-null value; nulls never conflict; decimals compare numerically | `ConstraintViolationException(UNIQUE)` |
| `@Unique(fields = {…}, name)` | entity class, repeatable | no two live objects share the **combination** of values; an object with a null in any of the fields is not compared (SQL, PostgreSQL default); paths may be dotted (`place.city`) and declared in superclasses; the first field is indexed when none is | as above, `field()` = the paths |
| `@ForeignKey(onDelete)` | `Ref<T>`, `List / Set<Ref<T>>` (also inside `@Embeddable`) | every non-null reference points at a live `T` (or subclass) | `ConstraintViolationException(FOREIGN_KEY)` |
| `onDelete = RESTRICT` (default) | target deleted | the delete fails while referrers exist | as above |
| `onDelete = CASCADE` | target deleted | referrers deleted in the same commit, recursively | none |
| `onDelete = SET_NULL` | target deleted | `Ref` cleared, collection element removed; referrer gets a new version | none |

- Composite constraints are stored per class (`ClassDef.uniques`, schema format 3) and checked with the same lookup on their first indexed path; candidates are compared on every path.
- Both imply `@Index(AUTO)`: checks are point lookups (index candidates confirmed with `find(oid, seq)`), cost follows the matches, not the collection size.
- Checked on the sequencer when the write commits (§6.5), against the latest state plus the batch's own writes: a batch may swap two unique values, insert a target and its referrer together, or delete a referrer and its target together. Inside a transaction the checks are deferred to `commit()` (like `DEFERRABLE INITIALLY DEFERRED`).
- Cascaded deletes and cleared references are appended to the batch: one WAL record, one sequence number, replayed as logged. They are storage-level (like SQL): no lifecycle callbacks.
- Backlinks: `db.referrers(oid)` / `tx.referrers(oid)` returns every object pointing at an OID through any reference path (`Ref`, collections of refs, refs inside embedded objects and lists); indexed paths use the index, the others a scan.
- Not retried by `inTransaction`: the same writes would fail again.

### 3.7 Lifecycle callbacks

| Event | Annotation / `EntityListener` method | When |
|---|---|---|
| PRE_PERSIST / PRE_UPDATE / PRE_REMOVE | `@PrePersist` `@PreUpdate` `@PreRemove` | on the caller thread when the write is issued (in a transaction: at `tx.insert / update / delete`); changes made here are stored; an exception cancels the write |
| POST_PERSIST / POST_UPDATE / POST_REMOVE | `@PostPersist` `@PostUpdate` `@PostRemove` | after the commit is durable (in a transaction: after `commit()`); nothing on rollback |
| POST_LOAD | `@PostLoad` | every object read as an entity (get, queries, OQL results) |

- Callback methods: no arguments, any visibility, inherited (superclass methods first, an override runs once). Listeners: `db.addListener(type, listener)`, matched with `isInstance`, run before the entity's own methods, in registration order.
- Skipped, like JPA: bulk OQL `UPDATE / DELETE`, deletes by OID, increments, `DynamicObject`s, FK cascades.

---

## 4. Directory Layout

Moved to [storage-design.md §3](storage-design.md#3-directory-layout).

- One directory per database, `LOCK`, `CURRENT` → `commit_N`, `schema/`, `wal/`, `col/<collection>/seg_N.odbf` + `.del` generations.
- Atomic file writes: `*.tmp`, fsync, rename, directory fsync.

---

## 5. Segment Format

Moved to [storage-design.md §4](storage-design.md#4-segment-format) (layout, encodings, index kinds, row encoding, vector columns and HNSW graphs) and [§5](storage-design.md#5-where-roaringbitmap-is-used) (RoaringBitmap usage).

- Immutable file per segment, rows sorted by OID, docid = row number.
- Indexed fields: sorted dictionary, bit-packed ordinals, Roaring postings or sorted docids (`AUTO`).
- Rows: `RecordCodec`, path-only decoding.

---

## 6. Write Path

§6.1 to §6.4 moved to [storage-design.md §6](storage-design.md#6-write-path): flow, MemSegment, delete logs, flush and commit.

- One sequencer thread, WAL with group commit, MemSegment, background flush into segments.

### 6.5 Integrity checks

![Write path](assets/write-path.png)

`Integrity.check(ops)` runs on the sequencer after version validation and before the WAL append; it returns the batch unchanged when the schema has no constraints.

1. **Final state** per touched OID: the batch's last record, or deleted (transactions also pass OIDs they inserted and deleted again: never stored, but treated as deleted for the batch's own references).
2. **Deletes** (worklist, so cascades recurse): for every foreign key whose target class covers the deleted object, find live referrers (index point lookup + the batch's own records); RESTRICT throws, CASCADE appends a delete, SET_NULL rewrites the referrer's record (its own op if the batch writes it, else a new update with version + 1).
3. **References**: every reference in a final record must point at an object that is live in the batch or in the latest state, of the target class.
4. **Unique**: per constraint, duplicates among the final records, then an index point lookup for each value excluding objects the batch itself changes.

Failure rejects the whole batch (nothing logged); appended ops are logged, applied and published with it, and recorded in the conflict table so concurrent transactions on those objects conflict.

---

## 7. MVCC: Versions & Snapshots

Moved to [storage-design.md §7](storage-design.md#7-versions-and-snapshots-mvcc).

- `Snapshot = (Version, visibleSeq)`, immutable `Version`, late-bound query snapshots.

---

## 8. Point Lookup

Moved to [storage-design.md §8](storage-design.md#8-point-lookup-getoid).

- MemSegments newest first (version chain), then segments (bloom, OID binary search, delete bitmap).

---

## 9. Query API

### 9.1 Field model (Speedment-inspired)

```mermaid
classDiagram
    class Field~T,V~ {
        <<interface>>
        +Class~T~ entity()
        +String path()
        +ValueType type()
        +V get(T)
        +Pred~T~ isNull()
        +Pred~T~ isNotNull()
    }
    class AbstractField~T,V~ { interned per entity+path }
    class ComparableField~T,V~ { eq notEq in notIn gt ge lt le between asc desc }
    class IntField~T~
    class LongField~T~
    class DoubleField~T~
    class BoolField~T~ { isTrue isFalse }
    class StringField~T~ { startsWith }
    class EnumField~T,E~
    class ListField~T,E~ { contains containsAny containsAll }
    class RefField~T,R~ { eq(oid) in matches(Pred~R~) }
    class ObjectField~T,V~ { then(getter) for embedded }
    Field <|.. AbstractField
    AbstractField <|-- ComparableField
    ComparableField <|-- IntField
    ComparableField <|-- LongField
    ComparableField <|-- DoubleField
    ComparableField <|-- BoolField
    ComparableField <|-- StringField
    ComparableField <|-- EnumField
    AbstractField <|-- ListField
    AbstractField <|-- RefField
    AbstractField <|-- ObjectField
```

**Predicates** (`sealed interface Pred<T> extends java.util.function.Predicate<T>`):

```
Pred ── Eq · In · Range(lo, loInc, hi, hiInc) · IsNull · StartsWith · Contains(Any/All)
     ── InstanceOf(Class) · RefMatch(Pred) · RefIn(subquery)
     ── And · Or · Not · Residual(java.util.function.Predicate)
```
- `and / or / negate` overridden → stay inside `Pred` (metadata preserved).
- Every `Pred` is executable (`test(T)`): used for MemSegment fallback, residual evaluation and differential tests.

**Orders** (`Order<T> extends Comparator<T>`): `field.asc()`, `field.desc()`, `.nullsFirst()`, `.nullsLast()`; `thenComparing` / `reversed` overridden.

### 9.2 Two ways to get a field, freely mixed

| | Generated metamodel | Method reference |
|---|---|---|
| Syntax | `Person_.age.gt(30)` | `where(Person::getAge).gt(30)` |
| Needs | `kookaburra-processor` on annotation path | a getter (`getX` / `isX` / record accessor) |
| Embedded path | `Person_.home.city` | `where(Person::getHome).then(Address::getCity)` |
| Resolves to | same `Field` instance (registry keyed by `(class, path)`) | ← |

```mermaid
flowchart LR
    MR["where(Person::getAge)"] --> SL["Getter extends Serializable<br/>→ writeReplace() → SerializedLambda"]
    SL --> CHK{"implMethodKind = invokeVirtual/Interface<br/>and name is a property getter?"}
    CHK -- no --> ERR["IllegalArgumentException<br/>'not a property reference'"]
    CHK -- yes --> PROP["entity = instantiatedMethodType arg0<br/>property = getAge → age"]
    PROP --> REG["FieldRegistry (ClassValue cache)"]
    GEN["Person_.age (generated)"] --> REG
    REG --> FLD["IntField&lt;Person&gt; 'age'"]
```

`where(...)` overloads (verified on JDK 17: exact method refs resolve by return type):
`IntGetter → IntField`, `LongGetter → LongField`, `DoubleGetter → DoubleField`, `BoolGetter → BoolField`, `StringGetter → StringField`, `ComparableGetter → ComparableField` (boxed, enum, dates; a boxed `Integer` property still yields the interned `IntField`), `CollectionGetter → ListField`, `RefGetter → RefField`, `Getter → ObjectField`.
Lambdas (`p -> p.getAge()`), bound references and non-persistent getters are rejected.

Generated metamodel naming: `Person` → `Person_`; nested `Outer.Inner` → `Outer_Inner_`; embedded `home` → nested class `Home_ extends EmbeddedPath` with instance fields (`Person_.home.city`). Recursive embeddables are a compile error.

### 9.3 QueryStream

**API style: chained everywhere** (D37). Conditions, sort keys and slices are method calls on the stream:

```java
List<Person> page = db.query(Person.class)
    .where(Person::getCity).eq("Sydney")          // indexed
    .where(Person::getAge).gt(30)
    .orderBy(Person::getAge).desc()
    .orderBy(Person::getName).asc()
    .skip(20).limit(10)
    .list();

List<DynamicObject> rows = db.dynamic("Person").from()            // dynamic: stored paths
    .where("home.city").eq("Perth").orderBy("age").desc().list();
```

| Chained call | Equivalent | Notes |
|---|---|---|
| `query(X.class)` | `from(X.class)` | on `ObjectDB`, `Transaction` |
| `.where(getter)` → `eq / notEq / in / gt / ge / lt / le / between / isNull / isNotNull / isTrue / isFalse / startsWith / contains` | `.filter(Q.where(getter).eq(...))` | conditions AND together; reference fields compare by OID; `.where("path")` for dynamic queries |
| `.orderBy(getter)` → `asc / desc / ascNullsFirst / descNullsLast` | `.sorted(Q.where(getter).desc(), ...)` | keys apply in call order |
| `.list()` | `.toList()` | |
| `.filter(or(a, b))`, `.filter(not(a))` | n/a | OR / NOT in the same chain |

Pipeline has a **fixed canonical order**; calls out of order throw `IllegalStateException`.

```mermaid
flowchart LR
    FROM["db.from(Class)"] --> FI["filter(...)*"]
    FI --> SO["sorted(Order...)?"]
    SO --> SL["skip / limit?"]
    SL --> PR["select / map / distinct?"]
    PR --> T["terminal"]
    SL --> T
    FI --> GB["groupBy(...).agg(...)"]
    GB --> HV["having(...)?"]
    HV --> GS["sortedBy(...)? skip / limit?"]
    GS --> T
```

```java
import static com.github.kookaburra.query.Q.*;

List<Person> page = db.from(Person.class)
    .filter(Person_.city.eq("Sydney").and(where(Person::getAge).gt(30)))  // indexed
    .filter(p -> p.getBio() != null)                                      // residual
    .sorted(Person_.age.desc(), Person_.name.asc())
    .skip(20).limit(10)
    .toList();

long n          = db.from(Person.class).filter(Person_.tags.contains("vip")).count();
List<String> cs = db.from(Person.class).map(Person_.city).distinct().toList();
List<Tuple2<String,Integer>> t = db.from(Person.class).select(Person_.name, Person_.age).toList();
List<NameAge> r = db.from(Person.class).select(NameAge::new, Person_.name, Person_.age).toList();

List<Row> stats = db.from(Person.class)
    .filter(Person_.employer.matches(Company_.industry.eq("AI")))
    .groupBy(Person_.city)
    .agg(count(), avg(Person_.age), max(Person_.age))
    .having(count().gt(10))
    .sortedBy(count().desc())
    .toList();

List<Animal> dogs = db.from(Animal.class).filter(instanceOf(Dog.class)).toList();
String plan = db.from(Person.class).filter(...).explain();
```

### 9.4 SQL ↔ API ↔ execution

| SQL | API | Execution |
|---|---|---|
| `FROM Person` | `db.from(Person.class)` | snapshot; `$type` filter if subclass |
| `WHERE a = ? AND b > ?` | `filter(Person_.a.eq(..).and(Person_.b.gt(..)))` | dict search → post / ord range → AND |
| `WHERE <java code>` | `filter(p -> ...)` | residual: decode candidates |
| `ORDER BY a DESC, b` | `sorted(Person_.a.desc(), Person_.b.asc())` | per-segment ordinal sort → k-way merge by value |
| `LIMIT n OFFSET m` | `skip(m).limit(n)` | top-(m+n) per segment, merge, stop early |
| `SELECT a, b` | `select(Person_.a, Person_.b)` | read columns (ord → dict), no row decode |
| `SELECT DISTINCT a` | `map(Person_.a).distinct()` | ordinals whose post ∩ candidates ≠ ∅ |
| `COUNT(*)` | `count()` | cardinality |
| `EXISTS` | `exists()` / `first()` | stop at first non-empty segment |
| `MIN/MAX(a)` | `min(Person_.a)` / `max(...)` | first / last ordinal intersecting candidates |
| `SUM/AVG(a)` | `sum(Person_.a)` / `avg(...)` | scan ord over candidates (BSI later) |
| `GROUP BY a` + aggs | `groupBy(Person_.a).agg(...)` | per-ordinal arrays; count via `andCardinality` |
| `HAVING` | `having(count().gt(10))` | in-memory over groups |
| join via FK | `filter(Person_.employer.matches(pred))` | subquery → OID set → ref dict → OR posts |
| semi-join | `filter(Person_.employer.in(db.from(Company.class).filter(..)))` | same, subquery not materialized |
| eager join fetch | `fetch(Person_.employer)` | batch `getAll` of referenced OIDs |
| `UPDATE … WHERE` | `updateEach(p -> ...)` | one batch, optimistic lock per object |
| `DELETE … WHERE` | `deleteAll()` | candidates → delete log, no decode |

### 9.5 Terminals

`toList` · `first` · `count` · `exists` · `forEach` · `min` / `max` / `sum` (long / double / exact `BigDecimal`) / `avg` (double) · `cursor()` (`AutoCloseable`) · `stream()` · `explain()`.

Database-level: `referrers(oid)` / `referrers(Class, oid)` (backlinks, §3.6), `addListener` / `removeListener` (§3.7), `backup(Path)` (§15.2), `inTransaction(TxOptions, work)`.

### 9.6 Resources

- All terminals except `cursor()` / `stream()` release their snapshot before returning.
- `cursor()` / `stream()` hold the snapshot until closed or exhausted; a `Cleaner` is the safety net.

### 9.7 JDK Stream interop

`QueryStream.stream()` returns a `java.util.stream.Stream<T>` over the **pushed-down** result; further operations run in the JDK. `onClose` releases the snapshot.

### 9.8 Dynamic access (no entity classes)

```mermaid
flowchart LR
    SCH["schema_V"] --> DS["DynamicSchema"]
    DS --> DF["DynField: path + ValueType<br/>implements Field&lt;DynamicObject,V&gt;"]
    DF --> PRED["same Pred / Order / Plan"]
    DO["DynamicObject<br/>class · oid · version · Map&lt;path,value&gt;"] --> CODEC["Codec (schema-driven)"]
    CODEC <--> ROW["row bytes"]
```

```java
DynamicCollection people = db.dynamic("Person");            // simple name if unique, else fully qualified
DynField age = people.field("age");
List<DynamicObject> rs = people.from().filter(age.gt(30)).sorted(age.desc()).limit(10).toList();

DynamicObject o = people.newObject().set("name", "Ann").set("home.city", "Perth").set("level", "HIGH");
long oid = people.insert(o);   o.set("age", 31);   people.update(o);   people.delete(o);
```

- `QueryStream` is generic over a **target** (stored class + how documents become results): typed entities, `DynamicObject`s, or OQL's mix (evaluate on `DynamicObject`, return entities).
- Values accepted by `DynField` / `set`: Java values or their canonical forms (enum names, OIDs for refs, ISO strings for dates); validated against the schema on write (`DynamicCodec`).

- `DynField` implements `Field<DynamicObject, V>` → `Pred`, `Order`, planner and executor are reused unchanged.
- Values are canonical (§3.3); embedded values are nested `Map`s; refs are `Ref`; enums are constant names validated against the schema.
- Typed and dynamic access may be used on the same database concurrently.

### 9.9 OQL (Object Query Language, JPQL-style)

Text queries for applications and Studio's console; parsed into the same `Plan` as `QueryStream`. Not SQL: it queries **objects**, navigates paths and references, and is polymorphic.

```mermaid
flowchart LR
    G4["Oql.g4 (ANTLR 4)"] -.generates.-> LEX
    TXT["OQL text + params"] --> LEX["OqlLexer"] --> PAR["OqlParser → parse tree"] --> VIS["AstBuilder (visitor) → AST"] --> RES["Resolver<br/>aliases · paths · types (schema)"]
    RES --> PLAN["Plan (Pred / Order / projection / group)"]
    PLAN --> EXE["Executor"]
    EXE --> R1["List&lt;T&gt; (typed)"]
    EXE --> R2["List&lt;DynamicObject&gt;"]
    EXE --> R3["List&lt;Row&gt; (projections, aggregates)"]
```

**Grammar (JPQL syntax, SQL-92 core semantics; normative version: `kookaburra-core/src/main/antlr4/.../Oql.g4`)**

```
statement   := select | update | delete
select      := SELECT [DISTINCT] selectList FROM Entity [[AS] alias]
               [WHERE expr] [GROUP BY expr {, expr} [HAVING expr]]
               [ORDER BY orderItem {, orderItem}] [LIMIT n] [OFFSET m]
selectList  := '*' | alias | NEW className '(' selItem {, selItem} ')' | selItem {, selItem}
selItem     := expr [[AS] name]
update      := UPDATE Entity [[AS] alias] SET path '=' expr {, path '=' expr} [WHERE expr]
delete      := DELETE FROM Entity [[AS] alias] [WHERE expr]
orderItem   := (expr | resultAlias | position) [ASC | DESC] [NULLS FIRST | NULLS LAST]

expr        := expr OR expr | expr AND expr | NOT expr
             | expr cmp expr | expr [NOT] BETWEEN expr AND expr | expr [NOT] IN '(' expr {, expr} ')'
             | expr [NOT] LIKE pattern [ESCAPE 'c'] | expr IS [NOT] NULL | path IS [NOT] EMPTY
             | expr [NOT] MEMBER [OF] path | TYPE '(' alias ')' [NOT] IN '(' Entity {, Entity} ')'
             | expr ('+' | '-' | '*' | '/' | '%' | '||') expr | '-' expr | '(' expr ')'
             | CASE WHEN expr THEN expr {WHEN expr THEN expr} [ELSE expr] END
             | fn '(' expr {, expr} ')' | agg | path | literal | :name | ?n
fn          := UPPER | LOWER | TRIM | LENGTH | SUBSTRING | ABS | MOD | COALESCE | NULLIF | SIZE
agg         := COUNT '(' '*' ')' | (COUNT | SUM | AVG | MIN | MAX) '(' [DISTINCT] expr ')'
cmp         := = | <> | != | < | <= | > | >=
literal     := number | 'string' | TRUE | FALSE | NULL | DATE 'yyyy-MM-dd'
             | TIMESTAMP 'yyyy-MM-dd HH:mm:ss[.n]' | INSTANT 'yyyy-MM-ddTHH:mm:ss[.n]Z' | EnumClass.CONSTANT
path        := [alias '.'] field {'.' field}   -- alias optional; implicit dereference through Ref and @Embeddable
```

| Clause | Semantics (SQL-92) |
|---|---|
| `WHERE` | rows where `expr` is TRUE (two-valued: comparisons with NULL are FALSE, §11) |
| `GROUP BY` | one row per distinct key tuple (NULL is a key); select items must be keys or aggregates |
| `HAVING` | filter on groups; may use aggregates and keys |
| aggregates | ignore NULL inputs; `COUNT(*)` counts rows; no non-NULL input → `COUNT` 0, others NULL (also in the typed `GroupStream`; `QueryStream.sum()` returns 0); `SUM(int)` → long, `AVG` → double |
| `DISTINCT` | on the full select list, before `ORDER BY` / `LIMIT` |
| `ORDER BY` | expression, select alias or 1-based position; default `ASC NULLS LAST` |
| expressions | evaluated on canonical values (no entity classes needed); `/` on integers is integer division; `||` concatenates strings |

**Implementation notes**

- Grammar is layered like SQL (`OR < AND < NOT < predicate < value expression < primary`), so `BETWEEN … AND …` / `LIKE … ESCAPE …` never swallow a following `AND` (a bug the OQL differential suite caught).
- Push-down: `path ⋈ constant` (either side), `BETWEEN`, `IN`, `LIKE 'prefix%'`, `IS [NOT] NULL`, `MEMBER OF`, `TYPE()`; paths through references become sub-queries (`DynRefMatch`). Everything else is evaluated per object (`RESIDUAL` in explain). Literals that do not fit the column type (e.g. `age > 30.5`) are evaluated, not coerced.
- `ORDER BY` of plain stored paths is pushed down (top-N); otherwise sorting happens in memory. Enum keys sort by declaration order everywhere.
- `GROUP BY` / aggregates on plain stored paths run on canonical values without materializing objects (**fast path**); any expression switches to per-object evaluation. Both are checked against each other in tests.
- `TYPE(a)` is exact (JPQL); `instanceOf` in the typed API includes subclasses.
- Unknown paths in any clause are errors (with line / column).

**Mapping to the plan**

| OQL | Plan |
|---|---|
| `SELECT p FROM Person p` | objects of Person + subclasses |
| `p.home.city = 'Perth'` | `Eq(home.city)` → column |
| `p.employer.industry = 'AI'` / `JOIN p.employer e WHERE e.industry = 'AI'` | `RefMatch(employer, industry = 'AI')` |
| `'vip' MEMBER OF p.tags` | `Contains(tags, vip)` |
| `p.tags IS EMPTY` | `IsNull(tags) OR size = 0` (residual for size) |
| `p.name LIKE 'Ann%'` | `StartsWith(name, 'Ann')`; other patterns (`%x%`, `_`, `ESCAPE`) → expression filter |
| `WHERE p.price * p.qty > 100`, `UPPER(p.name) = 'ANN'` | expression filter on canonical values (no index) |
| `SELECT p.name, p.price * p.qty AS total … ORDER BY total DESC` | computed projection + sort on computed key |
| `COUNT(DISTINCT p.city)` | distinct-set aggregate |
| `TYPE(a) IN (Dog, Cat)` | `InstanceOf` exact-class set (`$type` postings) |
| `SELECT NEW x.NameAge(p.name, p.age)` | projection + constructor |
| `SELECT p.city, COUNT(p) … GROUP BY p.city HAVING COUNT(p) > 10` | group / agg / having |
| `UPDATE … SET … WHERE` / `DELETE … WHERE` | `updateEach` / `deleteAll` in one batch |

**API**

```java
List<Person> ps = db.oql("SELECT p FROM Person p WHERE p.city = :city AND p.age > ?1 ORDER BY p.age DESC", Person.class)
                    .param("city", "Sydney").param(1, 30).limit(10).list();
List<Row> rows  = db.oql("SELECT p.city, COUNT(p) AS n FROM Person p GROUP BY p.city ORDER BY n DESC").rows();
long changed    = db.oql("UPDATE Person p SET p.active = FALSE WHERE p.age > 90").execute();
String plan     = db.oql("SELECT p FROM Person p WHERE 'vip' MEMBER OF p.tags").explain();
List<DynamicObject> any = db.oql("SELECT a FROM Animal a WHERE TYPE(a) = Dog").dynamic().list();   // no classes needed
```
Entity names: simple class name if unique in the schema, else fully qualified.

---

## 10. Query Execution

### 10.1 Plan

```mermaid
flowchart TB
    QS["QueryStream calls"] --> P["Plan<br/>collection · typeSet · Pred tree · residuals<br/>orders · skip · limit · projection · group/aggs"]
    P --> N["Normalize: flatten And/Or, push Not down,<br/>split And into indexable ∧ residual,<br/>Or containing Residual → whole Or residual"]
    N --> PS["for each SegmentView in Version (parallel optional)"]
    PS --> C1["candidates = indexable(Pred)  [RoaringBitmap]"]
    C1 --> C2["∧ typeSet bitmap  − deletes@snapshot"]
    C2 --> C3["order indexable conjuncts by estimated cardinality"]
    C3 --> C4["residual: decode needed columns/rows, test"]
    C4 --> OUT{"shape"}
    OUT -- unordered --> CAT["concatenate"]
    OUT -- sorted --> KM["per-segment sorted runs → k-way merge by value"]
    OUT -- grouped --> GM["per-segment ordinal groups → values → hash merge"]
    OUT -- count --> SUM["sum of cardinalities"]
```

### 10.2 Predicate evaluation per index kind

| Pred | BITMAP | ORDINAL | MemSegment | no column |
|---|---|---|---|---|
| `Eq` | dict search → post | dict search → scan ord = k | key → post list | residual |
| `In` | OR of posts | scan ord ∈ set | OR of post lists | residual |
| `Range` | ord range `[lo,hi)`: OR posts if `hi−lo ≤ 64`, else scan ord | scan ord in range | scan values | residual |
| `StartsWith` | dict prefix → ord range | dict prefix → ord range | scan values | residual |
| `IsNull` | null bitmap | null bitmap | scan values | residual |
| `Contains` (MULTI) | post | scan multi | post list | residual |
| `InstanceOf` | `$type` posts | n/a | `$type` posts | n/a |
| `RefMatch` | subquery → OIDs → ref dict → posts | scan | scan | residual |
| `Not(x)` | `[0,N) − x` | same | same | residual |
| `Residual` | n/a | n/a | n/a | decode row, `test` |

Scans of `ord` are restricted to the current candidate bitmap when one exists.

### 10.3 Sort / top-N

- **Index order** (one sort key with post or sdoc, `limit` given): walk documents in value order (`ColumnIndex.forEachInOrder`), keep candidates, stop after `skip+limit` per segment. Used when `bound × N ≤ 4 × matches²` (else scanning the matches is cheaper); not when matching nulls sort first.
- Otherwise: read the key of every match into a bounded max-heap of `skip+limit`; a candidate not better than the heap top is rejected without allocation.
- Across segments: candidates of all segments meet in the same heap, compared by **value**; ties by (segment, docid).
- Unindexed sort key: values are read from the row with path-only decoding (other fields skipped, §5.4).

### 10.4 Group / aggregate

| Case | Per segment | Rows visited |
|---|---|---|
| one key with post, only `COUNT(*)` | `andCardinality(post[o], matches)` per value + nulls | **none** |
| one key with dict ordinals | states in an array indexed by ordinal; key decoded once per value | matches (no hashing) |
| several keys / no column | canonical key tuple → hash map | matches |

Cross-segment: ordinal → value, merge in hash map (`count` add, `sum` add, `avg` (sum,count), `min/max` compare, `count distinct` set union).

### 10.5 Query plans: explain and explain analyze

A plan is a tree of steps, the root being the result (as PostgreSQL's `EXPLAIN`): `QueryStream.plan()` / `explain()`
show how the query will run without running it; `analyze()` / `explainAnalyze()` run it (results materialized as by
`list()`) and add the rows each step produced (`in → out`) and its time (as `EXPLAIN ANALYZE`). OQL: `EXPLAIN …`,
`EXPLAIN ANALYZE SELECT …` (SELECT only: an UPDATE or DELETE would change data), `OqlQuery.plan()` for the tree.

```
Query  Person · snapshot 2 · 2 segments                         10 rows   3.62 ms
└─ Fetch objects  materialize the results                       10 rows   0.43 ms
   └─ Limit  10                                                 10 rows
      └─ Top-N sort  age DESC NULLS FIRST                       20 → 10 rows   1.43 ms
         └─ Filter  city = 'Perth' AND score IN (90, +∞)        1,001 → 20 rows   1.76 ms
            ├─ mem#3  1 object                                  1 → 0 rows   0.55 ms
            │  └─ AND …
            └─ seg#2  1,000 objects                             1,000 → 20 rows   1.21 ms
               └─ AND                                           1,000 → 20 rows   1.19 ms
                  ├─ city = 'Perth'  [postings card=4]          1,000 → 250 rows   0.05 ms
                  └─ score IN (90, +∞)  [scan: reads each row]  250 → 20 rows   1.09 ms
```

| Step | Meaning |
|---|---|
| Query | collection, snapshot, segment views |
| Fetch objects | materializing the results (analyze only) |
| Limit | `skip` / `limit` |
| Diverse / Rerank / Hybrid | re-ordering of the closest candidates (§10.6) |
| Top-N sort / Sort · Nearest · Full-text search | ordering: by keys, by vector distance (HNSW or exact per segment), by BM25 |
| Filter | the planned condition, then one step per segment view and its conditions in evaluation order, each with its access: `postings`, `ordinals`, `sorted docs`, `OID lookup`, `$type`, `full-text index`, `scan: reads each row`, `decode rows` |

- `QueryPlan.Step(operation, detail, access, inputRows, rows, nanos, children)`; `Access` = INDEX, FULLTEXT, VECTOR, SCAN, PROCESS, NONE colours the Studio diagram.
- Measuring: an `exec.Profile` passed to the executor records per view the candidates, matches and time, and per condition (with its depth) the documents in and out and the time; without a profile nothing is recorded.
- Studio draws the tree (§17.3): Explain / Analyze buttons, steps coloured by access, rows and time bars, the slowest working step marked, a text view.

### 10.6 Vector search

```mermaid
flowchart LR
    F["filters → matches bitmap<br/>(indexes, as any query)"] --> V{"per segment view"}
    V -->|"graph for this distance,<br/>limit, matches > ef"| H["HNSW search, ef = max(numCandidates, k)<br/>matches = accept set"]
    H -->|"compared more vectors<br/>than there are matches"| X
    V -->|"no graph / exact() / few matches"| X["exact scan of the vector column<br/>(floats read in place)"]
    V -->|"in-memory segment"| R["exact scan of the records"]
    H --> M["merge: bounded max-heap of skip + limit<br/>(exact float distances, ties in storage order)"]
    X --> M
    R --> M
```

| | Kookaburra | pgvector | Lucene / Elasticsearch |
|---|---|---|---|
| Type | `float[]` + `@Vector(dim, distance)` | `vector(n)` | `dense_vector` / `KnnFloatVectorField` |
| Distances | COSINE (`1 - cos`), L2, INNER_PRODUCT (`-a·b`) | `<=>`, `<->`, `<#>` (+ L1) | cosine, l2_norm, dot_product, max_inner_product |
| Index | HNSW per segment, built at flush / merge (`m = 16`, `ef_construction = 100`) | HNSW (`m = 16`, `ef_construction = 64`) / IVFFlat | HNSW per segment (`m = 16`, `beam_width = 100`) |
| Query | `nearest(Doc::getEmbedding, q, k)`, `.numCandidates(n)`, `.exact()`; OQL `ORDER BY cosine_distance(d.v, :q) LIMIT k` | `ORDER BY v <=> q LIMIT k`, `hnsw.ef_search` | `knn { k, num_candidates }` |
| Filters | pre-filter bitmap = accept set of the graph search; exact when selective | post-filter (iterative scans since 0.8) | pre-filter as accept set; exact when selective |
| Text query | `nearest(field, "text", k)`, OQL `EMBED('text')` (Ollama) | none (app side) | inference endpoints |

**Storage** ([storage-design.md §4.5](storage-design.md#45-vector-columns-and-hnsw-graphs)): a `@Vector(dim = N)` path gets a vector column per segment: the docs with a vector of that dimension, their floats contiguous (read with an absolute bulk get, no row decoding, no `float[]` per row), and, from `kookaburra.vector-index-min-rows` vectors on, the HNSW graph (bottom layer `2m` links per node, upper layers `m`, diversity heuristic). Free-length vectors (`dim = 0`) and in-memory segments are scanned exactly. The graph is built for the field's distance; a query with another distance scans exactly. Cosine graphs are built on unit vectors (`1 - dot`).

**Search per segment** (Lucene's strategy):
- no limit, `exact()`, no graph, or at most `ef` matches → exact scan of the matches;
- else HNSW: greedy descent through the upper layers, beam search with `ef` candidates on the bottom layer; every node is a stepping stone, only matches enter the results; when the search compares more vectors than there are matches (a selective filter) it stops and the matches are scanned exactly;
- results carry exact float32 distances and are merged across segments with the in-memory segment.

**int8** (`@Vector(quantization = INT8)`, as Elasticsearch's `int8_hnsw`): per segment the range of 99 % of the components (0.5 / 99.5 percentiles of up to 100 000 sampled values) is split into 256 steps; the vector column also holds one byte per component, the graph search reads those (a quarter of the memory), and all `ef` candidates are re-ranked with the float vectors, so results carry exact distances. Floats stay for re-ranking and merges.

**Quality**: recall@10 against the exact search is 0.996 on 100 k × 384 clustered vectors (`ef = 100`) and 1.000 on 10 k real 2560-dim text embeddings (`qwen3-embedding:4b` through Ollama). `numCandidates` trades latency for recall up to 1.0; `exact()` is the true answer.

**Cost** (M2 Max, JDK 17): 100 k × 384: top 10 about 1 ms with HNSW, 21 ms exact (was 35 ms reading rows); graph build about 10 s per 100 k vectors at flush (two flush threads).

**Re-ordering the closest candidates** (both need a limit; the first pass takes `max(20, 4 k)` candidates unless `fetch` is given):
- `diverse(λ, fetch)`: maximal marginal relevance (LangChain `max_marginal_relevance_search`, Elasticsearch / Qdrant MMR): greedy picks maximising `-λ · d(q, x) + (1 - λ) · min d(x, picked)`; λ = 1 is the plain order.
- `rerank(textField[, query, fetch])`: a `Reranker` re-scores each candidate's text against the query text (by default the text of `nearest(field, "text", k)`); built in: Ollama `/api/generate`, the model answers whether the document meets the query with one token and the score is `P(yes) / (P(yes) + P(no))` from its log-probabilities (Qwen3-Reranker method, any instruct or reranker model); requests sent concurrently; `Neighbor.score()` carries it.

**Embeddings**: `Embedder` (text → `float[]`) in core, no dependencies; built in: Ollama `/api/embed` (`kookaburra.embedder=ollama`, model, URL, timeout; batches of 64; contacted only when used). `Config.withEmbedder(...)` plugs in anything else. Used by `nearest(getter, "text", k)`, OQL `EMBED('text')` (once per distinct text and statement) and Studio's "From text" button.

- `QueryStream.nearest(...)` orders by distance (field's `@Vector` distance unless given) and allows `skip / limit` after it; not combined with `orderBy` / `groupBy`. Objects without a vector, or with one of another length (`dim = 0`), are not results.
- `listWithDistance()` → `Neighbor(value, distance)`; `count()` / `exists()` count results with a vector.
- OQL functions `COSINE_DISTANCE`, `L2_DISTANCE`, `INNER_PRODUCT` (positive dot), `VECTOR_DIMS`, `EMBED`; vectors as stored values, `float[]` / `double[]` / list parameters or text `'[1, 0, 0]'`. A single ORDER BY item `COSINE_DISTANCE / L2_DISTANCE(path, constant)` ASC or `INNER_PRODUCT` DESC on a vector of the queried class is pushed down; objects without a vector are placed after (NULLS LAST) or before (NULLS FIRST) the closest, as the in-memory sort would.
- `explain()` shows per segment: `HNSW, ef=…`, `exact scan of the vector column` or `exact scan of the rows`.

### 10.7 Full-text search

```mermaid
flowchart LR
    T["text"] --> A["Analyzer<br/>(english: words · possessive · stop words · Porter)"] --> W["words + frequencies"]
    W --> I["per segment: word dictionary<br/>Roaring posting per word · frequencies · lengths"]
    Q["query 'baking bread*'"] --> A2["same analyzer<br/>(prefix: lower-cased only)"] --> M["matches: AND / OR of postings"]
    M --> S["search: BM25 over collection statistics"]
    S --> H["hybrid: RRF with nearest()"]
```

| | Kookaburra | SQLite FTS5 | Lucene / Elasticsearch | PostgreSQL |
|---|---|---|---|---|
| Declare | `@FullText(analyzer = "english")` on a `String` | `CREATE VIRTUAL TABLE … USING fts5` | `text` field + analyzer | `tsvector` + GIN |
| Analysis | `english` (default): Unicode words, CJK bigrams, possessive, stop words, Porter stem; `standard`: Unicode words + CJK bigrams; own ones via `Analyzers.register` | `unicode61`, `porter` | `standard`, `english` (same chain), `cjk` … | text search configs |
| Filter | `where(f).matches("a b")` (all words), `matchesAny`, prefix `bre*`; OQL `MATCH(x.f, '…')` | `MATCH 'a b'`, `bre*` | `match` (OR / AND), `prefix` | `@@ to_tsquery` |
| Relevance | `search(f, "…", k)` BM25 (k1 1.2, b 0.75); OQL `ORDER BY SCORE(x.f, '…') DESC` | `bm25()` | BM25 | `ts_rank` (no IDF) |
| Hybrid | `nearest(v, "text", k).hybrid(f)`: RRF, constant 60 | none | RRF retriever | app side |

- **Storage** ([storage-design.md §4.6](storage-design.md#46-full-text-indexes)): an inverted index per segment on the same blocks as the columns: sorted word dictionary, one Roaring posting per word, frequencies in posting order, word count per document; the in-memory segment keeps postings and per-row frequencies. Segment format 3.
- **Analyzer** named in the schema (the string field's `TypeDef.name`), so indexing and queries always use the same one; an unregistered name fails with a hint to register it.
- **Semantics**: `matches` = every word (as FTS5), `matchesAny` / `search` = any word (as a `match` query); a field without an index gives the same answers by splitting its rows (differential test). Statistics include deleted documents until a merge (as Lucene).
- **Rerank** works after `search(...)` as after `nearest(...)`; `SCORE()` in a select list needs the same `ORDER BY SCORE(…) DESC` (the score comes from the search).
- **Phrases** `"fresh bread"`: words next to each other, in order; positions come from the analyzer (`Analyzer.tokens`: word, position, offsets), removed stop words keep their position (Lucene's gaps). The index finds the documents with every word of the phrase; positions are checked on the candidates' text (no positions stored in segments).
- **Highlighting**: `TextQuery.highlight(text)` / OQL `HIGHLIGHT(x.f, 'query')` marks matched words and phrases `<em>…</em>` (Elasticsearch's marks, PostgreSQL's `ts_headline`); long texts give up to three fragments of about 100 characters joined by ` … `; analyzers without offsets give the text unmarked.

### 10.8 Conditions on one element of an embedded list

| | API / OQL | Meaning |
|---|---|---|
| any element (existing) | `whereAny(Order::getLines, Line::getSku).contains("A")`, `'A' MEMBER OF o.lines.sku` | each condition may be met by a different element |
| one element | `whereElement(Order::getLines, e -> e.where(Line::getSku).eq("A").where(Line::getQty).gt(5))` | the same element meets every condition (MongoDB `$elemMatch`) |
| one element, OQL | `SELECT o FROM Order o JOIN o.lines l WHERE l.sku = 'A' AND l.qty > 5` | JPQL join over the list: some binding of `l` makes WHERE true; each object once (EXISTS semantics); `LEFT JOIN` binds null to an empty list; several element joins bind independently |

- Execution: equalities / IN on an element path are pushed down as "some element has the value" (index of the multi-valued path, a superset); conjuncts without the element alias run as usual; the exact check then evaluates the conditions per element (`Pred.ElemMatch`, OQL correlated residual). Conditions with NOT / OR on the alias are only checked per element (pushing them down could drop matches).
- The element alias is only allowed in WHERE (no per-element rows).

---

## 11. Semantics Contract

Index execution **must** equal `Pred.test` / `Order.compare` on objects. Enforced by differential tests.

| Topic | Rule |
|---|---|
| Default order | **Unordered** unless `sorted(...)`; `limit` without `sorted` = any n rows |
| Null in comparisons | `eq / gt / range / startsWith` on null → `false`; `isNull` explicit |
| Logic | Two-valued Java boolean: `not(age > 30)` matches `age == null` |
| Null in sort | `nullsLast` for `asc()`, `nullsFirst` for `desc()`; overridable |
| String order | `String.compareTo` (UTF-16 code units); dict sorted the same, **not** UTF-8 byte order |
| Double order / equality | `Double.compare`: `-0.0 < 0.0`, `NaN` greatest, `NaN eq NaN` true |
| Decimal order / equality | numeric (`BigDecimal.compareTo`): `1.50 = 1.5`; read back as `1.5`. OQL: an expression with a DECIMAL operand is exact (`0.1 + 0.2 = 0.3`), division to 34 digits; `SUM` / `AVG` of decimals are decimals (typed `avg()` returns double, like JPA) |
| Enum order | declaration order (`Enum.compareTo`) |
| Time zone | **UTC everywhere**: `LocalDateTime` is stored as UTC wall time, `Instant` is UTC; OQL `TIMESTAMP` / `INSTANT` literals are UTC; Studio displays UTC (labelled); the engine never uses `ZoneId.systemDefault()`. `ZonedDateTime` / `OffsetDateTime` / `java.util.Date` are not first-class types (store an `Instant`) |
| Multi-valued `contains` | any element matches |
| Path through an embedded list (`items.sku`) | multi-valued, "any element" (`contains`, `MEMBER OF`, `whereAny`); not indexed → scan |
| Embedded collection paths | element correlation **not** supported (two conditions may match different elements) |
| Polymorphism | `from(A)` includes all subclasses; subclass-only fields are null for others |

---

## 12. Concurrency

| Component | Thread safety |
|---|---|
| `ObjectDB` | thread-safe, one per directory per process |
| Writes | encoding on caller threads; one sequencer thread validates / logs / applies; WAL group commit |
| Reads | lock-free on `Version` + `visibleSeq` |
| Isolation | snapshot reads; single op / `WriteBatch` atomically visible; transactions: READ COMMITTED, SNAPSHOT, SERIALIZABLE (§12.1) |
| Conflicts | version mismatch → `OptimisticLockException` |
| `Snapshot`, `Cursor`, `WriteBatch`, `QueryStream` | not thread-safe |
| Back-pressure | too many segments / frozen memtables → writers throttled |

### 12.1 Transactions

Full design: **[transaction-design.md](transaction-design.md)** (implemented 2026-10-15).

| Aspect | Design |
|---|---|
| Switch | `kookaburra.transactions` (default `false` = autocommit only, no overhead) |
| Isolation | `READ_COMMITTED` (fresh snapshot per statement), `SNAPSHOT` (default, snapshot at `begin()`), `SERIALIZABLE` (snapshot + read validation at commit); default from `kookaburra.tx-isolation` |
| Concurrency control | optimistic: write set buffered; at commit the sequencer checks a conflict table (OID → last seq): first committer wins; stale `@Version` entities rejected at the call |
| Read-your-writes | private overlay `MemSegment` + masked snapshot views → every query / OQL / dynamic read sees the transaction's writes |
| Atomicity | one WAL record per commit; a failing call has no effect (statement-level) |
| Hot spots | `increment(Class, oid, field, delta)`: applied on the latest value at commit, never conflicts |
| SERIALIZABLE | read set = OIDs read by lookup + every query condition (`ReadView.queryViews`); at commit the before / after versions of commits since the snapshot (`ChangeLog`, kept only while SERIALIZABLE transactions are open) are tested against it; a match aborts. Read-only transactions never fail |
| API | `db.begin(Isolation.X)`, `tx.get / query / oql / dynamic / referrers / insert / update / delete / increment / commit / rollback`, `db.inTransaction([options,] work)`, `db.inTransactionResult([options,] fn)` |

![Isolation levels](assets/isolation.png)

---|---|
| Atomic multi-object, multi-collection write | `WriteBatch`: one WAL record, all or nothing |
| Atomic visibility | global `visibleSeq`: a batch is seen entirely or not at all |
| Repeatable reads across queries | `Snapshot` (MVCC) |
| Lost-update protection | `@Version` optimistic lock → `OptimisticLockException` |
| Durability | WAL + fsync per commit group (`SYNC`) |
| Interactive transactions | `db.begin(...)` with three isolation levels (§12.1, [transaction-design.md](transaction-design.md)) |
| **Not provided** | savepoints / nested transactions (T13), cross-process access |

---

## 13. Compaction

Moved to [storage-design.md §10](storage-design.md#10-compaction).

- Size tiers (`compaction-fan-in`), garbage-driven rewrites (`compaction-garbage-ratio`), `compact()`, back-pressure.

---

## 14. File I/O

Moved to [storage-design.md §11](storage-design.md#11-file-io).

- `MappedFile` over memory-mapped files (heap in memory mode), little-endian.

## 15. Recovery

Moved to [storage-design.md §12](storage-design.md#12-recovery).

- Commit file, orphan cleanup, WAL replay from per-collection replay points, torn tail truncated.

### 15.1 Logging

| Module | Logging |
|---|---|
| `kookaburra-core` | **SLF4J API only** (2.0.x); the application picks the backend. No logging on the per-write / per-row paths |
| Studio | logback 1.5 → `kookaburra.log-dir` / `studio.log` (daily + 10 MB rolling, 14 days, 200 MB cap) + console; level `kookaburra.log-level` |
| Bench | slf4j-simple at WARN |

| Logger | Level | Event |
|---|---|---|
| `Database` | INFO | open (collections, segments, WAL records replayed, ms), close, compaction (inputs, rows, bytes, ms) |
| `Database` | DEBUG | flush (rows, bytes, ms) |
| `Database` | WARN | write back-pressure longer than 1 s |
| `Database` / `Sequencer` | ERROR | flush / compaction / sequencer failure (writes stop) |
| `WalFile` | WARN | torn / corrupt WAL tail truncated |


### 15.2 Online backup

Moved to [storage-design.md §13](storage-design.md#13-online-backup).

- `db.backup(target)`: one snapshot exported into fresh segments, writers not blocked, returns `BackupInfo`.

### 15.3 Statistics (`db.stats()`)

```mermaid
flowchart LR
    W["write / tx / flush / merge paths"] -- "LongAdder ++" --> M["engine.Metrics"]
    V["Version + visibleSeq<br/>(one snapshot)"] --> S
    M --> S["db.stats() → DatabaseStats"]
    S --> A["application: Micrometer, Dropwizard, JMX, logs"]
    S --> ST["Studio: Stats tab"]
```

| Group | Content |
|---|---|
| `collections` | per collection: live objects (snapshot count), segments, segment bytes, deleted rows awaiting compaction, unflushed rows / bytes |
| `writes` | batches committed / rejected, inserts / updates / deletes (cascades and SET_NULL included), group commits (`batchesPerGroup`), WAL records replayed at open, back-pressure waits / ms |
| `transactions` | active, retries of `inTransaction`, per isolation level: committed / rolled back / conflicts / timeouts |
| `constraints` | unique and foreign-key violations, cascade deletes, SET_NULL updates |
| `maintenance` | flushes (rows, bytes, ms), compactions (input segments, bytes, ms), pending, backups |

- Pull model, no dependency (D56): the application polls and exports; core never depends on a metrics library.
- Counters are cumulative since open (not persisted); object counts and sizes come from one snapshot.
- Cost: one `LongAdder` increment per event on the write paths; `stats()` counts live objects per collection (bitmap cardinalities) and reads segment metadata.

---

## 16. Configuration

One file, **`kookaburra.properties`** (repo root; copied next to the Studio jar by the `dist` build). Every tunable is defined there and nowhere else; defaults are the recommended settings.

```mermaid
flowchart LR
    B["bundled defaults<br/>(kookaburra.properties in kookaburra-core.jar)"] --> F["kookaburra.properties<br/>(file next to the app / given path)"] --> S["-Dkookaburra.* system properties"] --> A["Studio CLI args (--data=)"]
```
Later sources override earlier ones (`Settings.load`, `Config.load` / `Config.fromSettings`; `Config.withX(...)` in code).

| Key | Default | Used by | Meaning |
|---|---|---|---|
| `kookaburra.mem-segment-bytes` | `64MB` | core | MemSegment size before freeze → flush |
| `kookaburra.segment-max-bytes` | `512MB` | core | compaction output limit (capped at 1.5 GB) |
| `kookaburra.compaction-fan-in` | `8` | core | segments merged per tier |
| `kookaburra.compaction-garbage-ratio` | `0.3` | core | share of deleted rows at which a segment is rewritten on its own (§13) |
| `kookaburra.max-segments` | `64` | core | per collection; above it writers are throttled |
| `kookaburra.write-backlog` | `10` | core | frozen MemSegments waiting for flush before back-pressure |
| `kookaburra.flush-threads` | `2` | core | threads for flushes and compactions |
| `kookaburra.transactions` | `false` | core | enables multi-statement transactions (off = autocommit, no conflict history) |
| `kookaburra.tx-timeout` | `60s` | core | open transactions older than this are rolled back |
| `kookaburra.tx-max-bytes` | `100MB` | core | encoded bytes buffered by one transaction |
| `kookaburra.tx-retries` | `3` | core | retries of `inTransaction` on `ConflictException` |
| `kookaburra.tx-isolation` | `SNAPSHOT` | core | isolation of `begin()` / `inTransaction` without options: `READ_COMMITTED`, `SNAPSHOT`, `SERIALIZABLE` |
| `kookaburra.tx-conflict-table-max` | `2000000` | core | conflict-table (and SERIALIZABLE change-log) entries before the oldest open transaction is evicted |
| `kookaburra.bloom-fpp` | `0.01` | core | OID bloom filter false-positive rate per segment |
| `kookaburra.auto-postings-ratio` | `16` | core | AUTO index writes postings when rows / distinct ≥ ratio |
| `kookaburra.postings-or-max` | `64` | core | range / IN over at most this many postings ORs bitmaps, else scans ordinals |
| `kookaburra.durability` | `SYNC` | core | `SYNC` = fsync per commit group, `ASYNC` = OS page cache |
| `kookaburra.group-commit-micros` | `200` | core | sequencer waits this long to batch more commits (SYNC only) |
| `kookaburra.sequencer-max-group` | `512` | core | max write requests per group commit |
| `kookaburra.vector-m` | `16` | core | HNSW links per node on the upper layers (bottom layer: twice as many) |
| `kookaburra.vector-ef-construction` | `100` | core | HNSW candidate list while inserting |
| `kookaburra.vector-ef-search` | `100` | core | HNSW candidates per segment of a search (at least k); `numCandidates(n)` per query |
| `kookaburra.vector-index-min-rows` | `2000` | core | segments with fewer vectors get no graph (exact scan) |
| `kookaburra.embedder` | `ollama` | core | text embedder: `ollama` or `none` (contacted only when used) |
| `kookaburra.embedder-url` | `http://localhost:11434` | core | Ollama address |
| `kookaburra.embedder-model` | `nomic-embed-text` | core | embedding model (its dimension must match the `@Vector` field) |
| `kookaburra.embedder-timeout` | `60s` | core | longest wait for one embedding request |
| `kookaburra.reranker` | `ollama` | core | reranker for `rerank(...)`: `ollama` or `none` (contacted only when used) |
| `kookaburra.reranker-url` / `-model` / `-timeout` | `http://localhost:11434` / `qwen3:4b` / `120s` | core | Ollama address, instruct or reranker model, longest wait |
| `kookaburra.data` | _(empty → `<app dir>/data`)_ | Studio | default database directory |
| `kookaburra.theme` | `NEON` | Studio | initial theme (user choice is remembered) |
| `kookaburra.page-size` | `50` | Studio | Data grid rows per page |
| `kookaburra.console-page-size` | `100` | Studio | OQL console rows per page |
| `kookaburra.demo-customers` | `250` | Studio | size of the `--demo` database |
| `kookaburra.log-dir` | _(empty → `<app dir>/logs`)_ | Studio | log directory (relative paths: against the jar directory) |
| `kookaburra.log-level` | `INFO` | Studio | ERROR / WARN / INFO / DEBUG / TRACE |

Sizes accept `KB` / `MB` / `GB`.

### 16.1 Performance plan

| Principle | Where |
|---|---|
| Push work into bitmaps: filters, type filters, deletes, counts never touch rows | §10.2, `Executor` |
| Never decode rows for indexed predicates; MemSegments hold decoded `Record`s | §6.2 |
| Cheapest conjunct first; each next conjunct only scans the surviving candidates | `Executor.ordered` |
| Top-N with a bounded heap, early stop for unordered `limit` | §10.3 |
| Encoding on caller threads; one fsync per group of batches | §6.1 |
| Lock-free reads: immutable `Version`, append-only chunked arrays, volatile publication | §6.2, §7 |
| Zero-copy on-disk structures: `ImmutableRoaringBitmap` over mapped slices, O(1) bit-packed ordinals | §5 |

**Harness**: `kookaburra-bench` (D31): `java -Xmx8g -jar kookaburra-bench/target/kookaburra-bench.jar --n=10000000 --threads=16 --seconds=20`; warm-up + repeated runs (p50 / p99); consistency checked after every phase (count ≡ scan ≡ OQL); test data deleted afterwards. Full report: [`benchmark-10m.md`](benchmark-10m.md).

**Environment**: Apple M2 Max (12 cores), 32 GB, internal SSD (APFS, `force()` = `F_FULLFSYNC` ≈ 4 ms), macOS, JDK 17.0.12, `-Xmx8g`; 10 M objects (`Member`: indexed `name` (unique), `city` (50 values), `age` ORDINAL, `tags` list; plain `score`, `balance`, `active`, `note`), 18 segments after load, 4 after compaction.

| Benchmark (10 M objects, run of 2026-10-17) | Target | Before tuning | **Result** |
|---|---|---|---|
| bulk insert, batches of 100, `ASYNC`, 1 thread | ≥ 300 k/s | 468 k/s | **696 k/s** ✅ |
| single insert, `SYNC`, 16 / 128 threads | ≈ threads ÷ fsync latency | 3.1 k/s | **3.2 k/s / 21.2 k/s** ✅ (fsync 4 ms) |
| `get(oid)` MemSegment / segment (p50) | ≤ 2 µs / ≤ 10 µs | 0.75 / 0.92 µs | **0.58 / 1.08 µs** ✅ |
| `count()` with one BITMAP `eq` | ≤ 5 ms | 0.23 ms | **0.24 ms** ✅ |
| `eq` + `range` + `limit(100)` | ≤ 20 ms | 0.18 ms | **0.21 ms** ✅ |
| top-10 by an ORDINAL column, 2.3 M matches | ≤ 50 ms / 1 M | 187 ms | **0.39 ms** ✅ |
| `groupBy(city).agg(count)` | ≤ 100 ms | 902 ms | **4.0 ms** ✅ |
| point lookup on a unique indexed string (OQL) | n/a | 13 ms | **0.12 ms** |
| OQL `GROUP BY city, COUNT, AVG(age) WHERE age > 40` | n/a | 756 ms | **244 ms** |
| unindexed predicate, full scan of 10 M rows | n/a | did not finish (full row decode) | **0.81 s** |
| `compact()` 10 M objects (1.4 GB) | n/a | 17.7 s | **10.4 s** |
| transactions: transfers (read 2, update 2), 16 threads: SNAPSHOT / READ COMMITTED / SERIALIZABLE | ≥ 50 k/s | n/a | **136 / 142 / 140 k/s** ✅ (0.023 retries / commit) |
| `increment` of one hot counter, 16 threads | no lost updates | n/a | **265 k/s** ✅ |
| insert with `@Unique` vs without, batches of 100, in memory | ≥ 50 % of plain | n/a | **235 vs 258 k/s** ✅ |
| insert with a checked `@ForeignKey` / delete with ON DELETE CASCADE, batches of 100 | n/a | n/a | **225 k/s / 181 k/s** |
| online backup of 10.5 M objects (1.4 GB) | n/a | n/a | **25.4 s** (413 k objects/s), writers not blocked |
| OQL `DELETE` of all 10.5 M objects, then `compact()` | space reclaimed | 1.5 GB left (4 segments) | **1.32 M/s, 0 B left** ✅ (D44) |
| reopen (no WAL to replay) / heap after reopen | n/a | n/a | **2.0 ms / 6.5 MiB** (mmap) |
| disk footprint | n/a | 136 B / object | **148 B / object** (+ sdoc lists) |

**Problems found and fixed** (M11):

| Symptom | Cause | Fix |
|---|---|---|
| unindexed filter on 10 M rows never finished | every row fully decoded (all strings allocated) to read one field | path-only decoding (§5.4) |
| `GROUP BY` 900 ms | per row: map lookup, `new String` key, `Arrays.asList`, hashing | ordinal-indexed states; `COUNT(*)` from postings (§10.4) |
| top-N 187 ms | key read + 2 allocations + heap push / pop per match | index-order walk with early stop; allocation-free rejects (§10.3) |
| unique-key lookup 13 ms | AUTO index without postings scanned 10 M ordinals | value-sorted doc lists (D32) |
| flush / merge memory | a RoaringBitmap per distinct value built even when postings were not written | build postings only when written |
| deleting all 10 M objects left 1.5 GB on disk | size-tiered merging never picked lone / oversized segments | garbage-driven compaction, `flush()` / `compact()` wait for running work (D44) |
| 200 k inserts with `@Unique` took minutes (quadratic) | each check ran the generic executor, which builds the live bitmap of the whole memtable | index point lookup confirmed with `find(oid, seq)` (§3.6) |

**Known limits** (documented, not bugs): durable single writes are bound by fsync latency × concurrency; sorting / filtering on unindexed fields reads rows (≈ 80 ns / row); a flush / merge buffers the whole segment in heap (streaming writer: backlog); mixed read p99 (~150 ms) is dominated by sorts on the unindexed `score`.

---

## 17. Kookaburra Studio (standalone UI)

Independent desktop application: module / jar **`kookaburra-face`** (own launcher); opens any database directory through the dynamic API: no entity classes needed.

| Concern | Choice |
|---|---|
| Runtime | JDK 17, JavaFX 21 LTS |
| Theme | AtlantaFX 2.0.1 (last line on JDK 17; 2.1 needs 21, 3.x needs 25): default **Kookaburra Neon** (Dracula + `neon.css`); switchable to Dracula, Nord, Primer, Cupertino (dark / light), saved in user preferences |
| Icons | Ikonli 12.4.0 (Material Design 2 pack) |
| Editors | RichTextFX 0.11.7 `CodeArea`: OQL console, JSON object editor, explain / log viewer |
| Packaging | separate Maven project; dev run `mvn -f kookaburra-face/pom.xml javafx:run`; **distribution** `mvn -f kookaburra-face/pom.xml -P dist package` → `bin/kookaburra-face.jar` (fat jar, JavaFX natives of the build OS) + `studio.sh` / `studio.cmd` + `kookaburra.properties` |
| Data directory | `--data=<dir>` > `kookaburra.data` (relative to the jar) > `<dir of the jar>/data`; `--demo` = in-memory sample database; both may be combined (two databases in the navigator) |
| Input safety | editor values are type-checked before the dialog closes (field-qualified message, input kept); the Data filter is a condition only (wrapped in parentheses: no `LIMIT` / `ORDER BY`); `EXPLAIN UPDATE / DELETE` only shows the plan; opening a non-empty directory that is not a database asks first; errors name the directory and the reason |
| Platforms | one jar for **Windows x64, Linux x64, macOS Apple Silicon** (JavaFX natives of all three; `.dll` / `.so` / `.dylib` do not collide); Intel Mac / Linux ARM: `-Djavafx.mac=mac` / `-Djavafx.linux=linux-aarch64`. Needs a Java 17+ runtime; starts with a double-click (`Launcher`, `StudioApplication`). Verified: macOS aarch64 (double-click), Linux x86_64 (Docker + Xvfb) |
| Logging | logback, see §15.1 |
| Start-up | splash (logo, Studio + core version, loading bar) for ≥ 1.2 s while the main window is built, then fades out |
| UI tests | TestFX 4.0.18 + Monocle 17.0.10, headless (software rendering); `StudioNestedTest` / `StudioMultiDbTest` run in memory and on disk; `TutorialRecorder` (not in the regular run; `-P tutorial`) records Studio clicks and code slides and renders the tutorial video with `TutorialVideo` (mouse cursor, cross-fades, subtitles, music, poster); screenshots → `target/screenshots`, published copies in `docs/assets/studio-*.png`; boundary suite `StudioEdgeCaseTest`; `-P visible` replays it on the real display with pauses |

### 17.1 Layout

```
┌───────────────────────────────────────────────────────────────────────────────┐
│ ☰ Open DB   ⟳ Refresh   ＋ New   ✎ Edit   🗑 Delete   ▶ Run (Ctrl+Enter)        │  toolbar
├──────────────────┬────────────────────────────────────────────────────────────┤
│ Collections      │ [ Data ] [ Query ] [ Schema ]                              │
│ ▾ Person   12,034│ ┌────────────────────────────────────────────────────────┐ │
│ ▾ Animal    3,210│ │ filter: city = 'Sydney' AND age > 30      [Apply] [⟳]  │ │
│    ├ Dog    1,800│ ├──┬──────┬───────┬────────┬─────┬───────────────────────┤ │
│    └ Cat    1,410│ │ #│ oid  │ $type │ name   │ age │ home.city …           │ │  TableView
│ ▸ Company     512│ │ …    │       │        │     │  (sortable headers →     │ │  (virtualized,
│                  │ │      │       │        │     │   ORDER BY push-down)    │ │   paged)
│                  │ ├──────┴───────┴────────┴─────┴──────────────────────────┤ │
│                  │ │ ◀ 1 2 3 … ▶   50 / page          12,034 rows · 4 ms    │ │
│                  │ └────────────────────────────────────────────────────────┘ │
├──────────────────┴────────────────────────────────────────────────────────────┤
│ Object editor (drawer):  [ Form ] [ JSON ]   Save · Cancel · version 7        │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 17.2 Visual style: dark / neon

`studio.css` holds layout and derives its `-k-*` tokens from the active AtlantaFX theme; `neon.css` (default theme) sets the palette below.

| Token | Value | Use |
|---|---|---|
| `-k-bg-0` | `#0B0E14` | window background |
| `-k-bg-1` | `#11151F` | panels, sidebars |
| `-k-bg-2` | `#1A2030` | cards, table header, editors |
| `-k-border` | `#26304A` | 1 px separators |
| `-k-fg` | `#E6EDF7` | primary text |
| `-k-fg-muted` | `#7A8BA8` | secondary text, gutters |
| `-k-accent` | `#00E5FF` (neon cyan) | focus ring, selection, primary buttons, active tab underline |
| `-k-accent-2` | `#FF2BD6` (neon magenta) | OQL keywords, highlights, badges |
| `-k-ok` / `-k-warn` / `-k-err` | `#39FF88` / `#FFC14D` / `#FF4D6D` | status, toasts, conflict dialogs |

| Element | Treatment |
|---|---|
| Focus / hover | `dropshadow(gaussian, -k-accent, 12, 0.35, 0, 0)` glow; 120 ms fade |
| Tabs, buttons | flat, 6 px radius, accent underline / outline; icons Ikonli MDI2 in `-k-fg-muted`, accent on hover |
| Table | zebra `-k-bg-1` / `-k-bg-2`; selected row = accent at 18 % alpha + left 2 px accent bar |
| Code (OQL / JSON) | JetBrains Mono (bundled, OFL) 13 px; keywords magenta, strings `#39FF88`, numbers `#FFC14D`, paths cyan, comments muted italic; current line `#1A2030` |
| Status bar | row count · elapsed ms · snapshot seq, monospace, accent dot pulses while a query runs |
| Splash / empty states | logo + subtle cyan→magenta gradient line |

### 17.3 Features

| Tab / area | Feature |
|---|---|
| Toolbar | open / demo / close / refresh; **Flush** writes the current database's memtables to segment files now (an open transaction is not included: its changes are private until commit); **Backup** (online backup of the current database into a chosen folder: used as is when empty, else a time-stamped `<name>-backup-yyyyMMdd-HHmmss` sub-folder; status shows objects, segments, snapshot seq); new / edit / delete; run; transaction buttons; themes; About |
| Navigator (sidebar) | **several databases at once**: tree database → classes (hierarchy, live object counts); close button per database; a directory already open is focused, not reopened; same-named databases get unique labels (`demo`, `demo (2)`); selecting a class opens it in Data and Schema |
| Transactions | toolbar **Begin / Commit / Rollback** per database (no cross-database transactions); Begin is a split button whose menu picks READ COMMITTED / SNAPSHOT / SERIALIZABLE (default `kookaburra.tx-isolation`); create / edit / delete / console DML go into the open transaction; status `TX #id · SERIALIZABLE · N pending changes`; `ConflictException` → dialog; Studio opens databases with `kookaburra.transactions=true`, timeout 30 min; closing a database or the window with pending changes asks first; the console's `BEGIN` / `COMMIT` / `ROLLBACK` drive the same transaction (D48) |
| About | product, version (filtered from the POM), GitHub link, author, e-mail, license, core / JavaFX / Java versions |
| Data | paged grid with `#` row numbers; embedded objects flattened into dotted columns, lists of embedded objects / maps summarised as `[ 2 elements ]` / `{ 3 entries }`, long texts cut at 200 characters, binaries as `PNG · 1.1 KiB` (full value in the tooltip); **inspector** on the right: Key / Value / Type tree of the selected object (embedded objects, list elements `[i]`, map keys; lists ≤ 10 elements expanded), double-click a reference = open its target (dangling references reported); **← referenced by** node: backlinks looked up when expanded (`referrers`), double-click opens one;  column header click = server-side sort; quick filter box = OQL `WHERE` fragment; reload button keeps filter / sort / page; **export** of all matches (filter + sort, every page) as JSON / JSONL (one object per line, recommended for large exports) / CSV (RFC 4180, refs as OIDs), fetched in chunks of 5 000; double-click a row = edit; **row context menu**: Open, **Find similar by &lt;vector field&gt;** (one item per vector field, disabled when the object has none): the console runs `SELECT x, cosine_distance(x.v, :vector) AS distance FROM <declaring class> x WHERE x.oid <> :self ORDER BY distance LIMIT 10` with `:vector` / `:self` bound (the field's own distance; L2 / inner product accordingly) |
| Object editor: Form | widgets per `ValueType` (text, checkbox, date picker, enum combo, OID for references); embedded objects = titled nested sections (any depth, dotted paths); **lists of embedded objects and maps = element tables** (one row per element / entry, map key column, nested fields as dotted columns, add / remove / move up / down; duplicate or empty keys and bad values are reported); **binaries**: format + size, image preview (PNG / JPEG / GIF / BMP), Load… / Save… / Clear; **long or multi-line texts** in a text area (⤢ expands short ones); values nested deeper than an element table (a list inside a list element) are kept and edited in the JSON tab; **vectors**: `[0.1, 0.2, …]` text checked against `@Vector(dim)`, **From text…** asks for a text and fills the field with its embedding (configured embedder, off the FX thread; disabled with a hint when none is configured; a model of another dimension is reported) |
| Object editor: JSON | RichTextFX JSON with highlighting + validation against schema; binaries as Base64, those over 64 KiB as a placeholder that keeps the stored bytes (megabytes of Base64 would stall the editor) |
| CRUD | create / update (optimistic lock: conflict dialog with reload / overwrite) / delete (multi-select = one batch); constraint violations (`@Unique`, `ON DELETE RESTRICT`, missing FK target) → "Constraint violated" dialog, nothing stored; cascades show in the refreshed counts; decimals edited as exact text (`BigDecimal`) |
| Query | OQL console: highlighting from `OqlLexer` tokens, Ctrl+Space completion (classes after FROM / JOIN / UPDATE, `alias.` paths, keywords and functions incl. `EMBED`), Ctrl / ⌘ + Enter runs; results paged (100 / 500 / 1000; statements with their own LIMIT / OFFSET are not re-paged) with `#` row numbers; whole-object results (`SELECT x`) expand to one column per property; **Format** button: keywords upper-case, one clause per line, `AND` / `OR` indented (not the `AND` of `BETWEEN`), long select lists one item per line, sub-queries indented, statements separated by a blank line; works on the OQL lexer's tokens (strings, quoted names and comments kept; text with unknown characters left alone), one undo step (`OqlFormat`, as DBeaver's / DataGrip's Format SQL); **Plan** tab: Explain (plan only) and **Analyze** (`EXPLAIN ANALYZE`: runs the SELECT) draw the plan as a diagram, one row per step in tree order with an icon and a colour by access (index green, full-text magenta, vector cyan, row scan orange, processing grey), bars for the rows each step produced (`in → out`) and for its share of the time, the slowest working step outlined and marked; a Text toggle shows the same tree as text; `EXPLAIN [ANALYZE] …` typed in the console shows the same; UPDATE / DELETE plans as text; history; **scripts**: statements separated by `;` (outside strings and comments) run in order, stop at the first error (`statement 2 of 3: …`), the last one's result is shown and paged; **transaction commands** `BEGIN [TRANSACTION \| WORK] [ISOLATION LEVEL READ COMMITTED \| REPEATABLE READ \| SNAPSHOT \| SERIALIZABLE]` (REPEATABLE READ = SNAPSHOT, as in PostgreSQL), `START TRANSACTION`, `COMMIT`, `ROLLBACK` (highlighted and completed as keywords); **export** of the statement's whole result (JSON / JSONL / CSV, read in chunks of 5 000) |
| Stats (dashboard) | `db.stats()` of the current database: key figures (objects, storage, commits / s, open transactions, conflicts and their share, pending flushes / merges); line charts of writes / s and transactions / s from samples every 2 s while the tab is shown (5 minutes kept per database, off the FX thread, timer stopped on other tabs); bar charts of objects and storage per collection; the full tables (collections, counters since open) below; rates = difference of two samples of the same opening |
| Schema | **left**: metadata of the selected class: full name, class id, parent, collection, subclasses, `@Id` / `@Version`, layouts, count, **comment**; field table (path, type, index, declared in, **constraint**: `UNIQUE`, `FK → Customer · CASCADE`, **comment**; scrolls as one panel, tooltips for clipped text). **right**: segments of every collection (memory / disk, documents, live, garbage %, size) + layout history |
| Data grid comments | a header of a field with `@Comment` carries an info mark whose tooltip is the comment |
| Export as Java | toolbar / File menu: the `SchemaExport` of the current database; file list, highlighted editable code (edited files marked, Reset file, Copy), package field (Generate; edits discarded after confirmation; invalid names refused), Save… into a source folder (asks before replacing files whose content differs) |
| Menu bar | File, Edit, Query, Transaction, View, Help (the macOS system menu bar there); every item fires its toolbar / tab button and is enabled exactly when that button is; shortcuts: Shortcut+O / N / E / S / Q / 1 to 4, F5; formatting has none |
| About | product, versions, links and a **feature map** (8 areas: objects, query, indexes, full-text, vectors, transactions, operations, Studio) |
| Text colours | dark themes: all text pure white; light themes: pure black (`text-dark.css` / `text-light.css` after the theme); only input placeholders stay grey; dialogs take the main window's stylesheets |

### 17.4 Threading

```mermaid
sequenceDiagram
    participant UI as FX Application Thread
    participant BG as Task executor (fixed pool)
    participant DB as ObjectDB (dynamic API)
    UI->>BG: Task<Page> (query / save / delete)
    BG->>DB: run with snapshot
    DB-->>BG: rows / OptimisticLockException
    BG-->>UI: Platform.runLater → update grid / show dialog
```
DB calls never run on the FX thread; long queries are cancellable.

### 17.5 Distribution (GitHub Actions)

```mermaid
flowchart LR
    T["git tag v1.2.3 + push"] --> A["test: core + processor + Studio<br/>(ubuntu, headless UI)"]
    A --> W["windows-2022<br/>msi (releases) + zip"]
    A --> L["ubuntu-24.04<br/>deb + tar.gz + jar"]
    A --> M1["macos-15<br/>dmg (arm64)"]
    A --> M2["macos-15-intel<br/>dmg (x64)"]
    W --> R["GitHub Release v1.2.3<br/>marked latest, SHA256SUMS"]
    L --> R
    M1 --> R
    M2 --> R
```

- `.github/workflows/studio-release.yml` runs on tags `v*`; `kookaburra-face/src/packaging/package.sh <version> <label>` packages on each OS with `jpackage` (JDK 21 runtime trimmed with jlink, about 45 MB per download). Icons in `src/packaging/` (`.ico`, `.icns`, `.png`).
- **Always the latest**: file names carry no version (`kookaburra-studio-windows-x64.msi`, …), so `releases/latest/download/<file>` serves the newest release; README and website link there. Windows upgrades in place (fixed upgrade UUID).
- **Release vs snapshot** (an rc tag is what `-SNAPSHOT` is in the pom): `v1.2.0` is a release (every package, Windows msi + zip, marked latest); `v1.2.0-rc1` is a snapshot (Windows zip only, no msi; a pre-release that leaves latest alone, its notes link to its own files); a manual run is a snapshot as workflow artifacts only. Studio shows the tag as its version (`1.2.0-rc1`); a manual run keeps `-SNAPSHOT`.
- **Installed app paths**: the launchers set `jpackage.app-path`, so Studio keeps settings, the default database and logs in `~/Kookaburra` (the program folder is read-only); any directory opens with Open… or `--data=PATH`.
- **Maven** (job `maven`, after the tests): kookaburra-parent, kookaburra-core and kookaburra-processor; `v1.2.0` needs version `1.2.0` in the poms and goes to Maven Central, `v1.2.0-rcN` needs `1.2.0-SNAPSHOT` and goes to the Central snapshot repository; a mismatch fails the job. Release profile: sources, javadoc, GPG, central-publishing-maven-plugin (autoPublish in CI).
- Not signed yet (Apple notarization, Windows code signing need certificates as repository secrets): first start needs right-click Open (macOS) or "Run anyway" (Windows).

---

## 18. Project Layout & Roadmap

### 18.1 Modules

```
kookaburra/
├─ docs/                         GitHub Pages: index.html (landing page), design.html (renders design.md), design.md,
│                                 benchmark-10m.md, storage-design.md, transaction-design.md, developer-guide.md, blogger/ (zh, en, medium), assets/ (logo, screenshots, diagrams), .nojekyll
├─ README.md                     English overview: features, quick start, examples, configuration, performance
├─ kookaburra.properties         the single settings file (bundled into kookaburra-core as defaults)
├─ bin/                          Studio distribution (built, not versioned): kookaburra-face.jar, scripts, logs/, data/
├─ pom.xml                       (parent: core + processor only; JDK 17, RoaringBitmap 1.6.22, ANTLR 4.13.2;
│                                 groupId com.github.paganini2008, packages com.github.kookaburra; `release` profile → Maven Central)
├─ kookaburra-core/              com.github.kookaburra
│   ├─ annotation/               @Entity @Embeddable @Id @Version @Index @Transient IndexKind
│   ├─ field/                    Field hierarchy, Getter types, Fields (registry), Property, EmbeddedPath
│   ├─ query/                    Pred, Order, Q, QueryStream, Plan, Planner, Agg, Tuple*, explain
│   ├─ dynamic/                  DynamicObject, DynamicCollection, DynField
│   ├─ oql/                      AstBuilder (visitor), resolver, OQL → Plan; grammar in src/main/antlr4 (Oql.g4)
│   ├─ mapping/                  Schema, TypeRegistry, ValueType, Codec, KeyEncoding
│   ├─ exec/                     SegmentView, Executor, merge, group
│   ├─ engine/                   ObjectDB impl, Writer, Wal, MemSegment, DeleteLog, Version, Commit, Compactor
│   ├─ segment/                  SegmentWriter/Reader, Dict, PackedInts, Bloom
│   └─ io/                       MappedFile, ByteSink / ByteSource
├─ kookaburra-processor/         annotation processor → Person_
├─ kookaburra-bench/             load / stress harness (§16.1): separate Maven project, fat jar, smoke-tested (coverage ≥ 90 %), deletes its data after a run
└─ kookaburra-face/              Kookaburra Studio: standalone JavaFX app: **separate Maven project** (own pom, not a
                                  module of the parent), depends on the published kookaburra-core; built / released on its own
```

### 18.2 Milestones

| # | Milestone | Done when | Status |
|---|---|---|---|
| M0 | Maven skeleton, JaCoCo gate | `mvn verify` green, coverage ≥ 90 % | ✅ |
| M1 | Field model, `Pred` / `Order`, `where(Getter)`, annotation processor | unit tests: resolution, mixing, `test()` semantics, JDK-comparator equivalence | ✅ |
| M2 | Schema (self-describing, layouts), TypeRegistry (inheritance, class ids), Record + RecordCodec, SchemaIO | round-trip tests incl. subclasses, embedded, collections, evolution | ✅ |
| M3 | MemSegment + Executor + QueryStream (in-memory DB, non-durable) | **differential tests** vs `List.stream()` on random data/queries | ✅ |
| M4 | Segment writer/reader, MappedFile, flush memtable → segment | same differential suite on disk segments | ✅ |
| M5 | WAL, commit, recovery | crash/replay tests | ✅ |
| M6 | Updates/deletes, DeleteLog, Version/Snapshot MVCC, WriteBatch (cross-collection) | concurrency + snapshot tests | ✅ |
| M7 | Compaction, throttling | merge correctness + concurrent delete remap | ✅ |
| M8 | Refs (`matches` / `in` / `fetch`), `updateEach` / `deleteAll`, `cursor` / `stream` | integration tests | ✅ |
| M9 | Dynamic access + OQL (SQL-92 core: expressions, functions, CASE, LIKE, DISTINCT, GROUP BY / HAVING, aggregates incl. COUNT DISTINCT, ORDER BY alias/position) | OQL ≡ typed API on the differential suite; SQL-92 semantics tests | ✅ |
| M10 | Kookaburra Studio (starts after M6) | CRUD, query console, JSON editor, explain on a sample DB | ✅ |
| M11 | Performance: ordinal fast paths, tuning against §16.1 targets with the stress harness | targets met or gaps documented | ✅ |
| M12 | Transactions ([transaction-design.md](transaction-design.md)): SNAPSHOT + OCC, read-your-writes, increments; chained query API | anomaly / conflict / differential / concurrency tests, coverage ≥ 90 % | ✅ |

### 18.3 Backlog

Features to be built next (only what users of the library or Studio will get): none planned for 1.0.0; everything above is done.

---

## 19. Decision Log

| # | Decision | Why |
|---|---|---|
| D1 | Own query API, not `implements Stream` | Opaque lambdas can't be pushed down; full `Stream` contract adds little; interop via `.stream()` |
| D2 | Borrow Speedment's field/predicate design, no dependency | Speedment last release 2020 and SQL-bound; JPAstreamer is LGPL and JPA-bound |
| D3 | Fields via generated `Person_` **and** `where(Person::getX)`, mixable | Processor = type-safe paths; method refs = zero setup; both resolve via one registry |
| D4 | Single-table inheritance per root + `$type` bitmap | Polymorphic queries = one bitmap op; null bitmaps make sparse columns cheap |
| D5 | Unordered by default | No global OID merge needed; matches SQL |
| D6 | MemSegment keeps columns, not just rows | Avoid decoding the whole memtable on every query |
| D7 | Delete log + snapshot length instead of per-update `.del` | No file I/O per update; consistent snapshots |
| D8 | Global `visibleSeq` | Atomic visibility of cross-collection batches |
| D9 | BSI deferred | Scope v1; format/enum slot reserved |
| D10 | RoaringBitmap 1.6.22 | Latest release older than 2 weeks at project start |
| D11 | Canonical pipeline order: filter → sort → skip/limit → project, or filter → group → having → sort → skip/limit | SQL-like, unambiguous push-down; post-processing via `.stream()` |
| D12 | `@Embeddable` on the value class instead of `@Embedded` on the field | One declaration per type; processor and runtime classify identically |
| D13 | `BoolField` is comparable; `RefField` is not | Booleans sort naturally; refs have no meaningful order (avoids `where` overload ambiguity) |
| D14 | Self-describing schema + dynamic API (`DynamicObject`, `DynField`) | Studio must open any DB without entity classes; reuses Pred/Plan unchanged |
| D15 | OQL = JPQL-style object query language (select / update / delete), compiled to Plan | Text queries for apps and Studio; familiar to JPA users; one planner for all front-ends |
| D16 | Studio is a separate standalone app on JDK 17 (AtlantaFX 2.0.1) | Keeps core dependency-free; whole stack runs on JDK 17 |
| D17 | Studio is dark-only, neon accent (Dracula base + `studio.css`) | User-requested "dark & cool" look; one theme to polish |
| D18 | Packages `com.github.kookaburra`; Maven groupId `com.github.paganini2008` (verified Central namespace) | User decision |
| D19 | Single sequencer thread instead of writer-per-collection | Atomic cross-collection batches without 2PC; monotonic `visibleSeq`; encoding stays parallel on callers |
| D20 | Per-class layouts + canonical `Record` | Old rows readable after changes; one form for typed, dynamic, indexing and OQL |
| D21 | JaCoCo gate: line & instruction coverage ≥ 90 % per module | User requirement; `mvn verify` fails below |
| D22 | OQL parser generated by **ANTLR 4.13.2** (`Oql.g4`) + visitor → AST → Plan | User choice: mature, grammar is the spec, easy to extend; runtime has no transitive deps. Generated code excluded from the coverage gate |
| D23 | UTC as the only time zone | Deterministic results across machines; no hidden dependency on the host zone |
| D24 | Positioning: single-collection queries with SQL-92 core semantics; no multi-table joins | User decision; keeps the bitmap engine focused on one collection per query |
| D25 | UI module / jar named `kookaburra-face` (product name: Kookaburra Studio), a separate Maven project, not part of the library build or its Central release | User decision |
| D26 | Commit records a WAL replay point per collection | Collections share one WAL but flush independently; a single point would replay already-flushed rows |
| D27 | WAL rotated at every commit | Files fully covered by segments can be deleted without scanning |
| D28 | Studio themes via AtlantaFX + token stylesheet | User request: switchable themes without duplicating styles |
| D29 | All settings in one `kookaburra.properties` (bundled defaults + file + `-D`) | User request: one place for every parameter, defaults = best practice |
| D30 | Class metadata lives in the Schema tab, not a sidebar panel | It duplicated the schema tree; one place for logical (class) and physical (segments) structure |
| D31 | Stress harness (`kookaburra-bench`) instead of JMH | End-to-end numbers incl. disk, fsync, compaction, concurrency and consistency checks; percentiles from warmed repeated runs |
| D32 | Value-sorted document lists (`sdoc`, 4 B / document) for single-valued indexed columns without postings | Benchmark: point lookup on a high-cardinality index took 13 ms (ordinal scan of 10 M); top-N by an indexed column read every match. With sdoc: 0.1 ms and 0.3 ms |
| D33 | No interactive transactions in v1 (§12.1) | User decision: atomic batches + optimistic lock + snapshots cover the embedded use cases; full transactions would touch the executor, indexes and MVCC |
| D34 | Logging: SLF4J API in the library, logback in Studio | Library must not impose a backend; Studio needs files next to the jar |
| D35 | One Studio jar for Windows, Linux and macOS | User request; native library names differ per OS, so they coexist |
| D36 | License Apache-2.0 | User decision; matches dependencies (RoaringBitmap, AtlantaFX, Ikonli: Apache-2.0; ANTLR: BSD; JavaFX: GPL+CE) |
| D37 | One chained API style (`query().where().eq().orderBy().desc().list()`), `from / filter / toList` kept as equivalents | user decision: uniform fluent style across the database APIs |
| D38 | Transactions: SNAPSHOT + OCC + redo-only, opt-in switch; details T1–T12 in [transaction-design.md](transaction-design.md) | enterprise-mainstream (FoundationDB / RocksDB optimistic / Oracle & PostgreSQL snapshot semantics) and fits the single sequencer |
| D39 | OIDs allocated at insert by per-class atomic counters (not in the sequencer) | final OIDs inside transactions; gaps after rollbacks like database sequences |
| D40 | Flush / compaction on a fixed thread pool (`kookaburra.flush-threads`) | several collections flush in parallel |
| D41 | Segment data files use the `.odbf` extension (`seg_<id>.odbf`); `.seg` files of older databases are renamed on open | user decision: recognisable data files |
| D42 | Paths through embedded lists are multi-valued with "any element" semantics, unindexed | matches JPQL `MEMBER OF` / MongoDB dotted paths; indexing element paths deferred |
| D43 | Studio holds several databases; transactions are per database | user decision; cross-database atomicity would need 2PC (non-goal) |
| D44 | Garbage-driven compaction: drop fully deleted segments, rewrite a segment alone at `compaction-garbage-ratio`; `flush()` / `compact()` wait for work already running | 10 M benchmark: after deleting everything, 4 segments (1.5 GB) stayed on disk because size-tiered merging never picked them |
| D45 | Nested values in Studio follow document-database tools (MongoDB Compass, Studio 3T): summaries in the grid, Key / Value / Type tree, element tables in the form | user request: mainstream presentation |
| D46 | Binaries in JSON / CSV are Base64 (Jackson's `byte[]` default, MongoDB Extended JSON `$binary.base64`); the editor shows large ones as a placeholder | lossless exports; a usable editor |
| D47 | The shared core suites run twice: in memory and on disk (`disk-suite` surefire execution, `TestDbs`) | user requirement: the file-based database is the product, the in-memory one a convenience |
| D48 | Studio console: `;` scripts and SQL-style `BEGIN / COMMIT / ROLLBACK` as console commands, not OQL grammar; toolbar Flush | PostgreSQL / DBeaver console habits; OQL stays a query language (transaction-design T14) |
| D49 | `BigDecimal` = DECIMAL, canonical without trailing zeros (`1.50` stored and read as `1.5`) | numeric equality everywhere (hash maps in grouping, DISTINCT, memtable postings) like `compareTo`; SQL DECIMAL / MongoDB Decimal128 compare numerically too; display with `setScale` |
| D50 | `@Unique` / `@ForeignKey` checked on the sequencer at commit, as index point lookups; ON DELETE actions appended to the same batch | one writer = no race between check and write; deferred like `DEFERRABLE INITIALLY DEFERRED`; cascades atomic and replayable from the WAL |
| D51 | SERIALIZABLE by optimistic read validation (read set of OIDs and query conditions vs before / after versions of later commits) | no locks or blocking (fits the single-writer OCC engine), no false positives from unrelated rows; FoundationDB / CockroachDB style; read-only transactions never abort |
| D52 | READ COMMITTED = fresh snapshot per statement, conflicts relative to the statement that read the object | PostgreSQL / Oracle default semantics; long transactions abort less |
| D53 | Lifecycle callbacks with JPA semantics: PRE at the call, POST after the durable commit, skipped for bulk OQL / by-OID / dynamic writes | familiar to JPA users; no callback runs for a write that never commits |
| D54 | Online backup = export of one snapshot into fresh compacted segments (not a file copy) | consistent without pausing writers, works for in-memory databases, the copy is compact and immediately openable |
| D55 | Composite unique constraints as repeatable class-level `@Unique(fields = …)`; nulls exempt | JPA `@Table(uniqueConstraints = …)` shape, SQL null semantics; one annotation for both forms |
| D56 | Monitoring as a pull API `db.stats()` (immutable `DatabaseStats`), no metrics library in core | embedded libraries stay dependency-free (Caffeine `stats()`, RocksDB `Statistics`); Micrometer / Dropwizard binding is the application's choice |
| D57 | Vectors as `float[]` (float32 only) with `@Vector(dim, distance)`, exact search first, pgvector distance definitions and function names | mainstream embedding precision; exact pre-filtered search is correct for any filter and fast enough to 10⁵ to 10⁶ vectors; HNSW per segment fits the immutable segments (phase 2) |
| D58 | Studio ships as native packages built by GitHub Actions on version tags, with version-less file names on a release marked latest | users always download the newest build from one fixed link; packages bring their own runtime; the user only pushes a tag |
| D59 | Approximate vector search with an HNSW graph per immutable segment (Lucene's design: built at flush / merge, filter bitmap as accept set, exact fallback for selective filters, exact float32 distances in results); graph only for `@Vector(dim = N)` and from `vector-index-min-rows` vectors | segments are immutable, so a graph never changes after it is written; same quality knobs as Lucene / pgvector (`m`, `ef_construction`, `ef`); exact fallback keeps filtered recall at 1.0 |
| D69 | `@Comment` stored in the schema, comment-only changes replace the current layout; schema export generates Java from the stored definitions with wrapper types and nested classes | Hibernate 6 / SQL `COMMENT ON` users know; a comment is documentation, not a format change; generated classes must map to the stored fields exactly so a database can be opened without its original code |
| D68 | int8 copies of the vectors for the graph search, float re-ranking of all `ef` candidates | a quarter of the memory read while searching, exact distances in results (Elasticsearch / Lucene scalar quantization) |
| D67 | Phrases checked on the candidates' text instead of positions in segments | no format change and no positions to store; the index still narrows to documents with every word |
| D66 | Element conditions: Java builder + OQL JOIN over embedded lists with EXISTS semantics, superset pushdown + exact per-element check | MongoDB `$elemMatch` / JPQL join semantics users know; pushdown only where it cannot drop matches |
| D65 | `@Renamed`: rewrite former names in stored layouts (fields), former class names in the schema (classes); collections keep their storage name | no data rewrite at rename time; old references and names keep working; merges move indexes to the new paths |
| D64 | Durability per transaction (`TxOptions.durability`) and per batch (`WriteBatch.durability`), resolved per group commit: one fsync when any request of the group is SYNC | PostgreSQL's per-transaction `synchronous_commit`, MongoDB's per-operation write concern; bulk loads and logs need not pay an fsync in a SYNC database |
| D63 | Query plans as a tree of steps with access kinds, `EXPLAIN ANALYZE` measured by an optional executor profile, Studio diagram | the shape users know from PostgreSQL / MySQL 8 and pgAdmin / Workbench; one model for the text, the API and the diagram; no cost when not analyzing |
| D62 | Full-text search on per-segment inverted indexes built from the existing dictionary + Roaring postings, BM25 with collection statistics, analyzers by name with Lucene's `english` chain (Porter) as default and a multilingual `standard` one; no Lucene dependency | same building blocks as the columns, no new dependency; results and scores as users know them from Lucene / Elasticsearch; names in the schema keep index and queries in step |
| D61 | Rerank through an LLM's yes / no log-probabilities on Ollama `/api/generate` (no rerank endpoint in Ollama), behind a `Reranker` interface; MMR on the vector distances | the Qwen3-Reranker scoring works with any local instruct model and with dedicated reranker models; MMR needs nothing but the candidates' vectors |
| D60 | Text embeddings through an `Embedder` interface in core with Ollama as the only built-in (JDK HTTP client, contacted lazily, default model `nomic-embed-text`) | the library stays dependency-free and local-first; Ollama is the common local runtime; anything else plugs in via `Config.withEmbedder` |

## 20. Changelog

| Date | Change |
|---|---|
| 2026-11-09 | Tag workflow publishes kookaburra-core and kookaburra-processor to Maven: release tags to Maven Central, rc tags to the Central snapshot repository (§17.5) |
| 2026-11-06 | Fix (storage-design S12): segment files are written into a presized sink without a final copy; bulk load back to about 690 k/s, no more OutOfMemoryError in background merges at -Xmx8g |
| 2026-11-06 | Fix (storage-design S11): cleanup after a commit no longer deletes the temporary file of a segment a flush or merge thread is writing ("cannot rename seg_N.odbf.tmp", then "database failed") |
| 2026-11-06 | Fix: references inside lists, sets and map values keep their target (`LIST(REF(Member))` instead of `REF(Object)`); list foreign keys check the referenced class; Studio shows `Ref<T>` / `List<Ref<T>>` |
| 2026-11-06 | `@Comment` on classes and fields (§3.5, D69); `exportSchema` / `SchemaExport` (§3.5); Studio: comments on the Schema page and as grid header hints, Export as Java with editable preview, menu bar, About feature map, pure white / black text (§17.3); tutorial video (2:58) with the Export as Java clip; `bin/data` rebuilt |
| 2026-11-05 | `@Renamed` fields and classes (§3.5, D65, schema format 5); element conditions `whereElement` / OQL `JOIN` over embedded lists (§10.8, D66); full-text phrases and `HIGHLIGHT` (§10.7, D67); int8 vectors (§10.6, D68, segment format 4); crash-image tests of the search features |
| 2026-11-05 | Durability per transaction and per batch (D64): `TxOptions.durability(...)`, `WriteBatch.durability(...)`; group commit forces the WAL only when a request of the group needs it |
| 2026-11-05 | Query plans (§10.5, D63): `QueryPlan` tree, `plan()` / `analyze()` / `explainAnalyze()`, OQL `EXPLAIN ANALYZE` and `OqlQuery.plan()`, type filters by class name; Studio Plan diagram with Explain / Analyze |
| 2026-11-05 | Full-text search (§10.7, D62): `@FullText(analyzer)`, `Analyzer` / `Analyzers` (english with Porter, standard, custom), `matches` / `matchesAny`, `search(field, text, k)` with BM25, `hybrid(...)` with RRF, OQL `MATCH` / `SCORE`, segment format 3; Studio demo product descriptions |
| 2026-11-05 | Search quality: `nearest(...).diverse(λ, fetch)` (MMR) and `.rerank(textField[, query, fetch])` with `Reranker` (Ollama, yes / no log-probabilities, D61); `Neighbor.score()`; explain lines DIVERSE / RERANK; `kookaburra.reranker*` settings |
| 2026-11-05 | Vector search phase 2 (§10.6, D59): per-segment vector columns (floats read in place) and HNSW graphs, `numCandidates` / `exact()`, explain per segment, segment format 2, `kookaburra.vector-*` settings; text embeddings (D60): `Embedder`, Ollama, `nearest(field, "text", k)`, OQL `EMBED`; Studio "Find similar" row action and "From text" for vector fields; bench vector phase; backlog reordered |
| 2026-11-04 | Studio demo: products carry a 4-dimensional embedding `[reading, tech, home, outdoor]` (books, gadgets and items form clusters); tutorial video (2:56) adds vector search in the console, the Stats dashboard, the vector API slide and the download links; blogs synced |
| 2026-11-04 | Studio distribution: GitHub Actions on tag `v*` → jpackage installers for Windows / macOS / Linux on a GitHub Release marked latest (§17.5, D58); installed app uses `~/Kookaburra`; databases given at start report errors in dialogs, non-empty foreign directories need confirmation |
| 2026-11-04 | Vector search phase 1: `float[]` VECTOR type with `@Vector(dim, distance)`, exact `nearest(...)` with filters, `listWithDistance()`, OQL `COSINE_DISTANCE` / `L2_DISTANCE` / `INNER_PRODUCT` / `VECTOR_DIMS` with ORDER BY pushdown (§3.3, §10.6, D57); schema format 4; Studio display, JSON and form editing of vectors |
| 2026-10-17 | Statistics API `db.stats()` / `DatabaseStats` (§15.3, D56); Studio Stats tab |
| 2026-10-17 | Storage design split into [storage-design.md](storage-design.md) (§4, §5, §6.1 to §6.4, §7, §8, §13, §14, §15, §15.2 now link there); [developer-guide.md](developer-guide.md) added |
| 2026-10-17 | Tutorial video as one run: `mvn -f kookaburra-face/pom.xml test -P tutorial` records and renders (`TutorialRecorder` + `TutorialVideo`, ffmpeg); visible mouse cursor with click rings; Studio first, then the API; covers constraints, isolation levels, backlinks and backup; `docs/tools/make-tutorial.sh` removed |
| 2026-10-17 | Composite unique constraints: class-level repeatable `@Unique(fields = …, name = …)` (§3.6, D55), schema format 3, Studio constraint column |
| 2026-10-17 | `BigDecimal` (DECIMAL, D49); `@Unique` and `@ForeignKey` with RESTRICT / CASCADE / SET_NULL and backlinks `referrers` (§3.6, §6.5, D50); READ COMMITTED and SERIALIZABLE (§12.1, D51, D52), `kookaburra.tx-isolation`, `inTransaction(TxOptions, …)`; lifecycle callbacks and `EntityListener` (§3.7, D53); online backup (§15.2, D54); schema file format 2; Studio: isolation menu, Backup, backlinks, constraint column, decimals; demo with unique e-mail / SKU, foreign keys and decimal prices; bench phases Integrity and backup |
| 2026-10-16 | Studio: console scripts (`;`) and `BEGIN` / `START TRANSACTION` / `COMMIT` / `ROLLBACK` (D48, transaction-design T14), toolbar **Flush**; `bin/data` re-seeded with the nested demo; savepoints dropped from the roadmap (T13) |
| 2026-10-15 | Tutorial video (2:33, English subtitles, original synthesised BGM): `TutorialRecorder` drives Studio and renders frames, `docs/tools/make-tutorial.sh` (ffmpeg: crossfades, libass subtitles, loudnorm) → `docs/assets/kookaburra-tutorial.mp4`, played on the website; 10 M benchmark re-run (§16.1) |
| 2026-10-15 | Garbage-driven compaction and waiting `flush()` / `compact()` (D44, §13); Studio nested-object UI: grid summaries, inspector, element tables, binary fields with preview, long texts (D45, §17.3); binaries as Base64 in JSON / CSV with an editor placeholder (D46); demo data with nested objects, photos and long notes; LOB tests (texts to 3 M characters, binaries to 16 MiB); core suites also on disk (D47) |
| 2026-10-15 | Studio: transactions (Begin / Commit / Rollback, conflict dialog), several databases in one window (D43), `--data` + `--demo` together; `.odbf` segment files (D41); nested objects: `Map<String, Embeddable>`, element paths through embedded lists (D42), keywords as OQL path segments; bench Transactions + Delete phases; memory + disk variants of transaction / nested / Studio tests |
| 2026-10-15 | M12: transactions per [transaction-design.md](transaction-design.md) (opt-in `kookaburra.transactions`), atomic `increment`, OIDs at insert, flush thread pool; chained query API (`query / where / orderBy / list`, dynamic `where("path")`); Studio: export of console results (JSON / JSONL / CSV) |
| 2026-10-13 | Draft [transaction-design.md](transaction-design.md): PostgreSQL-aligned isolation levels (RC / RR / SERIALIZABLE), read-your-writes overlay, optimistic commit validation, savepoints, error codes: for review, no code yet |
| 2026-10-11 | Boundary testing of Studio: fixed `EXPLAIN DELETE` executing (core `execute()` now refuses EXPLAIN), `LIMIT 0` returning a row, empty last page after deletes, stale tree counts after console DML, editor closing on type errors, filter accepting `LIMIT`; readable OQL syntax errors (unterminated strings named, long expected-token sets cut); malformed dates reported per field; license Apache-2.0 confirmed (D36) |
| 2026-10-11 | M11 performance pass from the 10 M benchmark: path-only row decoding, ordinal / postings group-by, index-order top-N, sdoc lists (§5, §10.3, §10.4); SLF4J logging (§15.1); transactions scope (§12.1); Studio: export JSON / JSONL / CSV, Schema tab merges metadata, cross-platform jar, logback, `StudioApplication` |
| 2026-10-11 | M10 done. Single `kookaburra.properties` (§16); Studio fat jar (`-P dist` → `bin/`), default data dir next to the jar; Schema tab merges class metadata + segments; `#` row numbers; Data reload button |
| 2026-10-11 | Initial English design (supersedes `object-db-storage-design.md`) |
| 2026-10-11 | M0/M1 done. Synced with code: `@Embeddable`/`@Transient`, field hierarchy (`ObjectField`, `CollectionGetter`), value normalization, metamodel naming |
| 2026-10-11 | `docs/index.html` GitHub Pages viewer for this document; full `.gitignore` |
| 2026-10-11 | Renamed package / groupId `io.kookaburra` → `com.github.kookaburra` |
| 2026-10-11 | Studio: themes, About, metadata panel, paged console results, property columns for object results; class `final` only on value types; no inline fully-qualified names |
| 2026-10-11 | M9 done: dynamic access (`DynamicObject`, `DynField`, `DynamicCollection`), OQL (ANTLR grammar, AST, push-down, fast-path grouping, SELECT NEW, UPDATE / DELETE, EXPLAIN); version 1.0.0-SNAPSHOT |
| 2026-10-11 | Maven Central readiness: groupId `com.github.paganini2008`, POM metadata (url, Apache-2.0, developer, scm, issues), `release` profile (sources, javadoc with custom header tags, GPG, central-publishing-maven-plugin, autoPublish off); UI excluded from library build |
| 2026-10-11 | M7–M8 done: tiered compaction with late-delete remapping, caller-side back-pressure, `compact()`, `get(oid)`, `Ref.resolve`, `fetch`, semi-join `in(QueryStream)`, `updateEach`, `deleteAll` |
| 2026-10-11 | M4–M6 done: segment files, flush pipeline, Storage (memory / disk), WAL with per-commit rotation, commit files with per-collection replay points, crash recovery, segment delete logs |
| 2026-10-11 | M3 done: in-memory DB (sequencer, MemSegment, executor, QueryStream / Projection / GroupStream with HAVING and COUNT DISTINCT), differential suite |
| 2026-10-11 | M2 done; single sequencer write path (§6.1); layouts (§3.5); JaCoCo ≥ 90 % gate |
| 2026-10-11 | Positioning: single-collection SQL-92 core semantics; OQL expressions / functions / CASE / LIKE / COUNT DISTINCT / ORDER BY alias & position |
| 2026-10-11 | OQL parser: ANTLR 4 instead of hand-written (D22) |
| 2026-10-11 | Text query language redesigned as JPQL-style OQL (§9.9, was "KQL"), part of the core API |
| 2026-10-11 | Added Studio (§17), dynamic access (§9.8), OQL (§9.9), self-describing schema (§3.4); milestones M9/M10; dark/neon style spec (§17.2) |
