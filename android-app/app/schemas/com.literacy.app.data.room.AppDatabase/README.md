# AppDatabase schema JSON 说明（Room exportSchema）

本目录由 Room KSP 编译器导出（`room.schemaLocation`），提交入库供
`AppDatabaseMigrationTest`（MigrationTestHelper）跑真实 v1→v2 迁移测试。

- `2.json`：**Room 编译器自动导出**（`exportSchema=true`），权威可信。
- `1.json`：**手工推导**（见下），仅在迁移测试中使用。

## 1.json 为手工推导（v1 从未 exportSchema）

`6f8ed99`（feat(android): Room 持久化）首次引入 Room 时**没有配置
`room.schemaLocation`，v1 schema 从未导出过**。`1.json` 是 4b54fde 补导出时手工
写的，推导过程：

1. 取 `git show 6f8ed99:android-app/app/src/main/kotlin/com/literacy/app/data/room/Entities.kt`
   的四张表（characters / sessions / session_character_results / name_plan），
   **逐列交叉验证**：列名、affinity（TEXT/INTEGER/REAL）、notNull、主键
   （characters=char 非自增、sessions=id AUTOINCREMENT、results=id AUTOINCREMENT、
   name_plan=id 非自增）全部对齐实体定义与 Room 默认命名。
2. 以 `2.json` 为模板复制结构，**仅删除** `session_character_results` 的
   `idempotencyKey` 唯一索引（v1 无该索引——它是 v1→v2 迁移才加的，见 `AppDatabase.kt`
   `MIGRATION_1_2`）。其余字段/表与 2.json 完全一致（2.json 本身是编译器从同一批实体
   导出的，与 v1 实体除索引外无差异）。
3. 交叉验证闭环：迁移测试在 v1 库上执行 INSERT（命中全部 NOT NULL 列）——若结构
   推导有误（列缺失/类型不符）INSERT 会抛错；迁移后再用 `runMigrationsAndValidate`
   对 2.json 做全量 schema 校验（`validateDroppedTables=true`）。

## identity hash 机制（依据 room-testing 2.6.1 源码验证）

Room 的 identity hash 自洽链与 1.json 的关系（MigrationTestHelper 流程）：

1. `createDatabase(TEST_DB, 1)`：用 1.json 的**顶层 `identityHash` 字段**（当前为
   占位符 `"v1-no-idempotency-index-placeholder"`）写入 `room_master_table`。
   此步骤只写入、不校验（新建库无校验对象），因此占位符值不会导致失败。
2. `runMigrationsAndValidate(TEST_DB, 2, true, ...)`：跑真实 `MIGRATION_1_2`，
   校验后把 `room_master_table` 的 identity hash **覆写为 2.json 的顶层
   `identityHash`**（`bb43d43ef468757b9a2eb24553826914`）。
3. 测试最后用 `Room.databaseBuilder(...AppDatabase...)` 打开 v2 库：Room 计算当前
   schema（四表 + 唯一索引）的 hash，必须与 `room_master_table` 存储值相等——
   该值是第 2 步写入的 2.json 顶层 hash，而 2.json 是编译器导出，与实体编译产物
   天然一致，故校验通过。

`setupQueries` 中的 `room_master_table` 建表 + `INSERT ... identity_hash` 是 Room
schema JSON 导出格式的组成部分（`DatabaseBundle.buildCreateQueries()` 只执行 entity
create queries，MigrationTestHelper 不执行 setupQueries），保留它仅为与真实导出格式
一致。

## 未来改动注意

- **不要把 1.json 的顶层 identityHash 占位符"修正"成某个未知真实 hash**：由上述流程
  可见该值只在第 1 步写入、第 2 步即被 2.json 的 hash 覆写，从不参与比对——改成一个
  臆造值反而引入"看起来像是真的"的误导。
- **Room 升级时必须复核**：MigrationTestHelper 的 identity hash 写入/覆写流程可能随
  room-testing 版本变化（本文档基于 2.6.1 验证）；升级后重跑
  `AppDatabaseMigrationTest`，若失败先看是否 hash 机制变了，再决定 1.json 是否仍可用。
- **新增 v2→v3 迁移时**：2.json 会被编译器重新导出（hash 会变），1.json 不动；
  若 v1 schema 本身变了（实体增删列），必须重新手工推导 1.json 并更新本说明。
- **v1 脏数据**：P0-1 修复前模型自造 idempotencyKey 可能产生同 key 多行，
  `MIGRATION_1_2` 建唯一索引前先去重（保留最早一行），见 `AppDatabase.kt` 与
  `AppDatabaseMigrationTest.migrate1To2_legacyDuplicateKeys_deduplicatedBeforeIndex`。
