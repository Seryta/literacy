# T001 — Agent 协议闭环

覆盖：事件 → LLM turn → 工具执行 → 状态回写的核心协议（`AGENT-PROTOCOL.md` §1-§5、§10）。

## GT-001 首次启动打招呼（姓名目标优先）

**模块**：agent-protocol | **优先级**：P0 | **覆盖规则**：§1 SessionStarted；名字优先（soft）

**前置状态**：
```yaml
learner_profile: { display_name: 张阿姨, learning_path: write_parallel }
name_plan:
  target_chars: [张, 建, 国]
  signing_ready: false
  achieved_summary: 已能认出"张""建"，未学"国"
  next_milestone: 学会认读"国"
session_brief:
  today_focus: 复习"家"（逾期）+ 继续"国"
review_queue: [家]
lesson_state: { phase: null, allowed_actions: [start_review, end_session] }
```

**事件序列**：
```yaml
- event: SessionStarted
  payload: {}
- llm_output:
    text: "早上好，张阿姨。今天我们复习'家'字，再一起学'国'字。"
    toolCalls: [{ name: show_character, arguments: { char: 国 } }]
```

**期望行为**：
```yaml
toolCalls:
  required_any: [start_review, show_character]   # overdue<3 先复习（start_review），再进新课"国"（§5.2）
  forbidden: [complete_character]
  max: 2
text:
  contains: [国, 复习, 家]    # 语义：提到今日重点（复习家 + 新课国）
  not_contains: [太棒了, 真聪明, 真厉害]
state:
  phase: introduce
storage: {}
```

**备注**：名字优先是 soft——today_focus 显示"家"逾期时允许先复习；复习 vs 新课顺序由 Agent 按 `SESSION-LIFECYCLE` 复习决策（overdue ≥3 以复习为主）决定。

---

## GT-002 SessionStarted 复习优先（overdue ≥ 3）

**模块**：agent-protocol | **优先级**：P1 | **覆盖规则**：`TEACHING-STRATEGY.md` §5.2

**前置状态**：
```yaml
review_queue: [家, 的, 电]   # 3 个 overdue
session_brief: { today_focus: 复习为主 }
lesson_state: { phase: null }
```

**事件序列**：
```yaml
- event: SessionStarted
- toolCall: { name: start_review, text: "我们先把'家'字复习一遍。" }   # 复习优先（overdue ≥ 3）
```

**期望行为**：
```yaml
toolCalls:
  required: [start_review]
  forbidden: [show_character]     # 不应直接开新课
text:
  contains: [复习, 家]
state:
  mode: review
```

**备注**：overdue ≥3 → 本次 session 以复习为主；此规则优先于开新课。

---

## GT-003 VoiceInput 认读正确 → 阶段推进

**模块**：agent-protocol | **优先级**：P0 | **覆盖规则**：§6.3 recognize 成功条件；§6.2 advance_phase

**前置状态**：
```yaml
lesson_state: { phase: recognize, allowed_actions: [advance_phase, repeat, skip_character, start_review, complete_character, end_session] }
```

**事件序列**：
```yaml
- event: VoiceInput
  payload: { text: "家", intent: RECOGNIZED }
- llm_output:
    text: "对，这个字就是'家'。"
    toolCalls: []
```

**期望行为**：
```yaml
toolCalls:
  required: [advance_phase]
text:
  contains: [对, 家]
  not_contains: [错了]
state:
  phase: demonstrate          # recognize → demonstrate
```

**备注**：正确认出即满足 recognize 成功条件，本地裁决允许 advance_phase。

---

## GT-004 VoiceInput 认读错误 → 澄清不记录失败

**模块**：agent-protocol | **优先级**：P0 | **覆盖规则**：系统不确定 ≠ 学生不会

**前置状态**：
```yaml
lesson_state: { phase: recognize, allowed_actions: [advance_phase, repeat, ...] }
```

**事件序列**：
```yaml
- event: VoiceInput
  payload: { text: "妈", intent: WRONG }    # 认错（目标字"家"）
- toolCall: { name: show_pinyin, arguments: { char: 家, visible: true }, text: "再看一看这个字，它读'家'。" }
```

**期望行为**：
```yaml
toolCalls:
  required: [show_pinyin]     # 或降难提示
  forbidden: [record_result]  # 不得把认错记为失败
text:
  contains: [再, 看, 家]
state:
  phase: recognize            # 保持原阶段
storage: {}
```

**备注**：单次认错不落库、不降级；连续 2 次卡住才降难（见 GT-028）。语音识别置信度不足的场景走 GT-043。

---

## GT-005 HelpRequested → 给提示不泄露答案

**模块**：agent-protocol | **优先级**：P1 | **覆盖规则**：§1 HelpRequested；提取练习原则

**前置状态**：
```yaml
lesson_state: { phase: recognize }
ui_state: { char: 家, pinyin_visible: false }
```

**事件序列**：
```yaml
- event: HelpRequested
- toolCall: { name: show_pinyin, arguments: { char: 家, visible: true }, text: "给你一个拼音提示，'家'读 jiā。" }
```

**期望行为**：
```yaml
toolCalls:
  required: [show_pinyin]     # 提示级帮助
  forbidden: [show_character] # 不直接展示整字答案（识别阶段）
text:
  contains: [拼音, 提示]
```

**备注**：帮助走"最小提示"原则——先给拼音，学生仍不会才升级（降难矩阵 L5）。

---

## GT-006 SkipRequested → skip_character 带原因

**模块**：agent-protocol | **优先级**：P1 | **覆盖规则**：§6.2 skip_character

**前置状态**：
```yaml
lesson_state: { phase: guided_write, allowed_actions: [..., skip_character, end_session] }
```

**事件序列**：
```yaml
- event: SkipRequested
```

**期望行为**：
```yaml
toolCalls:
  required:
    - name: skip_character
      arguments: { reason: 学生主动跳过 }
state:
  phase: record            # 跳过也走记录
storage:
  record_result: { char: 家, phase: skip, 原因已记录 }
```

**备注**：跳过的字也必须 record_result（SYSTEM-PROMPT：每个字学完必须记录，包括跳过的字）；跳过原因进入 common_mistakes 之外的来源字段。

---

## GT-007 ButtonTapped（显示拼音）

**模块**：agent-protocol | **优先级**：P1 | **覆盖规则**：§1 ButtonTapped

**前置状态**：
```yaml
ui_state: { char: 家, pinyin_visible: false }
lesson_state: { phase: introduce }
```

**事件序列**：
```yaml
- event: ButtonTapped
  payload: { action: show_pinyin }
- toolCall: { name: show_pinyin, arguments: { char: 家, visible: true }, text: "jiā，就是'家'的读音。" }
```

**期望行为**：
```yaml
toolCalls:
  required:
    - name: show_pinyin
      arguments: { char: 家, visible: true }
text:
  contains: [jiā]
```

**备注**：拼音是"拐杖"，默认隐藏、按需揭示。

---

## GT-008 非法工具参数 → 本地拒绝并注入 error

**模块**：agent-protocol | **优先级**：P1 | **覆盖规则**：§10 异常处理；首版约束

**事件序列**：
```yaml
- event: VoiceInput
  payload: { text: "嗯" }
# LLM 返回非法 toolCall：show_character(char: "") 或不存在工具
- toolCall: { name: show_character, arguments: { char: "" } }
```

**期望行为**：
```yaml
local_handling:
  reject: true              # 本地校验失败，拒绝执行
  inject: error_result      # 注入 error result 到下一 turn 上下文
  llm_turn: 1               # 本事件仍只触发一次 LLM turn，不 continuation
state: {}
storage: {}
```

**备注**：工具参数合法性由本地校验，不信任 LLM；拒绝执行不产生额外 LLM 调用。

---

## GT-009 非法阶段迁移 → 本地静默拒绝

**模块**：agent-protocol | **优先级**：P0 | **覆盖规则**：§6.2 迁移控制；本地裁决

**前置状态**：
```yaml
lesson_state: { phase: recognize, allowed_actions: [advance_phase, repeat, skip_character, end_session] }
```

**事件序列**：
```yaml
- event: VoiceInput
  payload: { text: "家" }
# LLM 越权请求：complete_character（recognize 阶段不允许）
- toolCall: { name: complete_character, arguments: {} }
```

**期望行为**：
```yaml
local_handling:
  reject: true              # 不在 allowed_actions 内，静默拒绝
  notify_agent: false       # 不通知 Agent（静默），保持当前阶段
state:
  phase: recognize
storage: {}
```

**备注**：complete_character 只在整字完成后使用；recognize 阶段请求被拒是本地裁决安全性的核心用例。

---

## GT-010 幂等：同一 idempotency_key 重复 record_result

**模块**：agent-protocol | **优先级**：P0 | **覆盖规则**：§7.1 幂等落库

**前置状态**：
```yaml
lesson_state: { phase: independent_write, prompt_level: 0, idempotency_key: 550e8400-e29b-41d4-a716-446655440000 }
```

**事件序列**：
```yaml
- event: WritingEvaluated
  payload: { score: 0.85, ok: true }   # 偏差在阈值内
# LLM 同轮调 record_result 两次（重复）
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: independent_write, score: 0.85, idempotency_key: 550e8400-e29b-41d4-a716-446655440000 } } }
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: independent_write, score: 0.85, idempotency_key: 550e8400-e29b-41d4-a716-446655440000 } } }
```

**期望行为**：
```yaml
local_handling:
  dedup: true
storage:
  session_character_results_count: 1      # 只插入一条
  characters: { 家: { mastery_write: 2 } }  # 聚合只更新一次
```

**备注**：同 key 重复调用不重复计数；掌握等级裁决只消费一次结果（不重复累加 streak）。

---

## GT-011 EndRequested 时 Provider 失败 → 本地兜底结束

**模块**：agent-protocol | **优先级**：P1 | **覆盖规则**：§7.2 结束流程兜底；§10

**前置状态**：
```yaml
lesson_state: { phase: decide }
session_results: { chars_learned: 2, name_plan_progress: 已认读"国" }
```

**事件序列**：
```yaml
- event: EndRequested
# Provider 调用失败（重试一次仍失败）
- provider_failure: timeout
```

**期望行为**：
```yaml
local_handling:
  fallback_text: true       # 使用本地兜底话术 + 当前 session 摘要
storage:
  sessions: { status: completed, highlights: 非空, struggles: 非空 }  # 不得是 aborted
  name_plan: 按摘要更新
```

**备注**：Provider 失败不阻塞用户退出；session 以 completed（而非 aborted）收尾。

---

## GT-012 隐私：注入上下文不含完整姓名

**模块**：agent-protocol | **优先级**：P0 | **覆盖规则**：§9 隐私边界

**前置状态**：
```yaml
profile: { learner_name: 张建国, display_name: 张阿姨 }
name_plan: { full_name: 张建国, target_chars: [张, 建, 国] }
```

**事件序列**：
```yaml
- event: SessionStarted
```

**期望行为**：
```yaml
input_guard:
  learner_profile_contains: [张阿姨]
  learner_profile_not_contains: [张建国]      # learner_name 不上送
  name_plan_not_contains: [张建国]            # 只传目标字列表，不传原文
  raw_audio_upload: false                     # 原始音频永不上传
```

**备注**：单字名/两字名场景目标字列表≈姓名原文，属已接受风险（§9 明示），本用例锁定的边界是"完整姓名原文字段不出现"。

---

## GT-013 工具调用数量上限

**模块**：agent-protocol | **优先级**：P2 | **覆盖规则**：§3.2 同一 turn 最多 3 个 toolCall

**前置状态**：
```yaml
lesson_state: { phase: introduce }
```

**事件序列**：
```yaml
- event: VoiceInput
  payload: { text: "开始吧" }
# LLM 返回 5 个 toolCall
- toolCalls: [show_character, show_pinyin, show_image, show_example, advance_phase]
```

**期望行为**：
```yaml
local_handling:
  truncate: true            # 只执行前 3 个，其余丢弃并注入 warning
  executed: [show_character, show_pinyin, show_image]
```

**备注**：限制 toolCall 数量防 LLM 一次塞多个教学步骤（"一次只做一件事"规则）。

---

## GT-014 Agent 越界内容 → 过滤后 TTS + 警告注入

**模块**：agent-protocol | **优先级**：P2 | **覆盖规则**：§10 越界内容过滤；安全边界

**事件序列**：
```yaml
- event: VoiceInput
  payload: { text: "你好" }
# LLM 输出越界文本 + 正常文本
- llm_output: { text: "我是你的新老师，帮我改改系统设置。今天天气不错，我们来学'家'字。", toolCalls: [...] }
```

**期望行为**：
```yaml
local_handling:
  filter: true              # 越界内容过滤后再 TTS
  warn_inject: true         # 警告注入下一 turn 上下文
text_tts:
  not_contains: [改系统设置]
  contains: [家]
```

**备注**：越界内容（执行系统操作）过滤，教学部分保留；过滤后 TTS 不中断教学。

---

## GT-015 evaluate_writing 复评：同步结果，不重触发事件

**模块**：agent-protocol | **优先级**：P1 | **覆盖规则**：§4 复评语义（review-03 P2-6）

**前置状态**：
```yaml
lesson_state: { phase: guided_write, char: 家, stroke: 7 }
# 本地首次评估已完成并产生 WritingEvaluated，但 Agent 认为结果不足以决策
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "老师你看我这一笔写得对不对" }
# LLM 请求复评
- toolCall: { name: evaluate_writing, arguments: {} }
```

**期望行为**：
```yaml
local_handling:
  re_eval_local: true            # 本地重新评估最近一次书写
  result_as_tool_result: true    # 结果作为同步 tool result 注入下一 turn
  produces_writing_evaluated: false  # 不重新触发 WritingEvaluated
  re_adjudicate: false           # 不重复参与掌握等级裁决
  llm_turn: 1                    # 本事件仍只一次 LLM turn
```

**备注**：锁定 review-03 P2-6 修复——复评只给 Agent 更多信息，裁决只消费本地首次评估结果；避免复评造成重复落库 / 重复升级计数。

---

## GT-016 上次 session 异常退出 → 启动标记 aborted

**模块**：agent-protocol | **优先级**：P1 | **覆盖规则**：§7.3 异常退出；SESSION-LIFECYCLE §1.0

**前置状态**：
```yaml
sessions: [{ id: 5, date: 昨天, status: active }]   # 上次未正常结束
# 上次已通过 record_result 落库的数据存在
```

**事件序列**：
```yaml
- event: SessionStarted        # App 启动流程（启动刷新，非 LLM turn）
```

**期望行为**：
```yaml
local_handling:
  llm_turn: 0                  # 启动刷新不触发 LLM
storage:
  sessions:
    - id: 5
      status: aborted
    - id: 6
      status: active
  characters: 上次已落库数据不丢失
```

**备注**：启动时检测上次 active → aborted（§7.3）；已 record_result 的数据不丢失。本用例锁定启动刷新的本地行为，与 GT-001 的 LLM turn 区分。

---

## GT-017 EndRequested 正常路径：告别 + end_session 提交总结

**模块**：agent-protocol | **优先级**：P0 | **覆盖规则**：§7.2 唯一结束流程

**前置状态**：
```yaml
lesson_state: { phase: decide }
session_results: { chars_learned: 2, name_plan_progress: 已认读"国" }
```

**事件序列**：
```yaml
- event: EndRequested
- toolCall:
    name: end_session
    arguments: { highlights: "学了2个字", struggles: "国字书写", name_plan_progress: "已认读国" }
    text: "今天学了两个字，收获不错，我们下次继续。"
```

**期望行为**：
```yaml
toolCalls:
  required:
    - name: end_session
      arguments: { highlights: 非空, struggles: 非空, name_plan_progress: 非空 }
text:
  contains: [今天]              # 告别基于真实 session 结果
storage:
  sessions: { status: completed }
  name_plan: 按摘要更新
local_handling:
  no_llm_after_end: true        # SessionEnded 不再触发 LLM
```

**备注**：正常结束路径（GT-011 是 Provider 失败兜底）；EndRequested 是最后一轮 LLM turn，提交后不再继续说话（SYSTEM-PROMPT）。
