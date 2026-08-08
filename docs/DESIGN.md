# 成人识字 AI 助手 —— 架构设计草案

## 产品定位

面向成人的汉字识字与写字教学软件，Android 手机优先。
核心交互是 AI 语音引导 + 图形界面练习，教学方法以费曼学习法、间隔重复和逐笔画书写引导为主。

当前版本聚焦：
- 识字
- 写字
- 成人日常生活场景
- 标准普通话语音交互

当前版本不聚焦：
- 精细发音评分
- 方言适配
- 离线教学模式
- 完整账号云同步

### 当前阶段前提

- 当前版本按在线产品设计，学习 session 需要网络和有效的 LLM provider 配置
- LLM Agent 是教学循环的核心，参与教学 turn 的决策、反馈和工具调度
- 当前不设计断网后的离线课程降级；网络或 API 不可用时保留当前学习状态，并提示用户重试或结束本次学习
- STT、TTS、手写评估和学习数据仍优先在 Android 端本地处理

## 目标用户与使用场景

- 学习者：希望提升识字和签字能力的成年人
- 协助者：学习者子女、家人或其他帮助建档的人
- 典型第一目标：先学会认写自己的名字，满足签字等现实需要

## 设计原则

- **真实任务优先**：以认出、写出姓名和完成签字等实际能力为目标，不只看 App 内分数
- **成人化与尊重**：不用儿童化语气、素材、奖章或考试式反馈
- **学习者可选择**：允许请求提示、重听、跳过、暂停和结束
- **系统不确定不等于用户不会**：语音或手写识别置信度不足时先澄清，不记录为学习失败
- **反馈具体且克制**：每次优先指出一个最有帮助的问题，并说明已经取得的具体进步

## 交互模式

用户主要通过语音与 AI 交互，配合屏幕上的：
- 米字格
- 笔画灰色引导
- 拼音标注
- 图片联想
- 成人语境例句
- 点读

一次打开 App 的学习过程视为一个 `session`，其中由事件驱动的一次次处理视为 `turn`。

## 核心架构（方案 B：纯 Android 原生）

```text
┌─────────────────────────────────────────┐
│       Android App (Kotlin/Compose)       │
│                                          │
│  ┌──────────┐  ┌──────────┐  ┌────────┐ │
│  │ 本地 STT  │  │ 本地 TTS  │  │手写评估│ │
│  │(语音→文本)│  │(文本→语音)│  │(笔画反馈)│ │
│  └────┬─────┘  └────┬─────┘  └───┬────┘ │
│       │              │            │      │
│  ┌────┴──────────────┴────────────┴────┐ │
│  │            Agent 编排层                │ │
│  │  - Prompt 构建                       │ │
│  │  - 调用 provider 获取 turn 决策       │ │
│  │  - 工具调度                          │ │
│  │  - 学习状态整合                      │ │
│  └────────────────┬────────────────────┘ │
│                   │                      │
│  ┌────────────────┴────────────────────┐ │
│  │        LLM Provider Layer           │ │
│  │  - Provider Adapter                 │ │
│  │  - 完整 JSON / Tool Call 适配        │ │
│  └─────────────────────────────────────┘ │
│                                          │
│  ┌─────────────────────────────────────┐ │
│  │            本地 SQLite               │ │
│  │  - 用户档案                          │ │
│  │  - 课程框架与字库                    │ │
│  │  - 学习进度与复习排期                │ │
│  │  - 会话摘要与偏好                    │ │
│  └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

- 不依赖自建业务服务端，App 通过 provider 适配层接入外部 LLM
- 语音、手写、学习数据尽量本地处理
- 当前版本要求联网，LLM 参与教学循环
- 网络或 API 不可用时不进入离线教学流程
- 后期可选增加同步服务，但不是当前版本前提

## 非 Agent 与 Agent 的职责边界

核心原则：**LLM Agent 负责实时教学，非 Agent 提供可控框架和可靠数据，使 Agent 能在明确边界内因人而异地教学。**

### 非 Agent 负责

这部分是教学“操作系统”，尽量可测试、可控、可复用：

- 用户档案与首启建档
- 姓名拆字与“名字优先计划”生成
- 字库、笔顺、拼音、例句、联想图关键词
- 课程状态机骨架
- 间隔重复算法
- 评估规则与阈值
- UI 页面和可调用工具
- 安全限制、隐私脱敏和本地存储

### Agent 负责

这部分由接入的 LLM provider 完成，回答“当前这一拍怎么教”：

- 当前该教哪个字、哪个阶段
- 当前更适合讲解、示范、跟写还是复习
- 如何根据学习者表现调整节奏
- 如何给出尊重、简洁、成人化的反馈
- 用户需要提示、暂停或换一种方式时如何响应
- 何时切页面、显示拼音、展示例句、进入下一轮

### 一个简化理解

- 非 Agent：定义边界、数据、规则、框架
- Agent：在边界内做实时教学决策

## 首启与建档流程

考虑到学习者可能尚不具备独立配置能力，当前版本允许由协助者完成首启建档。

### 首启流程

1. 进入协助建档页
2. 选择或配置 LLM provider
3. 录入学习者姓名
4. 录入基本偏好和学习起点
5. 生成“名字优先计划”
6. 进入首次学习 session

### 首启采集信息

- 学习者姓名
- 称呼方式
- 是否认识部分常见字
- 是否会拼音
- 语速偏好
- 字体大小偏好
- 是否默认显示拼音
- provider 类型
- provider 凭证或 API Key

### 名字优先计划

详细设计见：
- [`TEACHING-STRATEGY.md`](./TEACHING-STRATEGY.md) — 统一优先级机制，姓名作为 P0 字包
- [`CURRICULUM-DESIGN.md`](./CURRICULUM-DESIGN.md) — 生活字包系统
- [`MASTERY-CRITERIA.md`](./MASTERY-CRITERIA.md) — 签字达标标准

核心规则：
- 姓名学习是统一优先级队列中的一个项目，不是硬编码路径
- 用户可跳过/推迟姓名，随时通过语音唤回
- 签名字包完成后进入下一个生活字包

## 教学主线

详细设计见 [`CURRICULUM-DESIGN.md`](./CURRICULUM-DESIGN.md)。

默认教学起点：`协助建档 → 姓名(P0) → 家庭(P1) → 数字金额(P2) → 地址(P3) → ...`

姓名是默认第一个字包，用户可以跳过或选择其他字包优先。

## 教学策略

详细设计见：
- [`TEACHING-STRATEGY.md`](./TEACHING-STRATEGY.md) — 优先级机制、降难矩阵、路径选择、脚手架撤除
- [`MASTERY-CRITERIA.md`](./MASTERY-CRITERIA.md) — 四维度掌握标准（识别/书写/理解/应用）

## Agent 工具系统

### 在线教学数据边界（初版）

在线 Provider 的每次请求只允许包含：当前字、教学阶段、学习者回答文字与脱敏后的本地评估结果。姓名、录音、原始笔迹、API Key、完整学习档案及跨 session 身份/进度摘要必须留在本机。具体字段白名单以 [`design/prototype/index.html`](../design/prototype/index.html) 顶部“全局产品设计说明 · 初版实现契约”为准。

将 Pi 的 `read/bash/edit/write` 替换为教学工具。这里保留能力边界和设计意图；具体事件映射、输入输出结构、toolCall 顺序、幂等规则，以 [`AGENT-PROTOCOL.md`](./AGENT-PROTOCOL.md) 为准。

### 工具分组

- `UI 控制`
  - `show_character` / `show_pinyin` / `show_image` / `show_example`
  - `show_options` / `show_sentence` / `compare_characters`
  - `highlight_stroke` / `clear_grid` / `navigate_screen` / `set_font_scale`
- `语音`
  - `listen`
  - `pronounce_slowly`
- `评估`
  - `evaluate_writing`（默认本地评估，仅供复评请求）
- `进度`
  - `record_result`
  - `get_review_queue`
  - `get_progress_summary`
  - `get_name_plan`
- `课程控制`
  - `advance_phase`
  - `complete_character`
  - `skip_character`
  - `start_review`
  - `next`（复习模式推进）
  - `end_session`

### 当前约束

- 不提供 `evaluate_pronunciation`，避免当前版本偏离识字主线
- `text` 由 App 自动 TTS 朗读，不再单独设计 `speak` 工具
- `listen` 只负责预约语音输入，真正开麦发生在当前 TTS 播放完成之后
- `show_options` 的结果通过 `ButtonTapped` 或带判题结果的 `VoiceInput` 进入下一 turn
- `record_result` 采用幂等写入，`characters` 在学习过程中即时更新，不等 session 结束
- 控制类工具是否允许执行，由 `lesson_state.allowed_actions`、本地阶段裁决和结束流程约束共同决定

协议细节见：
- [`AGENT-PROTOCOL.md`](./AGENT-PROTOCOL.md)：事件、输入输出结构、toolCall 执行、阶段迁移、异常处理
- [`SYSTEM-PROMPT.md`](./SYSTEM-PROMPT.md)：模型行为约束、输出格式、动态上下文注入

## 课程状态机

为避免 Agent 只会“聊天”，每个字的学习要落在统一状态机内。

### 单字学习阶段（概念层）

当前采用一条稳定的单字教学主线：

- `introduce`：展示字形和使用场景
- `recognize`：认读或回忆
- `demonstrate`：语音示范与必要提示
- `guided_write`：逐笔画跟写
- `independent_write`：不看提示独立写
- `explain`：用自己的话解释字义（可选）
- `sentence`：放进生活句子里使用（可选）
- `record`：记录学习结果
- `decide`：决定进入下一字、复习或结束

这里保留的是教学意图层。canonical phase 名称、允许动作、迁移裁决和成功条件，以 [`AGENT-PROTOCOL.md`](./AGENT-PROTOCOL.md) 为准。

### 状态机作用

- 给 Agent 提供明确教学边界
- 允许 Agent 在协议允许的动作范围内，根据学习目标和用户状态调整节奏
- 用户可随时请求提示、重听、跳过、暂停或结束
- 系统低置信度进入澄清流程，不作为学习错误记录
- 便于测试、回放和本地裁决阶段迁移

## Session / Turn 事件模型

### Session

一次打开 App 的完整学习过程。

### Turn

一次由事件触发的 Agent 决策循环：

`事件输入 -> Prompt 构建 -> LLM provider 决策 -> 工具调用 -> 结果回写 -> 等待下一外部事件`

### 事件类型

当前定义的完整事件集合：

- `SessionStarted`
- `VoiceInput`
- `HelpRequested`
- `PauseRequested`
- `SkipRequested`
- `StrokeFinished`
- `WritingEvaluated`
- `RecognitionLowConfidence`
- `EvaluationLowConfidence`
- `ButtonTapped`
- `ScreenChanged`
- `TtsCompleted`
- `NetworkUnavailable`
- `CharacterCompleted`
- `ConfusableDetected`
- `IdleTimeout`
- `RecognitionRepeatedFailures`
- `EndRequested`
- `SessionEnded`

这比“纯对话消息驱动”更贴近真实 App 交互，也更接近 Pi 的 turn 处理思想。哪些事件触发 LLM turn、各事件的 payload 约定，以 [`AGENT-PROTOCOL.md`](./AGENT-PROTOCOL.md) 为准。

## Agent 循环适配

```text
┌─────────────────────────────────────────────┐
│                识字 Agent 循环                │
│                                              │
│  事件输入（语音/书写/按钮/页面状态）           │
│    │                                         │
│    ▼                                         │
│  System Prompt 构建                          │
│    - 角色人设                                │
│    - lesson_state                            │
│    - ui_state                                │
│    - 学习历史与复习状态                       │
│    - 上一轮工具结果                           │
│    - 可用工具列表                             │
│    - 安全护栏                                 │
│    │                                         │
│    ▼                                         │
│  LLM Provider                                │
│    │                                         │
│    ▼                                         │
│  text -> TTS                                 │
│  toolCall -> 执行 -> 回写结果                 │
│    │                                         │
│    ▼                                         │
│  等待下一外部事件                              │
└─────────────────────────────────────────────┘
```

与 Pi 的关键差异：
- Pi 等待终端输入，这里等待学习事件
- Pi 调用编程工具，这里调用教学工具
- Pi 的 steering queue，这里对应用户语音打断和 UI 操作

## Agent 对界面的感知

每次 LLM 调用前，Prompt 注入当前界面状态：

```xml
<ui_state>
当前屏幕：学习主界面
当前字："家"
米字格状态：已完成 3/10 笔
上一笔反馈：起笔偏左
拼音显示：隐藏
最近一次用户输入："这个字我在门牌上见过"
</ui_state>
```

同时注入教学意图状态，而不是只给 UI：

```xml
<lesson_state>
当前目标：学会“家”
当前阶段：独立书写后反馈
阶段成功条件：结构基本正确，能说出含义
当前限制：不要引入新字，不要展开闲聊
</lesson_state>
```

## System Prompt

完整 System Prompt 见 [`SYSTEM-PROMPT.md`](./SYSTEM-PROMPT.md)，参照 Pi Agent 的结构组织：
- 角色定义 + 产品上下文
- 可用工具（含一行描述，对应 Pi 的 `promptSnippet`）
- 工具使用指南（对应 Pi 的 `promptGuidelines`）
- 教学规则（状态机遵从、支架式教学、提取练习、交叉练习、费曼学习法、间隔重复等）
- 语气准则
- 安全边界
- 动态上下文注入格式

额外融入的学习理论：
- **支架式教学 (Scaffolding)**：根据表现动态调整帮助程度
- **提取练习 (Retrieval Practice)**：先回忆再展示，回忆的努力在强化记忆
- **交叉练习 (Interleaving)**：新旧混合，认读写交替

## 安全护栏

见 [`SYSTEM-PROMPT.md`](./SYSTEM-PROMPT.md) 安全边界部分。

## 存储设计

详细设计见 [`STORAGE-DESIGN.md`](./STORAGE-DESIGN.md)。借鉴 Claude Opus 5 记忆文件系统的目录组织，按主题分区：

| Opus 5 | 识字助手表 | 内容 |
|---|---|---|
| `/profile.md` | `profile` | 身份信息 |
| `/topics/<domain>.md` | `characters` | 每个字的学习档案 |
| `/areas/<name>.md` | `name_plan` | 名字学习目标 |
| `/people/<name>.md` | `people` | 重要的人 |
| `/preferences.md` | `preferences` | 偏好设置 |
| 记忆 listing | `sessions` | 会话摘要，启动时读取 |

## Session 生命周期

详细设计见 [`SESSION-LIFECYCLE.md`](./SESSION-LIFECYCLE.md)。核心流程：

1. **启动刷新**（非 Agent）：读取上次摘要 → 基于已存排期生成复习队列 → 检查名字计划 → 生成 `today_brief`
2. **教学循环**（Agent 参与）：事件驱动的 turn 循环
3. **Session 结束**：`EndRequested` → Agent 生成告别与结构化总结 → App 原子化写入存储并更新进度

## 字库与课程数据

### 汉字数据模型

```kotlin
data class Hanzi(
    val char: String,
    val pinyin: String,
    val frequency: Int,
    val hskLevel: Int?,
    val radical: String,
    val strokeCount: Int,
    val strokes: List<Stroke>,
    val commonWords: List<String>,
    val exampleSentences: List<ExampleSentence>,
    val imageHint: String?,           // emoji 字符 或 内置图标名
    val confusableWith: List<String>, // 形近字列表
    val relatedChars: List<String>
)

data class Stroke(
    val name: String,
    val path: List<PointF>,
    val order: Int
)
```

### 课程组织原则

- 名字优先
- 高频优先
- 构字能力优先
- 情境聚类

示例顺序：
- 先拆姓名相关字
- 再进入家庭、数字、金额、地址等生活高频字
- 每课 1-3 个新字 + 若干复习字（数量由阶段决定，见 `CURRICULUM-DESIGN.md` §5）

### 姓名数据边界

- 保留学习者录入的准确姓名，不自动替换生僻字、异体字或多音字
- 建档时检查每个姓名字是否具备字体、拼音和笔画数据
- 数据不完整时明确提示协助者补充或调整，不静默生成错误内容

## 基础可用性要求

- 学习主界面保持单一主要任务，并明确显示当前是老师在说、系统在听还是轮到用户操作
- 核心操作提供大按钮，包括重听、提示、跳过、暂停和结束
- 支持系统字体缩放和高对比度显示，避免仅用颜色表达对错
- 进度反馈使用"已经能独立写出姓氏"等具体能力描述，避免考试式排名和红叉

## 辅助练习系统

详见 [`RESEARCH-EXERCISES.md`](./RESEARCH-EXERCISES.md)。

练习类型覆盖 4 个能力维度（识别/书写/理解/应用）：
- 看字认读、听音选字、听写、独立默写
- 组词、造句、读句子、选字填空
- 签名练习、形近字辨析、口头拆字、看图选字

新增 Agent 工具：`show_options`、`show_sentence`、`compare_characters`（已纳入工具分组，见上文与 `SYSTEM-PROMPT.md` / `AGENT-PROTOCOL.md`）。

新增事件：`ConfusableDetected`（形近字混淆时触发，已纳入 `AGENT-PROTOCOL.md` 事件表）。

## 评估范围

评估只保留对教学决策有价值的部分：

1. 识别评估：是否认得出该字
2. 书写评估：笔画数、结构、关键位置是否明显偏差
3. 理解评估：能否用自己的话解释
4. 使用评估：能否用该字说出生活中的句子
5. 记忆评估：复习时是否还能认写
6. 任务评估：能否在姓名、表格或其他真实场景中使用

不追求：
- 精细语音打分
- 声调级纠错
- 类口语考试式发音评价

## 尚待调研与实现

- [x] 中文语音模型：已调研，见 `RESEARCH-VOICE.md`
- [x] 手写识别：Android MotionEvent + 坐标对比，见 `RESEARCH-TECH.md`
- [x] 成人识字教学法：已调研，见 `RESEARCH-TEACHING.md`
- [x] 方案选型：已确定纯 Android 原生
- [x] 姓名拆字规则与签字达标标准：见 `TEACHING-STRATEGY.md` 与 `MASTERY-CRITERIA.md`
- [ ] 首启协助建档页字段定义（采集信息见“首启采集信息”，页面级字段待定）
- [ ] 字库数据准备（常用字笔画/拼音/组词/例句；字包具体字待确认，见 `CURRICULUM-DESIGN.md`）
- [ ] 米字格坐标系定义和笔画路径数据结构
- [ ] 间隔重复算法参数调优
- [ ] Provider 适配层与完整 JSON 工具调用协议定义
- [ ] LLM 完整 JSON 响应与 TTS/工具执行串联策略
- [ ] 测试基线：状态机单测 + golden turn 集 + 回放框架
- [ ] token 预算实测与上下文裁剪策略
- [ ] STT/TTS 引擎选型 POC（SenseVoice / Sherpa-ONNX / Whisper / CosyVoice，见 `RESEARCH-VOICE.md`）
