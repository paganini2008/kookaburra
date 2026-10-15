# Kookaburra：用 Stream 的写法查 Java 对象，底层用 RoaringBitmap 加速

![Kookaburra](https://paganini2008.github.io/kookaburra/assets/logo_256.png)

## 1. 概述

**Kookaburra**：*Query Java objects like a database — bitmaps underneath, streams on top.*

Kookaburra 是面向 **Java 17+ 的嵌入式对象数据库**。普通的注解类直接存进去，就可以用类型安全的链式 API 或类 JPQL 的 **OQL** 查询。过滤、排序、聚合跑在 **RoaringBitmap** 索引和内存映射的段文件上，支持可选的**快照隔离事务**、嵌套对象和大字段（长文本、图片）。另外配了一个桌面管理工具 **Kookaburra Studio**。

![Kookaburra Studio](https://paganini2008.github.io/kookaburra/assets/studio-data.png)

**教学视频（2 分 33 秒，英文字幕）**：

<video controls preload="metadata" playsinline width="100%" poster="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial-poster.jpg">
  <source src="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4" type="video/mp4">
</video>

如果视频没有显示，可以[在项目网站观看](https://paganini2008.github.io/kookaburra/#tutorial)，或[直接打开 MP4](https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4)。

## 2. 解决什么问题

应用要把自己的对象存在本地并按条件查询时，常见的做法是 ORM + 关系库。这样要维护映射、建表、写 SQL，有时还得多跑一个服务进程。另一种做法是全放进 `List` 里用 `stream().filter()`：每次查询都全量扫描，数据也只能留在堆里。

Kookaburra 把这两者结合：**写法和 Java Stream 一样，执行像数据库一样**，条件下推到位图和有序字典索引，数据持久化在磁盘上并支持崩溃恢复。另外它不需要单独的服务进程。

| 痛点 | Kookaburra 的做法 |
|---|---|
| ORM / SQL 映射层太重 | 直接存注解对象，无需建表 |
| `stream().filter()` 全量扫描 | 位图 / 序号索引，在 1000 万对象上毫秒级 |
| 字符串字段名，重构后静默失效 | `where(Book::getYear)` 或生成的 `Book_` 元模型 |
| 多个对象要一起改（转账、库存） | 可选的快照隔离事务，冲突时抛出 `ConflictException` |
| 领域模型有多层嵌套 | 嵌入对象、嵌入对象列表和 Map 都能用点号路径查询 |
| 二进制存储看不见、改不了 | Studio：多库并存、带事务的增删改、嵌套对象查看器、图片预览、OQL、导出 |

## 3. 快速开始

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

用 Studio 查看：`mvn -f kookaburra-face/pom.xml -P dist package` → 双击 `bin/kookaburra-face.jar`。

## 4. 环境要求

| 项 | 要求 |
|---|---|
| Java | 17+ |
| 运行时依赖 | RoaringBitmap 1.6.22、ANTLR 4.13.2 runtime、SLF4J API 2.0 |
| 构建 | Maven 3.8+ |
| Studio | Java 17+；Windows x64 / Linux x64 / macOS Apple Silicon，一个 jar 通用（已内置 JavaFX） |

## 5. 工作原理

```mermaid
flowchart LR
    T["Typed API / OQL / Dynamic"] --> P["Planner"] --> X["Executor"]
    X --> V["MVCC 快照"]
    V --> M["MemSegment（内存）"]
    V --> S["段文件（mmap）<br/>字典 · 序号 · 倒排 · 按值排序文档"]
    W["写入线程（编码）"] --> Q["Sequencer<br/>组提交"] --> L["WAL"]
    Q --> M
    M -- 刷盘 --> S
    S -- 分层合并 --> S
```

| 层 | 作用 |
|---|---|
| 查询 API | 类型化字段、OQL、动态访问最终都编译成同一种谓词计划 |
| 执行器 | 条件下推到倒排（Roaring）和有序序号；按代价排列合取项；top-N 按索引顺序遍历，凑够 N 条即停 |
| 写入 | 单一 sequencer 线程：校验 → WAL（每组一次 fsync）→ MemSegment → 发布可见序号 |
| 存储 | 不可变段文件：字典、位压缩序号、倒排位图、按值排序文档、空值位图、bloom；后台分层合并 |

## 6. 代码示例

**示例一：分组聚合**

- 输入：6 本书（4 本科幻、1 本奇幻、1 本推理）
- 执行：

```java
db.query(Book.class).groupBy(where(Book::getGenre))
  .agg(Q.count().as("books"), Q.avg(where(Book::getPrice)).as("avgPrice"))
  .sortedBy(Q.count().as("books").desc())
  .toList();
```

- 输出：

```text
SCIFI   books=4 avg=11.36
FANTASY books=1 avg=10.00
CRIME   books=1 avg=7.40
```

**示例二：OQL 查询与批量更新**

- 输入：同上
- 执行：

```java
db.oql("SELECT b.author, b.year FROM Book b WHERE b.year < :y ORDER BY b.year").param("y", 1950).rows();
db.oql("UPDATE Book b SET b.price = b.price * 0.9 WHERE b.genre = 'CRIME'").execute();
```

- 输出：

```text
J.R.R. Tolkien 1937
Raymond Chandler 1939
updated=1
```

**示例三：注解处理器生成的元模型**

- 执行：`db.query(Book.class).filter(Book_.tags.contains("classic")).filter(Book_.price.lt(10.0)).count()`
- 输出：`2`

**示例四：事务（需要设置 `kookaburra.transactions=true`）**

- 输入：两个账户，ann 余额 100，bob 余额 0
- 执行：

```java
try (Transaction tx = db.begin(Isolation.SNAPSHOT)) {
    Account a = tx.get(Account.class, from);
    tx.update(a.balance(a.getBalance() - 30));
    tx.increment(Account.class, to, "balance", 30);   // 原子增量，不会冲突
    tx.commit();                                      // 期间 a 被别人改过 → ConflictException
}                                                     // 没有提交 → 自动回滚
```

- 输出：ann = 70，bob = 30；两个事务并发修改同一对象时，后提交的那个抛出 `ConflictException`

**示例五：嵌套对象**

- 输入：`Shipment` 包含 `Route route`（两层嵌入）、`List<Item> items`、`Map<String, Place> stops`
- 执行：

```java
db.oql("SELECT s.code FROM Shipment s WHERE s.route.to.city = 'Perth'").list();
db.query(Shipment.class).whereAny(Shipment::getItems, Item::getSku).contains("pear").list();
db.oql("SELECT s.code FROM Shipment s WHERE 'kiwi' MEMBER OF s.items.sku").list();
```

- 输出：`[S-1]`、`S-1`、`[S-2]`（经过嵌入对象列表的路径表示“任一元素满足”，与 JPQL `MEMBER OF` 一致）

**示例六：长文本与图片**

```java
db.insert(new Document("scan", text, Files.readAllBytes(Path.of("scan.png"))));
```

已测试 200 万字符的 Unicode 文本和 5～16 MiB 的二进制数据，覆盖刷盘、合并、事务和重新打开；导出成 JSON / CSV 时二进制用 Base64 表示。

**Studio 中的嵌套对象**：表格里嵌入对象列表显示为 `[ 2 elements ]`；右侧 Inspector 以 Key / Value / Type 树展示每一层；编辑器里用元素表格增删和调整顺序；图片字段可以预览、载入和保存。

![嵌套对象](https://paganini2008.github.io/kookaburra/assets/studio-nested.png)

## 7. 配置

所有配置都在一个 `kookaburra.properties` 文件里。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `kookaburra.mem-segment-bytes` | `64MB` | 内存段多大时刷盘 |
| `kookaburra.segment-max-bytes` | `512MB` | 合并后单个段的上限 |
| `kookaburra.durability` | `SYNC` | `SYNC` 每组提交 fsync；`ASYNC` 只写入页缓存 |
| `kookaburra.group-commit-micros` | `200` | 等待更多提交并入同一次 fsync 的时间 |
| `kookaburra.auto-postings-ratio` | `16` | AUTO 索引在"行数 / 不同值数 ≥ 该值"时写倒排 |
| `kookaburra.compaction-garbage-ratio` | `0.3` | 段中已删除行的比例达到该值时单独重写该段；完全删空的段直接丢弃 |
| `kookaburra.transactions` | `false` | 开启事务；关闭时每次写入直接提交 |
| `kookaburra.tx-timeout` / `tx-retries` | `60s` / `3` | 事务超时；`inTransaction` 冲突后的重试次数 |
| `kookaburra.data` | jar 同级 `data/` | Studio 默认数据目录 |
| `kookaburra.log-dir` / `log-level` | jar 同级 `logs/` / `INFO` | Studio 日志 |

## 8. 性能

**环境**：Apple M2 Max（12 核）、32 GB、内置 SSD（fsync ≈ 4 ms）、JDK 17、`-Xmx8g`；**1000 万对象**；压测工具 `kookaburra-bench`（预热、p50 / p99、每个阶段后做一致性校验）。

| 操作（1000 万对象） | p50 |
|---|---|
| 批量写入（每批 100 条，ASYNC） | 63.2 万对象/秒 |
| 持久化单条写入（SYNC），16 / 128 线程 | 3.7k/s / 27.8k/s（组提交） |
| `get(oid)` | 0.75 µs |
| 位图等值计数 | 0.24 ms |
| 等值 + 范围 + limit 100 | 0.21 ms |
| 230 万命中中按索引列取前 10 | 0.40 ms |
| `GROUP BY city COUNT(*)` | 4.1 ms |
| 唯一键查找（OQL） | 0.11 ms |
| 未建索引条件的全量扫描 | 0.76 s |
| 事务转账（读 2 写 2），16 线程 | 13.5 万次/秒，每次提交平均重试 0.022 次 |
| 热点计数器原子增量，16 线程 | 25.1 万次/秒，没有丢失更新 |
| OQL 删除全部 1060 万对象 → compact 后的磁盘占用 | 116 万对象/秒 → 0 B（空间完全回收） |
| 重新打开 / 打开后堆占用 | 2.4 ms / 6.4 MiB |

**调优前后对比**：

| 项 | 调优前 | 调优后 | 做法 |
|---|---|---|---|
| GROUP BY 计数 | 902 ms | 4.1 ms | 直接用倒排做 `andCardinality`，不访问任何行 |
| top-10 排序 | 187 ms | 0.40 ms | 按索引顺序遍历，凑够 N 条即停 |
| 唯一键查找 | 13 ms | 0.11 ms | 新增按值排序的文档列表 |
| 未建索引的扫描 | 跑不完 | 0.76 s | 按路径只解码需要的字段 |
| 删光后的磁盘空间 | 1.5 GB 不回收 | 0 B | 按垃圾比例触发合并，完全删空的段直接丢弃 |

## 9. 局限与取舍

- 持久化单条写入受 fsync 延迟限制：吞吐 ≈ 并发数 ÷ fsync 延迟；大批量写入请用批量或 `ASYNC`。
- 只支持单集合查询，不做跨集合 JOIN（可用引用 + `fetch` / 半连接 `in(...)`）。
- 事务是快照隔离（与 Oracle、PostgreSQL 的 REPEATABLE READ 相同），不是可串行化：可能出现写偏斜，需要用共享写来保护不变量。
- 不适合：多进程同时写同一目录、需要 SQL 生态（BI 工具 / JDBC）的场景、TB 级数据。

## 10. 总结

1. Stream 的写法，数据库的执行方式：条件下推到位图和有序字典。
2. 方法引用和生成的元模型两种写法都类型安全，可以混用。
3. OQL 覆盖 SQL-92 的单表核心语义，适合工具和脚本。
4. 写入走 WAL + 组提交，读取走 MVCC 快照，崩溃后可恢复。
5. 在 1000 万对象上，常见查询都在亚毫秒到毫秒级。
6. mmap 段文件：打开快、堆占用小。
7. 可选的快照隔离事务：读到自己的写入，先提交者胜，原子增量。
8. 嵌套对象（多层嵌入、嵌入对象列表、Map）和大字段（长文本、图片）都能存取和查询。
9. Studio 一个 jar 在三大平台都能双击运行：多库并存、带事务的增删改查、嵌套对象查看器、OQL 和导出。
10. 共享的测试套件在内存版和磁盘版各跑一遍，覆盖率 ≥ 90%，并做了 1000 万对象的压测。
11. 只有一个配置文件，默认值即最佳实践。
