# 学习档案存储设计

借鉴 Claude Opus 5 记忆文件系统的目录结构思想：不是一张扁平的表，而是按"主题"组织学习者的数据。

## Opus 5 结构 → 识字助手映射

| Opus 5 | 识字助手 | 存什么 |
|---|---|---|
| `/profile.md` | 学习者档案 | 姓名、称呼、初始水平、偏好 |
| `/topics/<domain>.md` | 每个字的学习记录 | 掌握程度、错误模式、复习排期 |
| `/areas/<name>.md` | 学习目标 | 名字计划、当前课程目标 |
| `/people/<name>.md` | 重要的人 | 学习者想写名字给谁看（家人等） |
| `/preferences.md` | 偏好设置 | 语速、字号、拼音默认值 |

## 存储路径

全部本地 SQLite，不依赖网络存储。

## 表结构

### `profile` — 学习者档案

对应 Opus 5 `/profile.md`：身份信息，3 个月后仍为真的才放这里。

```sql
CREATE TABLE profile (
    id INTEGER PRIMARY KEY DEFAULT 1,  -- 单行
    learner_name TEXT NOT NULL,          -- 真实姓名
    display_name TEXT NOT NULL,          -- 称呼方式（"张阿姨"）
    knows_pinyin INTEGER DEFAULT 0,     -- 是否会拼音
    literacy_level TEXT,                 -- 初始识字水平描述
    learning_path TEXT DEFAULT 'write_parallel',  -- write_parallel / read_primary / read_only，见 TEACHING-STRATEGY.md §3.2
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

### `preferences` — 偏好设置

对应 Opus 5 `/preferences.md`：元反馈和格式偏好。

```sql
CREATE TABLE preferences (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
-- 示例键：voice_speed, font_scale, pinyin_default,
--         provider_type, provider_config_ref
```

### `characters` — 每个字的学习档案

> **Canonical schema**：本文为 `characters` 表的唯一有效定义（已并入 4 维度掌握等级，见 [`MASTERY-CRITERIA.md`](./MASTERY-CRITERIA.md)）。早期版本的 `correct_count` / `attempt_count` / `mastered_at` 字段已废弃，`SESSION-LIFECYCLE.md` 与 `TEACHING-STRATEGY.md` 中的相关引用已同步更新。

对应 Opus 5 `/topics/<domain>.md`：一题一文件。

```sql
CREATE TABLE characters (
    char TEXT PRIMARY KEY,
    pinyin TEXT NOT NULL,
    -- 分维度掌握等级（0-4，见 MASTERY-CRITERIA.md）
    mastery_recognize INTEGER DEFAULT 0,
    mastery_write INTEGER DEFAULT 0,
    mastery_understand INTEGER DEFAULT 0,
    mastery_apply INTEGER DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'new',   -- new/learning/reviewing/mastered/fully_mastered
    ease_factor REAL DEFAULT 2.5,         -- SM-2 算法参数（按最弱维度计算）
    interval_days INTEGER DEFAULT 0,      -- 当前复习间隔
    last_review TEXT,
    next_review TEXT,
    current_prompt_level INTEGER DEFAULT 3, -- 当前提示等级（跨 session 记住）；取值 0-6，对应 TEACHING-STRATEGY 降难矩阵 L0-L6
    streak_recognize_success INTEGER DEFAULT 0,  -- 掌握等级裁决（P1-17）：识别维度连续成功次数
    streak_recognize_errors INTEGER DEFAULT 0,   -- 识别维度连续出错次数
    streak_write_success INTEGER DEFAULT 0,      -- 书写维度连续成功次数
    streak_write_errors INTEGER DEFAULT 0,       -- 书写维度连续出错次数
    streak_understand_success INTEGER DEFAULT 0, -- 理解维度连续成功次数
    streak_understand_errors INTEGER DEFAULT 0,  -- 理解维度连续出错次数
    streak_apply_success INTEGER DEFAULT 0,      -- 应用维度连续成功次数
    streak_apply_errors INTEGER DEFAULT 0,       -- 应用维度连续出错次数
    common_mistakes TEXT,                 -- 常见错误描述（"少写一横"）
    introduced_at TEXT,
    source TEXT                           -- 来源：name_plan / curriculum / user_request
);
```

### `name_plan` — 名字学习计划

以下内容是当前**候选 schema 草案**，用于帮助讨论状态拆分和数据责任边界，不代表 Room entity、DAO、SQL 表名或最终字段名已经定稿。后续实现时允许在不破坏整体设计意图的前提下调整命名和归并方式。

对应 Opus 5 `/areas/<name>.md`：进行中的项目/目标。

```sql
CREATE TABLE name_plan (
    id INTEGER PRIMARY KEY DEFAULT 1,    -- 单行
    full_name TEXT NOT NULL,
    target_chars TEXT NOT NULL,           -- JSON 数组
    priority_mode TEXT DEFAULT 'soft',
    current_stage TEXT,                   -- 当前阶段描述
    recognition_ready INTEGER DEFAULT 0,  -- 能认出
    guided_writing_ready INTEGER DEFAULT 0,-- 有提示能写
    independent_writing_ready INTEGER DEFAULT 0,-- 无提示能写
    signing_ready INTEGER DEFAULT 0,      -- 能签字
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

说明：
- `name_plan` 是名字学习状态的 canonical 数据源，这里强调的是“需要有一个单一真源”，不要求最终代码必须使用同名表或同名字段
- 注入给 Agent 的 `achieved_summary`、`next_milestone` 等可读摘要由应用层根据上述状态派生，不单独存库
- `priority_mode` 默认 `soft`，但保持可配置，避免把“名字优先”写死成唯一学习路径
- `current_stage / recognition_ready / guided_writing_ready / independent_writing_ready / signing_ready` 是当前更偏可读性的候选命名，后续可根据实现改成更合适的结构或枚举

### `people` — 重要的人

同样属于候选存储设计，重点是“是否需要记录关键关系人物及其用途”，而不是现在就锁死表名和字段名。

对应 Opus 5 `/people/<name>.md`：关系上下文。

```sql
CREATE TABLE people (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,                   -- 姓名
    relationship TEXT,                    -- 关系（"女儿"/"老伴"）
    why_important TEXT,                   -- 为什么要给这个人写名字
    created_at TEXT NOT NULL
);
```

这个表是可选的——如果学习者在建档时提到"我想给女儿写贺卡上的名字"，就可以记录在 people 中，后续教学中 Agent 可以引用（"这个'爱'字，以后给女儿写贺卡会用到"）。

### `sessions` — 会话摘要

这里的结构用于说明 session 结束后最少需要沉淀哪些摘要信息；实际实现可以合并、拆表或调整字段命名。

对应 Opus 5 的 "记忆在下一次对话开始时重读" 的机制。

```sql
CREATE TABLE sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    started_at TEXT NOT NULL,
    ended_at TEXT,
    status TEXT NOT NULL DEFAULT 'active',  -- active / completed / aborted
    chars_learned INTEGER DEFAULT 0,
    chars_reviewed INTEGER DEFAULT 0,
    name_plan_progress TEXT,
    highlights TEXT,
    struggles TEXT,
    duration_seconds INTEGER DEFAULT 0
);
```

Session 启动时立即插入 `status='active'` 记录。正常结束更新为 `completed`，下次启动检测到上次 `active` → 标记 `aborted`。

### `session_character_results` — 最小证据表

这里展示的是一份最小证据表草案，目的在于说明“需要可追溯 evidence + 幂等键 + 会话归属”，不是要求后续必须逐字照抄 SQL。

```sql
CREATE TABLE session_character_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    char TEXT NOT NULL,
    phase TEXT NOT NULL,          -- recognize / guided_write / independent_write / explain / sentence / assess / signature / skip
    exercise_type TEXT,           -- dictation / audio_choice / signature / ...
    score REAL,
    issues TEXT,                   -- JSON 数组
    prompt_level TEXT,             -- none / hint / full_demo
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);
```

每条记录对应一次 `record_result` 工具调用。`idempotency_key` 防止重复落库。
`exercise_type` 用于记录具体练习形态；普通阶段推进可为空。
`idempotency_key` 由 App 为每次真实尝试生成唯一 UUID，而不是按小时或阶段名拼接。
只有在证据记录真正插入成功后，才允许更新 `characters` 聚合数据。
证据插入与聚合更新必须放在同一事务内，避免 evidence 成功写入但聚合状态未更新。
此表支持生成 "上次学了张、建，独立书写正确率 75%" 等具体摘要。

如果后续实现中发现：
- `phase` 更适合拆成枚举对象
- `exercise_type` 更适合单独表或 sealed class
- `issues` 更适合结构化 JSON/子表

都可以调整；真正应保持稳定的是这些信息的语义，而不是当前示例里的具体列名。

## 数据流转

```
首启建档
  → INSERT profile, preferences
  → 拆解姓名 → INSERT name_plan
  → 为每个姓名字 INSERT characters (status='new', source='name_plan')

Session 启动
  → INSERT sessions (status='active')

每次 record_result 调用
  → 在单一事务中 INSERT session_character_results（幂等）
  → 在同一事务中 UPDATE characters（即时更新）

Session 正常结束
  → UPDATE sessions (status='completed', highlights, struggles)
  → UPDATE name_plan（如有进展）

Session 异常退出
  → 下次启动检测到上次 sessions.status='active' → 标记 'aborted'
  → 已通过 record_result 落库的数据不丢失
```

## 与 Opus 5 的关键相似点

| 原则 | Opus 5 | 识字助手 |
|---|---|---|
| 先读已有内容再写入 | `memory_read` before `memory_write` | session 开始时读取上次摘要 |
| 只写用户说的 | `[stated]` 标签 | characters 只存学习结果（掌握等级/连续计数/错误模式），不存推理 |
| 按主题分区 | /profile, /topics, /areas, /people | profile, characters, name_plan, people |
| 跨会话持久化 | 每次对话重读记忆文件 | 每次启动读取上次 session 摘要生成 today_brief |
| 持久化数据默认本地 | — | 持久化数据保存在本地 SQLite；在线 Provider 只接收运行时教学上下文 |
