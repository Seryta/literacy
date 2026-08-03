# Golden Turn 测试用例集

本目录存放**自然语言场景测试用例**（golden turn set）：把 AGENT-PROTOCOL / TEACHING-STRATEGY / MASTERY-CRITERIA 等设计文档中的抽象规则，转化为"事件序列 → 期望行为"的可断言契约，作为后续实现的验收标准与回放框架（状态机单测 + 事件回放）的输入。

## 与设计文档的关系

- **用例是规格的可执行形式**：设计文档描述"应当如何"，用例描述"具体输入 → 具体可断言输出"
- 设计变更 → 同步更新受影响用例；新增场景 → 按模块补用例（ID 递增）
- 写用例时发现的设计留白 → 按 reviews/ 流程记录并修设计文档，不回改用例迁就实现

## 文件组织（按 POC 优先级）

| 文件 | 模块 | 对应 POC 优先级 |
|------|------|----------------|
| T001-agent-protocol.md | 事件 → turn → 工具执行闭环 | POC-1 |
| T002-character-closed-loop.md | 单字教学 9 阶段 + 掌握裁决 | POC-2 |
| T003-voice-interaction.md | listen / TTS / 超时 / 连续失败 / 暂停恢复 | POC-3 |
| T004-review-algorithm.md | 复习模式 + 间隔重复 + 掌握降级 | POC-4 |
| T005-exercise-variants.md | 练习变体（show_options / compare_characters 等） | POC-5 |

## 用例格式模板

每个用例一个小节，含五个区块：

```markdown
## GT-NNN 标题

**模块**：<模块名> | **优先级**：P0/P1/P2 | **覆盖规则**：<文档> §<章节> ...

**前置状态**（YAML）：
- learner_profile / name_plan / lesson_state / review_queue / session_brief / ui_state

**事件序列**（YAML）：一个或多个事件 + payload，支持三类行，**按序交错执行（真实 turn 模型）**：
- `event`：外部事件（VoiceInput / WritingEvaluated / ButtonTapped / TtsCompleted / ...）
- `toolCall` / `toolCalls`：mock LLM 输出的工具调用；可带 `text` 字段（mock 教学语，激活 text 断言）
- `llm_output`：完整 mock LLM 输出（text + toolCalls，GT-014 越界内容场景）
- `provider_failure`：模拟 Provider 失败（GT-011 本地兜底场景）

**期望行为**（YAML）：
- toolCalls：required（必须出现）/ forbidden（禁止出现）/ max（上限 3）
- toolCall_args：关键参数的精确断言
- text：contains / not_contains（关键词语义断言）
- state：阶段 / 模式 / 提示等级变化
- storage：落库断言（characters / session_character_results / name_plan 等）
- local_handling：本地裁决行为（拒绝 / 不触发 LLM / 兜底）
- input_guard：对注入上下文本身的断言（隐私脱敏等）

**备注**：该用例锁定的设计意图 / 边界说明
```

## 断言粒度约定（重要）

| 断言对象 | 粒度 | 原因 |
|---------|------|------|
| `text` | **宽松**：contains / not_contains 关键词，断言教学意图与语气约束，不断言精确句子 | LLM 输出不可控，精确断言导致测试脆弱 |
| `toolCalls` | **精确**：工具名 + 关键参数 | 结构化输出，可精确断言 |
| `state` / `storage` | **精确**：阶段、等级、字段值 | 本地裁决，确定性 |
| `local_handling` | **精确**：是否触发 LLM / 是否拒绝 | 协议层本地行为，确定性 |
| `input_guard` | **精确**：注入内容不含某字段 | 隐私边界是硬约束 |

## 与回放框架的对接

- 每个用例的"前置状态 + 事件序列"是回放输入；"期望行为"是断言集
- **事件序列按时间线交错执行**：事件到达 → LLM 输出（toolCall/llm_output 行）→ 工具执行 → 下一事件；
  `toolCall` 行的 `text` 字段提供 mock 教学语，用于激活 text 语义断言
- 实现阶段：本地裁决逻辑（纯 Kotlin）直接跑事件序列断言 state/storage；LLM 部分用 mock 输出
  验证"工具调用 → 状态变化"链路；真实 provider 输出可录制为 fixture 回放
- 用例文件保持 Markdown（人读）为主，回放框架按本模板解析区块，不做额外格式要求

## 维护约定

- ID 按模块连续编号（GT-001~019 协议、GT-020~039 单字、GT-040~049 语音、GT-050~059 复习、GT-060+ 练习）
- 优先级 P0 = 核心主路径（实现第一天必须过）；P1 = 重要边界；P2 = 边缘/异常
- 新增用例时检查是否与既有用例重复（避免"100 个流水账"）；用例要能锁定一条明确的规则或决策
