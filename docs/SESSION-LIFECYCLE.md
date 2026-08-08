# Session 生命周期与启动刷新

## 概述

每次打开 App 的学习过程是一个 `session`。在 Agent 开始教学之前，App 先完成一轮**非 Agent 的启动刷新**，生成最新的学习状态快照。

## 完整生命周期

```
App 启动
  │
  ├─ 1. 启动刷新（非 Agent，本地确定性计算）
  │     ├─ 读取上次 session 摘要
  │     ├─ 基于已存 next_review 生成 review_queue
  │     ├─ 检查 name_plan 进度
  │     ├─ 生成 today_brief
  │     └─ 构建首次 turn 的 System Prompt + 上下文
  │
  ├─ 2. 进入教学循环（Agent 参与）
  │     ├─ Turn 1: 打招呼 + 根据 today_brief 决定从哪开始
  │     ├─ Turn 2..N: 教学交互
  │     └─ 用户结束或自然结束
  │
  └─ 3. Session 结束（非 Agent）
        ├─ `EndRequested` → Agent 生成告别与结构化总结
        ├─ App 原子化更新 sessions 表（status → completed）
        ├─ 更新 name_plan（如有进展）
        └─ 发出 `SessionEnded` 给 UI；characters 已在每次 record_result 时即时更新
```

## 1. 启动刷新

每次 App 打开时执行，不依赖 LLM。

### 1.0 处理上次异常退出

```kotlin
val previousSession = sessionDao.getLatest()
if (previousSession != null && previousSession.status == "active") {
    sessionDao.update(previousSession.id, status = "aborted")
}
// 已通过 record_result 落库的学习数据不丢失
val newSessionId = sessionDao.insert(Session(
    date = today(),
    startedAt = now(),
    status = "active"
))
```

### 1.1 读取上次状态

```kotlin
// 伪代码
val lastSession = previousSession
val namePlan = namePlanDao.get()
val allChars = characterDao.getAll()
```

### 1.2 刷新复习队列

启动时不重算 SM-2 参数；只根据已经存好的 `next_review` 生成当天的复习队列：

```
如果当前时间 >= next_review → 该字进入复习队列
如果当前时间 + 1天 >= next_review → 标记为"即将需要复习"
```

复习队列排序（对齐 `characters` canonical schema，见 [`STORAGE-DESIGN.md`](./STORAGE-DESIGN.md) / [`MASTERY-CRITERIA.md`](./MASTERY-CRITERIA.md)）：
1. 最近出错的字（`common_mistakes` 非空 或 `streak_errors > 0`）
2. 已过期未复习的字（`next_review < now`）
3. 即将到期的字（`next_review < now + 1day`）
4. 同层内：最弱维度等级最低的字优先（`mastery_*` 四维取最小值比较）

### 1.3 检查姓名计划进度

```
读取 canonical `name_plan` 状态（以下是当前候选字段命名，用于说明所需信息，不要求后续实现必须逐字采用）：
- priority_mode
- current_stage
- recognition_ready
- guided_writing_ready
- independent_writing_ready
- signing_ready

如果 name_plan.signing_ready == true → 姓名目标已完成，但仍可按用户需要继续巩固姓名或转入其他生活字
如果 name_plan.independent_writing_ready == true → 接近完成，优先安排签字场景巩固
否则 → 根据 current_stage 判断当前应关注的字

注入给 Agent 的 `achieved_summary` 和 `next_milestone` 由上述字段派生生成，不要求在数据库中单独存储
```

### 1.4 生成 today_brief

本机生成的首次上下文块（仅供本机排课与 UI 使用，不发送给在线 Provider）：

```
<session_brief>
今日日期：2026-07-29
上次学习：7月28日，学了"张"和"三"，正确率 75%
连续学习天数：5 天
待复习字：家（逾期2天）、的（今日到期）、电（今日到期）
姓名目标进度：已能认出姓名目标中的"张""建""国"，有提示能写"张"和"建"，尚不能独立写"国"
今日建议重点：复习"家"字 + 继续练习"国"字
</session_brief>
```

这个 brief 让 Agent 第一次 turn 时不必摸索状态，直接能说出 "上次你把'家'字写得不错，今天我们复习一下，再继续练'国'字"。

### 1.5 构建首次 Prompt

初版在线请求不发送下列本地摘要。Provider 只接收 `AGENT-PROTOCOL.md` §2 定义的 `teaching_context`：当前字、教学阶段、允许动作、脱敏本地评估结果和回答文字。

```
[System Prompt（固定部分）]
  +
[工具列表]
  +
<session_brief>（刚生成的）
<learner_profile>
<name_plan>（使用 canonical 字段 + 派生摘要）
<review_queue>
<available_tools>
[event message] SessionStarted
```

## 2. 教学循环

与 Pi Agent 的 turn 循环一致：

```
事件输入 → Prompt 构建 → LLM 决策 → 工具执行 → 等待下一外部事件
```

关键事件驱动的 turn：
- `VoiceInput` → STT → Agent 处理
- `StrokeFinished` → 本地手写评估 → `WritingEvaluated` → Agent 反馈
- `ButtonTapped` → 按钮动作 → Agent 响应
- `TtsCompleted` → 如果本轮预约了 `listen`，此时才开麦等待回应
- `CharacterCompleted` → 当前字完成后的下一轮决策
- `EndRequested` → 最后一轮 LLM 生成告别与结构化总结
- `SessionEnded` → 结束循环（不再触发 LLM）

## 3. Session 结束

唯一结束流程：

1. App 产生 `EndRequested`
2. 注入 `<current_session_results>`，让 Agent 基于本次 session 的真实结果生成告别文本，并通过 `end_session` 提交结构化总结
3. 若 Provider 在结束 turn 失败，App 直接使用本地兜底话术和当前 session 摘要完成结束
4. 非 Agent 层在同一事务中更新 `sessions` 表（status → `completed`，填入 highlights/struggles/duration）并更新 `name_plan`
5. 计算新的连续学习天数
6. 发出 `SessionEnded` 供 UI 收尾；`characters` 表已在每次 `record_result` 时即时更新，此处不重复写入

## 与 Pi Agent 的对应

| Pi | 识字助手 |
|---|---|
| `agent.prompt()` | session 启动 → 进入教学循环 |
| `agent_start / agent_end` | SessionStarted / EndRequested（LLM）/ SessionEnded（仅 UI） |
| steering queue | 用户语音打断（"等一下""换个字"） |
| followUp queue | 暂不使用（当前无异步任务） |
| tool execution | 本地执行教学工具 |
| `events (message_start/update/end)` | 对应事件模型中的各类事件 |
| `prepareNextTurn` | 当前不切换模型（仅一个 provider） |
| compaction | 不适用（对话不无限增长，session 限长） |

## 启动刷新的理论基础

除了费曼学习法和间隔重复，还融入了：

| 理论 | 应用 |
|---|---|
| **支架式教学 (Scaffolding)** | Agent 根据学生表现动态调整帮助程度——搭好"刚好够"的支架，随着能力提升逐步撤除 |
| **提取练习 (Retrieval Practice)** | 复习时先让学生回忆，不直接展示答案。回忆的困难本身就在强化记忆 |
| **交叉练习 (Interleaving)** | 新旧字混合复习，认读和书写交替，不把同类练习堆在一起 |
