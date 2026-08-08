# Agent 运行协议

本文档确定 Agent 与 App 之间的交互协议。不涉及 UI 实现、数据库细节或 Provider 适配方式。

---

## 1. 事件 → LLM 调用映射

以下事件触发一次 LLM turn，其余事件由本地处理：

| 事件 | 触发 LLM？ | 说明 |
|---|---|---|
| `SessionStarted` | ✅ | 首次 turn，Agent 根据 `session_brief` 打招呼 |
| `VoiceInput` | ✅ | 用户语音已转文字 |
| `HelpRequested` | ✅ | 用户按了"帮助"按钮 |
| `SkipRequested` | ✅ | 用户按了"跳过"按钮 |
| `WritingEvaluated` | ✅ | 本地评估已完成，Agent 根据结构化结果决定下一步 |
| `ButtonTapped` | ✅ | 按钮动作（如"显示拼音"） |
| `CharacterCompleted` | ✅ | 当前字完成后，Agent 决定下一字、复习或结束 |
| `EndRequested` | ✅ | 最后一轮 LLM turn，Agent 生成告别和结构化总结 |
| `ConfusableDetected` | ✅ | 本地检测到形近字混淆（识别/书写），Agent 决定是否插入辨析练习 |
| `IdleTimeout` | ✅ | 等待点超时未响应，Agent 给出关怀话术或降难 |
| `RecognitionRepeatedFailures` | ✅ | 连续多次 STT 失败，Agent 降低交互难度或切换通道 |

| 事件 | 触发 LLM？ | 说明 |
|---|---|---|
| `PauseRequested` | ❌ | 本地暂停，不调 LLM；恢复约定见 §1 末"暂停与恢复" |
| `StrokeFinished` | ❌ | 本地书写已完成，先做评估，再决定是否产生 `WritingEvaluated` |
| `RecognitionLowConfidence` | ❌ | 本地澄清提示，不调 LLM |
| `EvaluationLowConfidence` | ❌ | 本地澄清提示，不调 LLM |
| `ScreenChanged` | ❌ | 信息记录 |
| `TtsCompleted` | ❌ | 信号事件；如本轮预约了 `listen`，则此时才真正开麦 |
| `NetworkUnavailable` | ❌ | Android 层拦截，不到达 Agent turn |
| `SessionEnded` | ❌ | Session 已原子化完成，仅用于 UI 和统计收尾 |

新增事件的 payload 约定：

| 事件 | payload 至少包含 |
|------|----------------|
| `ConfusableDetected` | `char`（原字）、`confused_char`（混淆字）、`trigger`（recognition / writing / user_reported / agent_judged） |
| `IdleTimeout` | `waiting_for`（voice / writing / button）、`idle_seconds` |
| `RecognitionRepeatedFailures` | `failure_count`、`last_partial_text` |

### 暂停与恢复

- `PauseRequested`：本地暂停，不调 LLM；当前 `lesson_state` / `ui_state` 保留
- 恢复：用户点击"继续"或语音说"我回来了" → 产生 `ButtonTapped`（`action=resume`）触发 LLM
- 恢复 turn 注入暂停前的 `lesson_state`（含阶段与 `allowed_actions`），从暂停时阶段继续，不重新走 `introduce`
- 跨 session 的恢复由下次启动 `today_brief` 承接（见 `SESSION-LIFECYCLE.md`）

## 2. LLM 调用输入结构

每次调用时发送的最小必要教学上下文。跨 session brief、学习者档案、姓名计划、复习队列及完整事件历史均只在本机使用，不进入 Provider 请求：

```
[System Prompt（固定部分，system role）]
[工具列表]
<teaching_context>      # 当前字、教学阶段、允许动作、脱敏本地评估结果
[event message]         # 当前回答文字或无身份信息的教学事件
```

所有上下文由 App 每次 turn 重构建。Agent 不自己维护对话历史；本机根据事件和工具结果选择白名单字段注入。

用户原始输入、STT 转写、按钮文本、自由文本示例等用户来源内容，不直接拼接进固定 system prompt。它们必须满足以下之一：
- 作为单独的 user-role 消息发送
- 作为严格转义/编码后的结构化字段注入

本地工具执行仍以 phase、capability、allowed_actions 和当前状态版本为准，不因用户输入内容改变裁决逻辑。

## 3. LLM 输出结构

### 3.1 格式

```json
{
  "text": "要朗读的教学语言文本",
  "toolCalls": [
    { "name": "show_character", "arguments": { "char": "家", "revealStrokes": 3 } }
  ]
}
```

### 3.2 规则

- `text` 必填。即使只做 UI 操作，也要说一句话（如"好的，我们看下一笔"）
- `text` 由 App 自动 TTS 朗读，不通过 `speak` 工具
- `toolCalls` 可选。为空时，本轮只有语音回复
- 工具按数组顺序依次执行
- 同一 turn 内最多 3 个 toolCall；超出部分由本地截断（只执行前 3 个），被截断的调用注入 warning 到下一 turn 上下文（与 §10 越界内容处理一致）

## 4. 工具执行顺序

```
LLM 返回 toolCalls
  → 本地校验每个 toolCall 的参数和合法性
  → 按数组顺序执行
  → 收集结构化结果
  → 等待下一个外部事件
```

首版约束：每个外部事件只触发一次 LLM turn，不引入内部 continuation。

- LLM 返回的同步 UI 工具只更新本地界面，不会再次立即调用 LLM
- 需要 LLM 再次决策时，必须由本地生成新的外部事件
- `StrokeFinished` 后先由本地完成书写评估，再生成 `WritingEvaluated`
- `listen` 不立即开麦，只表示"预约下一次语音输入"；App 会在本轮所有 TTS 播放完成后再打开麦克风
- 用户解释/造句（`VoiceInput`）的评估由 Agent 在同一 turn 内联完成：`text` 中给出具体反馈，结论（score / issues）通过 `record_result` 落库；不再通过独立评估工具
- `evaluate_writing`（复评请求）由本地重新评估最近一次书写，结果作为同步 tool result 注入下一 turn；复评不重新触发 `WritingEvaluated`，也不重复参与掌握等级裁决（裁决只消费本地首次评估产生的结果）

等待点只有三类：
- `listen`：TTS 完成后开麦，等待 `VoiceInput`
- 用户书写：等待 `StrokeFinished`
- 结束流程：等待 App 完成 session 原子化提交

### 练习模式工具（首版仅保留）

| 工具 | 输入 | 用途 |
|------|------|------|
| `show_options` | `exercise_id: String, prompt: String` | 展示本地题库中的多选题（识别兜底 / STT 失败兜底） |
| `show_sentence` | `sentence_text: String` | 展示句子文本（读句子练习 / 选字填空题干） |
| `compare_characters` | `char_a: String, char_b: String` | 并列展示两个形近字，供辨析引导 |

练习工具的同步行为：
- `show_options` 只负责引用 App 预先准备好的题目；正确答案和选项由 App 本地持有
- 选择题展示后等待用户点击选项或语音回答 → App 本地判题 → 结果作为下一 turn 的 `ButtonTapped` 或 `VoiceInput` 事件注入
- `show_sentence` / `compare_characters` 与 `show_options` 同属同步 UI 工具：只更新本地界面，不触发新的 LLM turn
- `compare_characters` 展示的形近字对以字库 `confusable_with` 为准，Agent 只从其中选择并引导辨析

与练习相关的事件 payload 约定：
- `ButtonTapped` 选择题结果至少包含：`exercise_id`、`selected_option_id`、`is_correct`、`exercise_type`
- `VoiceInput` 若来源于 `show_options` 的语音作答，至少包含：`exercise_id`、`normalized_option_id`、`is_correct`、`exercise_type`
- `show_options` 绑定当前尝试的 `idempotency_key`，确保一次题目尝试只记一次结果

## 5. 何时等待用户 vs 自动推进

| 场景 | 行为 |
|---|---|
| Agent 说了一句话 + 无 toolCall | 等待用户动作；不自动开麦 |
| Agent 调了 `show_character` + `highlight_stroke` | 等待 `StrokeFinished` 事件 |
| Agent 调了 `listen` | 预约开麦；待当前 TTS 播放完成后开始监听，等待 `VoiceInput` |
| Agent 调了 `advance_phase` | 本地迁移到下一阶段，并等待下一外部事件 |
| Agent 调了 `complete_character` | 本地完成当前字并产生 `CharacterCompleted` 作为下一 turn 事件 |
| Agent 调了 `end_session` | App 用本轮 text + 结构化总结原子化完成 session，不再触发 LLM |
| TTS 播放完毕 + 上次预约了 `listen` | 此时才真正开麦 |
| TTS 播放完毕 + 上次未预约 `listen` | 不自动开麦，等待按钮或其他事件 |

## 6. 阶段迁移规则

### 6.1 单字教学阶段序列

```
1. introduce     → 展示字形和场景
2. recognize     → 认读
3. demonstrate   → 语音示范 + 拼音辅助
4. guided_write  → 逐笔画跟写
5. independent_write → 不看提示独立写
6. explain       → 费曼解释（可选）
7. sentence      → 造句（可选）
8. record        → 记录结果
9. decide        → 下一字 / 复习 / 结束
```

### 6.2 迁移控制

- 本地持有 canonical phase
- 每次 turn 时，`<lesson_state>` 中包含 `allowed_actions`，例如：`["advance_phase", "repeat", "skip_character", "start_review", "complete_character"]`
- Agent 只能从允许的动作中选择，并通过课程控制类 toolCall 表达“动作请求”，本地负责裁决与落地：
  - `advance_phase`：调用 `advance_phase`。本地验证阶段成功条件，允许则迁移到下一阶段，拒绝则保持在当前阶段
  - `complete_character`：调用 `complete_character`。仅在整字完成后使用，本地产生 `CharacterCompleted` 事件
  - `skip_character`：调用 `skip_character`（含原因）。本地记录跳过原因并迁移；跳过的字仍必须 `record_result`（`phase=skip`、`score=null`、跳过原因写入 `issues`，如 `["too_hard"]`）
  - `start_review`：调用 `start_review`。本地切换到复习模式
  - `next`：调用 `next`（仅复习模式可用）。本地切换到下一个复习字；复习队列清空时，`next` 由本地拒绝并保持当前复习字，等待 Agent 决定结束或返回主线
  - `repeat`：不调用上述控制工具，留在当前阶段继续教学

本地在执行 toolCall 前校验该动作是否在 `allowed_actions` 内；不在则拒绝并保持在当前阶段。
`end_session` 不依赖 `allowed_actions`，它是全局始终允许的结束请求。

### 6.3 阶段成功条件（本地判定）

| 阶段 | 成功条件 |
|---|---|
| introduce | 已展示（自动通过） |
| recognize | 用户正确认出 或 主动请求看拼音 |
| demonstrate | 已示范（自动通过） |
| guided_write | 所有笔画跟写完成 |
| independent_write | 识写并进：完成书写且偏差在阈值内；识主写辅：听音选字正确；识读优先：选字填空正确 |
| explain | 用户做了尝试（不判对错） |
| sentence | 用户说了句子（不判对错） |
| record | 已记录（自动通过） |
| decide | Agent 做出决策（自动通过） |

`explain` 和 `sentence` 对无法口语表达的用户可以跳过。

学习路径（`profile.learning_path`：`write_parallel` / `read_primary` / `read_only`）影响独立书写阶段的检测方式与成功条件；用户可在任何 session 中语音切换路径（"今天手不方便，不写字了"）。路径默认值见 [`TEACHING-STRATEGY.md`](./TEACHING-STRATEGY.md) §3.2。

### 6.4 掌握等级裁决（本地规则引擎）

4 维度掌握等级（0-4）的升级/降级由本地规则引擎计算，Agent 不参与裁决；规则定义见 [`MASTERY-CRITERIA.md`](./MASTERY-CRITERIA.md)。

- 触发点：
  - `record_result` 事务内：按本次尝试的 `phase` / `exercise_type` 更新对应维度的等级
  - 复习轮结果到达时（`WritingEvaluated` / 带判题结果的 `VoiceInput`）：按复习结果更新
- 幂等：同一 `idempotency_key` 只计算一次，不重复累加连续计数
- 计数承载：`characters.streak_<dim>_success` / `streak_<dim>_errors`（P1-17：每维度独立，目标维度一次尝试后一个递增、另一个清零）
- 裁决输入：维度、本次结果（对/错）、`prompt_level`、是否复习轮
- 名字字特殊规则：复习间隔比普通字短 30%（身份认同相关）

### 6.5 复习模式

- 进入：`start_review`（本地校验 `review_queue` 非空）
- 阶段序列：`recall`（先回忆，不展示答案）→ `assess`（听音选字 / 听写检测）→ `reinforce`（对出错字再学习）→ `next`（下一复习字）
- `allowed_actions` 变化：复习模式下 `advance_phase` / `complete_character` 不适用，推进复习字由 `next` 表达；`start_review` / `end_session` 始终允许
- 退出：复习队列处理完毕 → Agent 决定结束 session 或返回主线；用户随时可要求返回主线

## 7. 幂等落库规则

### 7.1 `record_result` 调用

```json
{
  "name": "record_result",
  "arguments": {
    "char": "家",
    "result": {
      "phase": "independent_write",
      "exercise_type": "dictation",
      "score": 0.85,
      "issues": ["撇起笔偏左"],
      "prompt_level": "hint",
      "idempotency_key": "550e8400-e29b-41d4-a716-446655440000"
    }
  }
}
```

- `idempotency_key` 由 App 为每次真实尝试生成唯一 UUID，并注入 `<lesson_state>` 中
- 同一 key 的 `record_result` 重复调用不重复计数
- `exercise_type` 用于区分具体练习形态；普通状态机推进可为空
- 只有在 `session_character_results` 成功插入后，才允许更新 `characters` 聚合数据
- `session_character_results` 插入与 `characters` 聚合更新必须处于同一事务中
- 学习结果立即落库，不等 session 结束

### 7.2 Session 结束

唯一结束流程：

1. 用户点击结束、疲劳信号、或本地流程判断结束时，产生 `EndRequested`
2. LLM 收到 `EndRequested` 后，基于 `<current_session_results>` 生成告别文本，并通过 `end_session` 提交结构化总结
3. 如果 Provider 在 `EndRequested` 时失败，App 使用本地兜底话术和当前 session 摘要完成结束，不阻塞用户退出
4. App 在单一事务中完成 `sessions` 更新、`name_plan` 更新和必要统计
5. 完成后发出 `SessionEnded` 供 UI 收尾；`SessionEnded` 不再触发 LLM

`end_session` 的结构化参数至少应包含：
- `highlights`
- `struggles`
- `name_plan_progress`

### 7.3 异常退出

- Session 启动时立即写入 `sessions` 记录（状态 = `active`）
- 下次启动时检测到上次 `active` 记录 → 标记为 `aborted`
- 不丢失已通过 `record_result` 落库的学习数据

## 8. 证据模型

在 `characters` 表之外增加最小记录表：

```sql
CREATE TABLE session_character_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL,
    char TEXT NOT NULL,
    phase TEXT NOT NULL,          -- recognize / guided_write / independent_write / explain / sentence / assess / signature / skip
    exercise_type TEXT,           -- dictation / audio_choice / signature / ...
    score REAL,                   -- 0.0-1.0 或 NULL
    issues TEXT,                  -- JSON 数组
    prompt_level TEXT,            -- none / hint / full_demo
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id)
);
```

这样：
- `session_brief` 能生成 "上次学了张、建，独立书写正确率 75%"
- 能区分不同能力类型的表现
- 幂等 key 防止重复

## 9. 隐私边界

- 学习者姓名、称呼、姓名计划、完整进度、复习队列和会话摘要仅在本地 SQLite 存储。
- 发送给 LLM Provider 的白名单仅为：当前字、教学阶段、用户语音转写后的回答文字、脱敏本地评估结果与本轮允许动作。
- 原始音频、原始手写轨迹、姓名、API Key、完整学习档案与任何可识别身份的信息永远不上传 LLM Provider。
- API Key 仅本地安全存储。
- 隐私说明必须区分“本地持久化数据”和“为在线推理发送的最小必要教学上下文”。

## 10. 异常处理

| 场景 | 谁处理 | 行为 |
|---|---|---|
| Provider 不可用 | Android 层（在调用前检测） | 提示"网络不可用"，保留状态，可选结束 session |
| Provider 超时 | Android 层 | 重试一次 → 仍失败则同"不可用" |
| Provider 返回格式错误 | Android 层 | 记录日志，提示用户，可选结束 session |
| 工具调用参数不合法 | Android 层（执行前校验） | 拒绝执行该 toolCall，注入 error result 到下一 turn |
| Agent 请求非法阶段迁移 | Android 层 | 拒绝迁移，保持在当前阶段，不通知 Agent（静默拒绝） |
| Agent 返回越界内容 | Android 层 | 过滤后 TTS，注入警告到下一 turn 的上下文 |

---

## 与 Pi 协议的对应

| Pi | 识字助手 |
|---|---|
| `agent.prompt(userMessage)` | `SessionStarted` / `VoiceInput` 触发 turn |
| `requestAssistantTurn` | LLM Provider → 完整 JSON `{text, toolCalls}` |
| `executeToolCalls` | 本地执行工具，结构化结果注入下一 turn |
| `AgentEvent` 流 (`turn_start`/`turn_end`) | 事件模型 (`StrokeFinished`/`VoiceInput`/等) |
| `steeringQueue` | 用户语音打断（"等一下""换个字"），在下一 turn 以高优先级 voice input 注入 |
| `prepareNextTurn` | 不切换模型（单 provider） |
| `convertToLlm` | App 在每次 turn 前构建结构化上下文块 |
| `transformContext` | 不适用（对话不长，无需裁剪） |
