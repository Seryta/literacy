# T003 — 语音交互

覆盖：listen 预约开麦、TTS 时序、超时、连续 STT 失败、暂停恢复、疲劳结束（`AGENT-PROTOCOL.md` §4/§5、`TEACHING-STRATEGY.md` §7、`RESEARCH-VOICE.md` 语音体验标准）。

## GT-040 listen 预约 → TtsCompleted 后开麦

**模块**：voice-interaction | **优先级**：P0 | **覆盖规则**：§4 listen 语义；§5 等待点

**前置状态**：
```yaml
lesson_state: { phase: recognize, char: 家 }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "这个字读什么？" }
# Agent 回复并预约 listen
- toolCall: { name: listen, arguments: {} }
- event: TtsCompleted                       # 本轮 TTS 播完
```

**期望行为**：
```yaml
local_handling:
  open_mic: true             # TTS 播完才真正开麦
  llm_turn_after_tts: 0      # 开麦不触发新 LLM turn
  next_expected: VoiceInput  # 等待语音输入
```

**备注**：listen 是"预约"不是"立即开麦"；TTS 未播完不得抢麦（锁定时序，防止语音打断教学）。

---

## GT-041 IdleTimeout：沉默超时 → 关怀话术

**模块**：voice-interaction | **优先级**：P1 | **覆盖规则**：§1 IdleTimeout；TEACHING-STRATEGY §7

**前置状态**：
```yaml
lesson_state: { phase: recognize, char: 家 }
```

**事件序列**：
```yaml
- event: IdleTimeout, payload: { waiting_for: voice, idle_seconds: 12 }
- llm_output:
    text: "你还在吗？没关系，慢慢来。"
    toolCalls: []
```

**期望行为**：
```yaml
toolCalls: { max: 1 }
text:
  contains: [在吗, 没关系]          # 关怀话术
  not_contains: [答错了, 不会吧]
```

**备注**：沉默不是错误；首次 IdleTimeout 触发 LLM 关怀；连续 IdleTimeout 的升级策略（如本地结束提示）设计文档未定义，本用例只锁定首次超时行为。

---

## GT-042 RecognitionRepeatedFailures：连续 STT 失败 → 降难或切选项

**模块**：voice-interaction | **优先级**：P0 | **覆盖规则**：§1 RecognitionRepeatedFailures；RESEARCH-VOICE 降级策略

**前置状态**：
```yaml
lesson_state: { phase: recognize, char: 家 }
```

**事件序列**：
```yaml
- event: RecognitionRepeatedFailures, payload: { failure_count: 3, last_partial_text: "" }
- toolCall: { name: show_options, arguments: { exercise_id: e5 }, text: "我们换一种方式，你看屏幕上的选项。" }
```

**期望行为**：
```yaml
toolCalls:
  required_any: [show_options, show_pinyin]   # 降难或切到屏幕选项
text:
  not_contains: [你没听清吧]                  # 不责备
local_handling:
  no_failure_record: true                     # 识别失败不落为学习错误
```

**备注**：连续 3 次 STT 失败触发；Agent 必须改变交互方式（选项/拼音），不能继续要求重说。

---

## GT-043 RecognitionLowConfidence：本地澄清，不触发 LLM

**模块**：voice-interaction | **优先级**：P1 | **覆盖规则**：§1 RecognitionLowConfidence；系统不确定 ≠ 学生不会

**事件序列**：
```yaml
- event: RecognitionLowConfidence, payload: { partial: "家？", confidence: 0.45 }
```

**期望行为**：
```yaml
local_handling:
  llm_turn: 0               # 本地澄清，不调 LLM
  retry_prompt: true        # "请再说一遍"或看屏幕选项
```

**备注**：单次低置信度由本地处理；累积到 3 次才升级为 RecognitionRepeatedFailures（触发 LLM）。

---

## GT-044 PauseRequested 暂停 → 恢复从原阶段继续

**模块**：voice-interaction | **优先级**：P0 | **覆盖规则**：§1 暂停与恢复

**前置状态**：
```yaml
lesson_state: { phase: guided_write, char: 国, stroke: 3 }
```

**事件序列**：
```yaml
- event: PauseRequested                        # 本地暂停，不调 LLM
- event: ButtonTapped, payload: { action: resume }
- llm_output:
    text: "好，我们继续写'国'字。"
    toolCalls: []
```

**期望行为**：
```yaml
local_handling:
  pause_llm_turn: 0         # 暂停不触发 LLM
state:
  phase: guided_write       # 恢复后从原阶段继续
  stroke: 3                 # 不重头开始
  re_introduce: false       # 不重走 introduce
text:
  contains: [继续, 国]       # 恢复语
```

**备注**：恢复 = ButtonTapped(action=resume) 触发 LLM；lesson_state 保留暂停前状态（review-03 P2-5 修复后）。

---

## GT-045 疲劳结束（"累了"）→ 认同 + 总结 + end_session

**模块**：voice-interaction | **优先级**：P0 | **覆盖规则**：TEACHING-STRATEGY §7 疲劳协议；§7.2 结束流程

**前置状态**：
```yaml
lesson_state: { phase: guided_write, char: 家 }
session_results: { 已完成: [张, 建], name_plan_progress: 已认读"国" }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "我有点累了，不想学了" }
- toolCall:
    name: end_session
    arguments: { highlights: "学了张、建", struggles: "国字", name_plan_progress: "已认读国" }
    text: "今天你学会了'张'和'建'，先到这里休息。"
```

**期望行为**：
```yaml
toolCalls:
  required:
    - name: end_session
      arguments: { highlights: 非空, struggles: 非空, name_plan_progress: 非空 }
text:
  contains: [今天, 学会]      # 简短总结进步
  not_contains: [再坚持一下, 还差一点]   # 不劝说继续
```

**备注**：疲劳信号立即停止当前教学，不尝试完成当前字；end_session 参数必须含三项结构化总结。

---

## GT-046 TTS 播完未预约 listen → 不开麦

**模块**：voice-interaction | **优先级**：P1 | **覆盖规则**：§5 等待点；listen 预约语义

**前置状态**：
```yaml
lesson_state: { phase: introduce, char: 家 }
# 本轮 Agent 未调用 listen
```

**事件序列**：
```yaml
- event: TtsCompleted        # 本轮 TTS 播完，但无 listen 预约
```

**期望行为**：
```yaml
local_handling:
  open_mic: false            # 不自动开麦
  llm_turn: 0                # 不触发新 LLM turn
  next_expected: 按钮或其他事件  # 等待用户动作
```

**备注**：与 GT-040 互补——listen 是预约；未预约时 TTS 播完只是信号事件，不抢麦不等待语音。

---

## GT-047 连续 2 次 STT 失败 → 本地提示重说（未达事件阈值）

**模块**：voice-interaction | **优先级**：P1 | **覆盖规则**：RESEARCH-VOICE 语音体验标准（连续 2/3 次）

**前置状态**：
```yaml
lesson_state: { phase: recognize, char: 家 }
# 本阶段已有 1 次识别失败（本地处理）
```

**事件序列**：
```yaml
- event: RecognitionLowConfidence, payload: { partial: "", confidence: 0.3 }   # 第 2 次失败
```

**期望行为**：
```yaml
local_handling:
  llm_turn: 0                # 连续 2 次仍本地处理，不触发 LLM
  retry_prompt: true         # "请再说一遍"（本地）
  produces_event: false      # 不产生 RecognitionRepeatedFailures（未到 3 次）
```

**备注**：RESEARCH-VOICE 降级策略第 2 档边界：连续 2 次本地提示；第 3 次才产生 RecognitionRepeatedFailures（GT-042 锁定）。本用例锁定"2 次不越级"。
