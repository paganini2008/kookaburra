# 对象数据库存储与查询设计（精简版）

## 1. 目标
- 纯 Java 实现的嵌入式对象数据库，读写平衡，**支持 JDK 17+**。
- 存储结构本身即索引，原生支持 filter / order by / group by。
- 唯一外部依赖：RoaringBitmap（Apache 2.0，可作为 Maven 依赖或 vendor 源码）。

## 2. 数据模型
- **OID**：long，高位为类 ID，低位为类内自增序号，全局唯一。
- **字段类型**：int / long / double / boolean / enum / 日期 / string / 引用（存目标 OID）/ 以上类型的集合（多值）。
- **Schema**：每类有字段定义和版本号。加字段旧段不重写，缺失列读为 null；删字段合并时丢弃。
- **null**：每列一个 null bitmap。

## 3. 目录结构
```
db/
├─ commit_N           当前段列表 + 已持久化的 WAL 序号
├─ schema/            各类 schema 及历史版本
├─ wal/               写前日志
└─ classes/<类名>/
   ├─ seg_X.seg       不可变段文件
   └─ seg_X_G.del     删除 bitmap（第 G 代，Roaring 序列化）
```

## 4. 段文件格式
一个段 = 一个文件 = 一批同类对象。段内对象**按 OID 排序后分配 docid（0..N-1，int）**。

```
seg_X.seg
├─ OID 列      docid → OID，有序 long 数组
├─ 字段列（每字段一个）
│   ├─ dict    有序去重值，下标即 ordinal
│   ├─ ord     docid → ordinal，位压缩
│   ├─ post    ordinal → docid 集合（Roaring 序列化，依次拼接）+ 偏移表
│   └─ null    null 的 docid 集合（Roaring）
├─ 多值列      docid → [ordinal...] 的偏移表 + 数据区
├─ 行区(可选)  docid → 整对象序列化字节
└─ footer      schema 版本、对象数、各区偏移、每列 min/max/基数
```

**编码规则**
- 数值字典：定长有序数组，二分查找。数值列也可省略 dict，ord 直接存保序编码值（double 转可排序 long）。
- 字符串字典：每 64 个一块，块内前缀压缩；块首值索引常驻内存。
- ord：`bits = ceil(log2(基数))`，`位置 = docid × bits`，O(1) 随机访问；文件尾留 8 字节填充。
- 超高基数列（如 UUID）可不写 post，只靠 ord 扫描。

**核心性质**：字典有序 ⇒ ordinal 顺序 = 值顺序。过滤、排序、分组全部转为整数运算和 bitmap 运算。

## 5. RoaringBitmap 的使用
| 位置 | 类型 | 用途 |
|---|---|---|
| memtable 倒排 | `RoaringBitmap`（可变） | 值 → docid 集合 |
| 段 post / null | `ImmutableRoaringBitmap` | 直接包装映射的 ByteBuffer，零反序列化 |
| 删除文件 | 序列化 Roaring | 每段删除的 docid |
| 查询中间结果 | `RoaringBitmap` | 交、并、差，`getCardinality()` 估算选择性 |

- 读取：`mappedFile.slice(off, len)` 得到 ByteBuffer → `new ImmutableRoaringBitmap(buf)`。
- docid 为 int，使用 32 位版本即可；单个 bitmap 不超过 2GB，且写入时保证不跨映射块边界（见第 12 节）。
- 刷盘前对可变 bitmap 调用 `runOptimize()` 以启用 run 容器压缩。

## 6. 写入路径
1. 请求追加写 WAL（递增序号），多请求合并一次 fsync（group commit）。
2. 写入该类 memtable：每列 `有序 Map<值, RoaringBitmap>` + `docid → 值`，可直接查询。
3. memtable 达阈值后冻结为只读，新开一个继续写；后台排序生成新段。
4. 写新 commit：临时文件 → fsync → 原子 rename → fsync 目录；删除已覆盖的 WAL。

- **更新**：新版本写入 memtable；旧版本所在段生成新一代 .del（置位）。
- **删除**：仅置删除位。
- **单字段高频更新（可选）**：列覆盖层文件（docid → 新值），读时覆盖，合并时消化。

## 7. 点查与引用导航
按 memtable → 新段 → 旧段依次查找：footer 的 OID min/max 过滤 → 有序 OID 列二分 → 检查删除位。首个命中即最新版本。无需独立全局索引。

## 8. 段合并
- 分层合并：大小相近的段若干合一。
- 流程：字典多路归并 → 生成旧 ord → 新 ord 映射 → 按 OID 顺序重写 ord / post，跳过已删除对象。
- 每类段数设上限；合并跟不上时对写入限速。
- 新段经 commit 生效；旧段引用计数归零后删除。

## 9. 查询表示
```
Query {
  class
  filter:    And | Or | Not | Eq | In | Range | IsNull | StartsWith | Contains | RefQuery
  sort:      [字段, 升/降]...
  offset, limit
  projection:[字段]...
  groupBy:   [字段]...,  aggs: count | sum | min | max | avg
}
```

## 10. 查询执行
每个段（含 memtable）独立执行，结果再跨段合并。

**过滤 → 候选 docid（RoaringBitmap）**
- Eq / In：字典二分得 ordinal → 取 post。
- Range / StartsWith：字典二分得 ordinal 区间；区间小则 OR 对应 post，区间大则扫 ord 数组比较整数。
- Not：`[0, N)` 全集 ANDNOT。
- 多条件按选择性（bitmap 基数、区间大小）从小到大计算。
- 最后 ANDNOT 删除 bitmap。

**排序**
- 单键 + limit：按 ordinal 升/降序遍历 post，与候选集求交，凑够即停。
- 多键：依次比较各键 ordinal。
- 跨段：各段有序流按实际值 k 路归并。

**分组聚合**
- 分组键 ordinal 作数组下标累加；多键组合为单个整数键；组数过多退回哈希表。
- 仅 count 时可直接用 `and(post, 候选).getCardinality()`。
- 跨段：ordinal 转回实际值后按值合并。

**引用条件**（如“地址城市为悉尼的人”）
对目标类执行子查询得 OID 集合 → 在源类引用列字典中查对应 ordinal → OR 其 post。引用列倒排即反向引用索引。

**投影与物化**
只读所需列；完整对象优先读行区，否则按 docid 逐列拼装。

## 11. 并发与一致性
- 单写线程处理全部写入，无写写冲突。
- 查询开始获取快照（commit 段列表 + memtable 只读视图），段引用计数 +1，结束释放。
- 读全程无锁；提供快照隔离 + 原子批量提交。

## 12. 读取与恢复
**统一读取接口**，屏蔽 JDK 版本差异：
```java
interface MappedFile extends AutoCloseable {
    long getLong(long pos);
    int  getInt(long pos);
    byte getByte(long pos);
    ByteBuffer slice(long pos, int len);   // 供 Roaring / 字典块使用
}
```

**多版本 JAR（Multi-Release JAR）**
| 运行环境 | 实现 | 解除映射 |
|---|---|---|
| JDK 17–21（主代码） | `MappedByteBuffer` 分块映射 | `sun.misc.Unsafe.invokeCleaner`（需 `jdk.unsupported`），失败则等 GC |
| JDK 22+（`META-INF/versions/22/`） | `MemorySegment` + `Arena.ofShared()` | `arena.close()` |

JVM 运行时自动选择实现，上层代码无感知。

**MappedByteBuffer 实现要点（17–21）**
- 单 buffer 上限 2GB，按 1GB 分块映射：`块号 = pos >>> 30`，`块内偏移 = pos & (1<<30)-1`。
- 跨块边界的多字节读取拆开拼接（概率极低，走慢路径）。
- **写入对齐规则**：每个 Roaring bitmap、字典块写入前若会跨 1GB 边界，先填充到边界，保证 `slice` 总能返回单块内的完整切片。
- 简化选项：将段目标大小限制在 1.5GB 以内，则每段只需一个 buffer，无需分块逻辑。
- 所有 buffer 显式设置 `ByteOrder.LITTLE_ENDIAN`，文件格式与平台无关。

**通用**
- 页缓存交给 OS；常驻内存：footer、字典块索引、post 偏移表。
- 段引用计数归零时调用 `close()` 解除映射，再删除文件。
- 启动：读最新 commit 加载段 → 从记录的 WAL 序号重放，重建 memtable。

**JDK 17 兼容性检查**
- RoaringBitmap 基于 ByteBuffer，无影响。
- Vector API 在 17 为孵化版，不使用，采用标量实现。
- 目录 fsync（`FileChannel.open(dir, READ).force(true)`，Linux）、`ATOMIC_MOVE`、record / sealed class 均可用。

## 13. 可调参数
memtable 大小、段目标大小、合并因子、每类段数上限、字典块大小、是否启用行区、哪些列建 post。

## 14. 开发顺序
1. `MappedFile` 两套实现 + 位压缩数组 + 字典（含单元测试，17 与 22+ 均跑）
2. 单段写入 / 读取，跑通 filter、order、group
3. WAL + memtable + commit + 恢复
4. 更新与删除
5. 段合并
6. 查询优化（选择性排序、Top-N、引用查询）
