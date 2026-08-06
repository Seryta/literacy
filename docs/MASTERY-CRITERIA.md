# 掌握标准与证据模型

本文档定义"学会一个字"的分层标准和存储模型。

---

## 1. 能力维度

一个字的学习分为 4 个独立的能力维度：

| 维度 | 含义 | 检测方式 |
|------|------|---------|
| **识别 (recognize)** | 看到字能认出、能说出含义 | 看字读音 / 听音选字 |
| **书写 (write)** | 能独立写出字 | 独立默写 / 听写 |
| **理解 (understand)** | 能解释字义、能组词 | 口头解释 / 组词 |
| **应用 (apply)** | 能在真实场景中使用 | 造句 / 读句子 / 签名 |

4 个维度**独立评价**："会认不会写"、"会写不会解释"要能分别表达。

## 2. 掌握等级

每个维度 4 个等级：

| 等级 | 标准 | 对间隔重复的影响 |
|------|------|----------------|
| **未学 (0)** | 尚未接触 | — |
| **学习中 (1)** | 在指导下能完成（L3-L4 提示） | 刚学完当天复习 3 次 |
| **初步掌握 (2)** | 偶尔需要提示（L1-L2 提示） | 1-3 天后复习 |
| **稳定掌握 (3)** | 不需提示能独立完成（L0） | 7-14-30 天递增 |
| **熟练 (4)** | 快速、自信、能教别人 | 90 天后复习 |

### 升级规则

```
学习中 → 初步掌握：连续 2 次在 L1-L2 提示下成功
初步掌握 → 稳定掌握：连续 2 次 L0 独立成功
稳定掌握 → 熟练：间隔复习中连续 3 次无障碍通过
```

### 降级规则

```
任何等级 → 降一级：复习时出错且本次提示 ≥ L3（每轮独立判定，无"连续 N 次"累计条件）
```

> 实现口径：`MasteryAdjudicator.adjudicate`——每次复习轮出错且本次提示等级 ≥ L3 即降一级
> （`max(0, 当前等级 - 1)`）；非复习轮出错不降级。早期草案的"连续 2 次需要提示才降"已废弃。

### 执行归属（本地规则引擎）

升级/降级由**本地规则引擎**计算，Agent 不参与裁决（与阶段迁移同原则，见 `AGENT-PROTOCOL.md` §6.4）：

- 触发点：
  - `record_result` 事务内：按本次尝试的 `phase` / `exercise_type` 更新对应维度的等级
  - 复习轮结果到达时（`WritingEvaluated` / 带判题结果的 `VoiceInput`）：按复习结果更新
- 幂等：同一 `idempotency_key` 只计算一次，不重复累加连续计数
- 计数承载：`characters.streak_<dim>_success` / `streak_<dim>_errors`（dim ∈ recognize/write/understand/apply，P1-17）
  —— 每维度独立记录连续成功/失败次数，目标维度一次尝试后一个递增、另一个清零。
  **语义（P1-17 澄清）：连续计数按「目标维度」独立累计**——升级/降级要求的是**该维度自身**的连续成功/失败次数，
  识别维度的连续成功不能被书写/理解维度"借用"（已实现：schema 为 per-dimension streak，8 列）。
  v2→v3 迁移：旧单对 streak 按「掌握等级严格最高的维度」承接，无锚点/平局重新初始化。
- 裁决示例：
  - 升级：目标维度最近连续 2 次成功且提示等级 ≤ L2（学习 → 初步掌握）；L0 独立成功 2 次（初步掌握 → 稳定掌握）；复习轮无障碍 3 次（稳定掌握 → 熟练）
  - 降级：复习轮出错且本次提示 ≥ L3 → 降一级（每轮独立判定；无"连续 N 次"累计规则）

## 3. 字的整体掌握状态

4 个维度的等级组合决定了字的整体状态：

| 状态 | 识别 | 书写 | 理解 | 应用 |
|------|------|------|------|------|
| new | 0 | 0 | 0 | 0 |
| learning | 1-2 | 0-1 | 0-1 | 0 |
| reviewing | 2-3 | 1-2 | 1-2 | 0-1 |
| mastered | 3-4 | 2-4 | 2-4 | 1-4 |
| fully_mastered | 4 | 4 | 4 | 4 |

### 整体状态转换

```
new → learning：至少识别维度达到等级 1
learning → reviewing：至少识别+书写维度达到等级 2
reviewing → mastered：至少识别+书写达到等级 3，且理解 ≥ 2
mastered → fully_mastered：4 个维度全部等级 4
```

## 4. 阶段成功条件的重新定义

之前协议中"用户做了尝试就算成功"的阶段，按新标准重新定性：

| 阶段 | 类型 | 最低通过标准 | 掌握意义 |
|------|------|------------|---------|
| introduce | 教学流程 | 自动通过 | 不代表掌握 |
| recognize | 教学流程 | 正确认出或请求拼音 | 对识别的"学习中"有帮助 |
| demonstrate | 教学流程 | 自动通过 | 不代表掌握 |
| guided_write | 教学流程 | 所有笔画跟写完成 | 对书写的"学习中"有帮助 |
| independent_write | **掌握检测点** | 识写并进：L1-L2 提示下完成 → 书写等级 1，L0 完成 → 书写等级 2；识主写辅：听音选字正确 → 识别等级；识读优先：选字填空正确 → 识别等级 | 直接影响对应维度掌握等级 |
| explain | 教学流程 | 尝试表达即可 | 对理解的"学习中"有帮助 |
| sentence | 教学流程 | 说出句子即可 | 对应用的"学习中"有帮助 |
| record | — | 记录结果 | 不判对错 |

**只有 `independent_write` 是硬性掌握检测点。** 其他阶段是教学流程占位——它们为掌握创造机会，但不过就是不过。真正的"会不会"在 `independent_write` 和间隔复习中体现。

**recognize 的赋值**：认对（或主动请求拼音后正确读出）→ 识别维度赋等级 1（学习中）；认错不赋值、不落库（单次），连续错误走降难流程（`TEACHING-STRATEGY.md` §2.2）。

> **衔接说明**：§4 中"L1-L2 提示下完成 → 书写等级 1"是**单次尝试的等级赋值**，不等同于 §2 等级表中"初步掌握 (2)：偶尔需要提示（L1-L2）"的能力画像。升级需满足 §2 连续次数规则（连续 2 次 L1-L2 成功 → 初步掌握）；等级表的"偶尔需要提示"描述的是稳定状态。

## 5. 证据模型更新

### characters 表扩展

```sql
CREATE TABLE characters (
    char TEXT PRIMARY KEY,
    pinyin TEXT NOT NULL,
    -- 分维度掌握等级
    mastery_recognize INTEGER DEFAULT 0,   -- 0-4
    mastery_write INTEGER DEFAULT 0,       -- 0-4
    mastery_understand INTEGER DEFAULT 0,  -- 0-4
    mastery_apply INTEGER DEFAULT 0,       -- 0-4
    -- 整体状态
    status TEXT NOT NULL DEFAULT 'new',
    -- SM-2 参数（按最弱的维度计算）
    ease_factor REAL DEFAULT 2.5,
    interval_days INTEGER DEFAULT 0,
    last_review TEXT,
    next_review TEXT,
    -- 当前提示等级（跨 session 记住）
    current_prompt_level INTEGER DEFAULT 3,   -- 取值 0-6，对应 TEACHING-STRATEGY 降难矩阵 L0-L6
    -- 掌握等级裁决连续计数（P1-17：每维度独立，目标维度一次尝试后一个递增、另一个清零）
    streak_recognize_success INTEGER DEFAULT 0,
    streak_recognize_errors INTEGER DEFAULT 0,
    streak_write_success INTEGER DEFAULT 0,
    streak_write_errors INTEGER DEFAULT 0,
    streak_understand_success INTEGER DEFAULT 0,
    streak_understand_errors INTEGER DEFAULT 0,
    streak_apply_success INTEGER DEFAULT 0,
    streak_apply_errors INTEGER DEFAULT 0,
    -- 错误记录
    common_mistakes TEXT,
    -- 元数据
    introduced_at TEXT,
    source TEXT
);
```

### session_character_results 不变

已有 `phase` 和 `score` 可以映射到维度。

### 聚合查询示例

```sql
-- "能认不能写"的字
SELECT char FROM characters 
WHERE mastery_recognize >= 3 AND mastery_write <= 1;

-- "会写但不会解释"的字  
SELECT char FROM characters
WHERE mastery_write >= 3 AND mastery_understand <= 1;
```

### "正确率"口径（统一定义）

新 schema 已废弃 `correct_count / attempt_count`，正确率由 `session_character_results` 聚合推导：

- 取该字**最近 5 次**有判分的尝试（`score` 非空）
- `score >= 0.6` 计为正确；`score` 为空的尝试（教学流程占位，如 explain/sentence 未判对错）不计入分子分母
- 形近字触发（`RESEARCH-EXERCISES.md`）与复习策略（`SYSTEM-PROMPT.md`）中的"正确率 < 60%"均按此口径

---

## 6. 与间隔重复的联动

- 复习排期取**4 个维度中最弱的等级**决定 `ease_factor` 和 `interval_days`
- 复习时优先检测最弱维度（如果书写弱于识别，复习时优先听写而非看字读音）
- 名字字的间隔比普通字短 30%（因为与身份认同相关，遗忘代价更大）

## 7. 签字达标标准（姓名 P0 专属）

### 7.1 目标

独立、无提示、一次完成地写出完整姓名，达到"可签在正式文件上"的程度。

### 7.2 判定规则

- 场景：签名区（非米字格，允许自然书写）
- 条件（全部满足）：
  1. 覆盖姓名全部字，无提示
  2. 一次完成（不涂改重写）
  3. 他人可辨认：本地书写评估以"整体可辨认"为准，阈值放宽（允许连笔与适度草化），不套用单字米字格的笔画偏差阈值
  4. 连续 2 次独立签名成功
- 判定由本地完成；结果写入 `name_plan.signing_ready`

### 7.3 达标后的处理

- `name_plan.signing_ready = true`，姓名 P0 完成，进入下一字包
- 姓名仍按间隔重复巩固，间隔比普通字短 30%（见 §6）
- 用户可随时通过语音唤回签名练习

### 7.4 与难字拆解的衔接

签名前若整字写不出，按 [`TEACHING-STRATEGY.md`](./TEACHING-STRATEGY.md) 名字难字拆解规则拆部件教学，再回到整字签名；拆解完成标准仍是整字独立写出。
