# Kookaburra：用 Stream 风格查询 Java 对象，RoaringBitmap 加速

![Kookaburra](https://paganini2008.github.io/kookaburra/assets/logo_256.png)

## 1. 概述

**Kookaburra**：*像数据库一样查询 Java 对象，底层是位图，上层是 Stream。*

面向 **Java 17+ 的嵌入式对象数据库**。存入的是普通的注解类，查询用类型安全的链式 API 或类 JPQL 的 **OQL**，执行在内存映射文件上的 **RoaringBitmap** 索引里。支持三种隔离级别的事务、唯一约束和外键、精确小数、在线备份，并配有桌面工具 **Kookaburra Studio**。

![Kookaburra Studio](https://paganini2008.github.io/kookaburra/assets/studio-data.png)

**教学视频（2:33，英文字幕）**：

<video controls preload="metadata" playsinline width="100%" poster="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial-poster.jpg">
  <source src="https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4" type="video/mp4">
</video>

如果播放器没有显示，可以[在项目网站观看](https://paganini2008.github.io/kookaburra/#tutorial)或[直接打开 MP4](https://paganini2008.github.io/kookaburra/assets/kookaburra-tutorial.mp4)。

## 2. 解决什么问题

应用要保存和查询自己的对象，通常得上 ORM 加关系数据库；轻量的做法是 `stream().filter()`，但每次查询都要全量扫描。Kookaburra 介于两者之间：**查询写起来像 Stream，执行起来像数据库**，而且就跑在你的进程里。

| 痛点 | Kookaburra |
|---|---|
| ORM / SQL 映射太重 | 注解对象直接存 |
| `stream().filter()` 全量扫描 | 位图和序号索引：千万对象毫秒级 |
| 字段名写在字符串里 | `where(Book::getYear)` 或生成的 `Book_` |
| 重复数据、悬空引用 | `@Unique`、`@ForeignKey`（RESTRICT / CASCADE / SET_NULL） |
| 多个对象要一起修改 | 事务：READ COMMITTED、SNAPSHOT、SERIALIZABLE |
| 金额舍入误差 | 精确的 `BigDecimal` |
| 运行中的库怎么备份 | 写入不停，`db.backup(dir)` |
| 二进制存储看不见内容 | Studio：浏览、编辑、查询、备份 |

## 3. 快速上手

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

用 Studio 查看：`mvn -f kookaburra-face/pom.xml -P dist package`，然后双击 `bin/kookaburra-face.jar`。

## 4. 环境要求

| 项目 | 要求 |
|---|---|
| Java | 17+ |
| 运行时依赖 | RoaringBitmap 1.6.22、ANTLR 4.13.2 runtime、SLF4J API 2.0 |
| 构建 | Maven 3.8+ |
| Studio | Java 17+；Windows x64 / Linux x64 / macOS Apple Silicon 共用一个 jar（已内置 JavaFX） |

支持的字段类型：`boolean`、各种整数、`float` / `double`、**`BigDecimal`**、`String`、枚举、`LocalDate` / `LocalDateTime` / `Instant`、`Ref<T>`、以上类型的 List / Set、`@Embeddable` 类（及其 List / Map）、`Map`、`byte[]`、任意 `Serializable`。

## 5. 工作原理

![架构](https://paganini2008.github.io/kookaburra/assets/architecture.png)

所有写入都经过同一个写线程（sequencer），写 WAL 之前依次检查冲突和约束：

![写入流程](https://paganini2008.github.io/kookaburra/assets/write-path.png)

| 层 | 作用 |
|---|---|
| 查询 API | 类型安全字段、OQL、动态访问，编译成同一套谓词计划 |
| 执行器 | 条件下推到 Roaring 倒排和有序序号；按索引顺序取 Top-N |
| 写入路径 | 回调 → 冲突检查 → 约束检查 → WAL（每组一次 fsync）→ MemSegment → 发布 |
| 存储 | 不可变的 mmap 段文件、删除日志、分层合并 |

## 6. 代码示例

**示例 1：分组聚合**

- 输入：六本书（科幻 4、奇幻 1、犯罪 1）
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

**示例 2：OQL 查询与批量更新**

```java
db.oql("SELECT b.author, b.year FROM Book b WHERE b.year < :y ORDER BY b.year").param("y", 1950).rows();
db.oql("UPDATE Book b SET b.price = b.price * 0.9 WHERE b.genre = 'CRIME'").execute();
```

```text
J.R.R. Tolkien 1937
Raymond Chandler 1939
updated=1
```

**示例 3：约束、小数、生命周期回调**

- 输入：

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

- 执行与输出：

| 代码 | 输出 |
|---|---|
| `db.insert(new Order("A-1", ann, "5"))`（A-1 已存在） | `ConstraintViolationException: duplicate value 'A-1' for unique Order.number` |
| 对 19.90 + 10.15 求和：`db.query(Order.class).sum(where(Order::getTotal))` | `30.05`（精确） |
| `db.referrers(Order.class, ann)` | `[A-1, A-2]`（反向链接） |
| `db.delete(Customer.class, ann)` | Ann 和她的订单在同一次提交里一起删除 |

![约束](https://paganini2008.github.io/kookaburra/assets/foreign-keys.png)

**示例 4：SERIALIZABLE 杜绝写偏斜（write skew）**

- 输入：两位医生值班；各自看到"另一位还在值班"，就把自己改成下班
- 执行：

```java
db.inTransaction(TxOptions.defaults().isolation(Isolation.SERIALIZABLE), tx -> {
    long onCall = tx.query(Doctor.class).where(Doctor::isOnCall).eq(true).count();
    if (onCall > 1) {
        tx.update(tx.get(Doctor.class, me).offCall());
    }
});
```

- 输出：SNAPSHOT 下两个事务都提交成功，结果没人值班；SERIALIZABLE 下第二个提交失败（`ConflictException`，"serialization failure"），自动重试后仍有一位医生在值班

![隔离级别](https://paganini2008.github.io/kookaburra/assets/isolation.png)

**示例 5：在线备份**

```java
BackupInfo info = db.backup(Path.of("backups/2026-10-17"));   // 写入不停
try (ObjectDB copy = ObjectDB.open(info.path())) { … }        // 恢复 = 打开目录
```

![在线备份](https://paganini2008.github.io/kookaburra/assets/backup.png)

**示例 6：嵌套对象与大字段**

```java
db.oql("SELECT s.code FROM Shipment s WHERE s.route.to.city = 'Perth'").list();        // [S-1]
db.oql("SELECT s.code FROM Shipment s WHERE 'kiwi' MEMBER OF s.items.sku").list();     // [S-2]
db.insert(new Document("scan", text, Files.readAllBytes(Path.of("scan.png"))));        // MiB 级字段
```

**Studio 里**：选中对象的反向链接、每个字段的约束、嵌套值的树形展示。

![Studio 反向链接](https://paganini2008.github.io/kookaburra/assets/studio-backlinks.png)

![Studio 约束列](https://paganini2008.github.io/kookaburra/assets/studio-constraints.png)

## 7. 配置

只有一个文件：`kookaburra.properties`。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `kookaburra.mem-segment-bytes` | `64MB` | 内存段达到该大小后刷盘 |
| `kookaburra.segment-max-bytes` | `512MB` | 合并输出段的上限 |
| `kookaburra.durability` | `SYNC` | `SYNC` 每个提交组 fsync，`ASYNC` 走页缓存 |
| `kookaburra.group-commit-micros` | `200` | 等待更多提交合并为一次 fsync |
| `kookaburra.compaction-garbage-ratio` | `0.3` | 删除行占比达到该值时单独重写该段 |
| `kookaburra.transactions` | `false` | 开启事务；关闭时每次写入直接提交 |
| `kookaburra.tx-isolation` | `SNAPSHOT` | 默认隔离级别：`READ_COMMITTED`、`SNAPSHOT`、`SERIALIZABLE` |
| `kookaburra.tx-timeout` / `tx-retries` | `60s` / `3` | 事务超时；`inTransaction` 冲突重试次数 |
| `kookaburra.data` | jar 同级 `data/` | Studio 默认数据目录 |

## 8. 性能与对比

**环境**：Apple M2 Max（12 核）、32 GB、内置 SSD（fsync ≈ 4 ms）、JDK 17、`-Xmx8g`；**1000 万对象**；压测工具 `kookaburra-bench`（预热、p50 / p99、每个阶段后做一致性校验）。

| 操作（1000 万对象） | p50 |
|---|---|
| 批量插入，每批 100（`ASYNC`） | 69.6 万对象/秒 |
| 持久化单条插入（`SYNC`），16 / 128 线程 | 3.2 k/s / 21.2 k/s |
| `get(oid)` | 0.6 到 1.1 µs |
| 位图等值计数 | 0.24 ms |
| 等值 + 范围 + limit 100 | 0.21 ms |
| 230 万命中中按索引列取 Top-10 | 0.39 ms |
| `GROUP BY city COUNT(*)` | 4.0 ms |
| 唯一键查找（OQL） | 0.12 ms |
| 无索引条件全表扫描 | 0.81 s |
| 转账，16 线程：SNAPSHOT / READ COMMITTED / SERIALIZABLE | 13.6 / 14.2 / 14.0 万/秒 |
| 单个热点计数器原子 `increment`，16 线程 | 26.5 万/秒，无丢失更新 |
| 有 / 无 `@Unique` 的插入（内存库） | 23.5 / 25.8 万/秒 |
| 1050 万对象在线备份 | 25.4 秒，写入不阻塞 |
| OQL `DELETE` 全部对象，`compact()` 后磁盘占用 | 132 万对象/秒，剩余 0 B |
| 重新打开 / 打开后堆占用 | 2.0 ms / 6.5 MiB |

## 9. 局限与取舍

- 持久化单条写入受 fsync 限制；批量导入请用批次或 `ASYNC`。
- 只支持单集合查询，不支持 join（用引用加 `fetch`，或半连接 `in(...)`）。
- 事务是乐观的：冲突在提交时暴露并重试，不会阻塞。
- 约束只支持单字段（暂不支持复合唯一键）。
- 不适合：多个进程同时写同一个目录、依赖 SQL / JDBC / BI 生态、TB 级数据。

## 10. 总结

1. Stream 风格的代码，数据库风格的执行，跑在位图和有序字典上。
2. 方法引用或生成的元模型，字段类型安全。
3. OQL 覆盖单表 SQL-92 核心。
4. 三种隔离级别；SERIALIZABLE 无锁消除写偏斜。
5. `@Unique` 和 `@ForeignKey`（含删除动作），在提交时原子检查。
6. 精确小数、嵌套对象、大字段。
7. JPA 风格的生命周期回调和监听器。
8. 一致性快照的在线备份；恢复就是打开目录。
9. 千万对象下亚毫秒到几毫秒；秒开，堆很小。
10. Studio 一个 jar；所有功能在内存和磁盘两种模式下都有测试，覆盖率 ≥ 90%。
