# T005 — 练习变体

覆盖：show_options / compare_characters / show_sentence 及签名练习（`RESEARCH-EXERCISES.md` 首版推荐、`AGENT-PROTOCOL.md` §4 练习工具、`MASTERY-CRITERIA.md` §7 签字达标）。

## GT-060 show_options 听音选字（识别兜底 / STT 失败兜底）

**模块**：exercise-variants | **优先级**：P0 | **覆盖规则**：§4 show_options；练习事件 payload

**前置状态**：
```yaml
lesson_state: { phase: recognize, char: 家, attempt: 2 }
```

**事件序列**：
```yaml
- toolCall: { name: show_options, arguments: { exercise_id: e7, prompt: "这个字是哪一个？" } }
- event: VoiceInput, payload: { exercise_id: e7, normalized_option_id: opt_3, is_correct: true, exercise_type: audio_choice }
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: recognize, exercise_type: audio_choice, score: 1.0, idempotency_key: gt060-1 } } }
```

**期望行为**：
```yaml
toolCalls:
  required: [record_result]
toolCall_args:
  record_result: { result: { phase: recognize, exercise_type: audio_choice, score: 1.0 } }
storage:
  characters: { 家: { mastery_recognize: 1 } }
  session_character_results_count: 1
```

**备注**：show_options 只引用 App 预置题目，Agent 不生成正确答案；语音作答走 VoiceInput + normalized_option_id 判题（§4）。

---

## GT-061 ConfusableDetected → compare_characters 辨析

**模块**：exercise-variants | **优先级**：P0 | **覆盖规则**：§1 ConfusableDetected；RESEARCH-EXERCISES 形近字触发

**前置状态**：
```yaml
lesson_state: { phase: independent_write, char: 太 }
characters: { 太: { confusable_with: [大, 犬] } }
```

**事件序列**：
```yaml
- event: ConfusableDetected, payload: { char: 太, confused_char: 大, trigger: writing }
- toolCall: { name: compare_characters, arguments: { char_a: 太, char_b: 大 }, text: "你注意看这两个字的区别，下面这个点很关键。" }
```

**期望行为**：
```yaml
toolCalls:
  required:
    - name: compare_characters
toolCall_args:
  compare_characters: { char_a: 太, char_b: 大 }
text:
  contains: [区别, 点]          # 引导观察区别
state:
  insert_before: record        # 辨析插入在 record 之前
```

**备注**：形近字混淆触发后 Agent 决定插入辨析；compare_characters 只并列展示，区别由 Agent 口头引导。

---

## GT-062 show_sentence 读句子练习

**模块**：exercise-variants | **优先级**：P1 | **覆盖规则**：§4 show_sentence；RESEARCH-EXERCISES 推荐 #8

**前置状态**：
```yaml
lesson_state: { phase: decide, char: 家 }
```

**事件序列**：
```yaml
- toolCall: { name: show_sentence, arguments: { sentence_text: "这是我的家" }, text: "读对了，这句话就是'这是我的家'。" }
- event: VoiceInput, payload: { text: "这是我的家" }
```

**期望行为**：
```yaml
toolCalls: { max: 1 }
text:
  contains: [读, 对]            # 具体反馈
```

**备注**：读句子是综合认读巩固；句子展示后请学生朗读（与 listen 预约配合，见 GT-040）。

---

## GT-063 签名练习：连续 2 次独立成功 → signing_ready

**模块**：exercise-variants | **优先级**：P0 | **覆盖规则**：MASTERY-CRITERIA §7 签字达标

**前置状态**：
```yaml
name_plan: { target_chars: [张, 建, 国], independent_writing_ready: true, signing_ready: false }
learner_profile: { learning_path: write_parallel }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "我想试试签名" }
- event: WritingEvaluated, payload: { phase: signature, ok: true, score: 0.85 }   # 第 1 次成功，整体可辨认
- event: WritingEvaluated, payload: { phase: signature, ok: true, score: 0.9 }    # 第 2 次成功，整体可辨认
```

**期望行为**：
```yaml
storage:
  name_plan: { signing_ready: true }     # 连续 2 次独立签名成功
state:
  mode: learning            # 签名练习仍在教学 session 内（签名区/米字格属 UI 层，不改变 mode）
local_handling:
  signature_threshold: 放宽阈值           # 允许连笔/适度草化，整体可辨认
```

**备注**：签字判定独立于单字米字格评估（连笔/草化可接受）；连续 2 次成功 → P0 完成 → 进入下一字包；姓名仍按间隔重复巩固（间隔短 30%）。

---

## GT-064 难字拆解：笔画多且连续失败 → 拆部件

**模块**：exercise-variants | **优先级**：P1 | **覆盖规则**：TEACHING-STRATEGY §9 难字拆解

**前置状态**：
```yaml
lesson_state: { phase: independent_write, char: 赢, prompt_level: 4, stuck_count: 2 }
characters: { 赢: { stroke_count: 17 } }
```

**事件序列**：
```yaml
- event: WritingEvaluated, payload: { phase: independent_write, score: 0.3, ok: false }
- toolCall: { name: show_character, arguments: { char: 赢 }, text: "这个字笔画多，我们先拆成几个部分来看。" }
```

**期望行为**：
```yaml
toolCalls:
  required: [show_character]
text:
  contains: [先, 部分, 拆]      # 拆部件教学（亡口月贝凡）
state:
  phase: independent_write   # 本地裁决：失败不推进、不降级（非复习轮）；拆部件回退是 Agent 决策层行为，由 mock 编排
```

**备注**：拆解条件 = 笔画 ≥8 且连续 2 次独立写失败（或用户主动要求）；拆到关键部件为止，完成后回到整字签名验证（MASTERY-CRITERIA §7.4）。本地层锁定的行为是"失败不推进"；回退到 guided_write 需 mock LLM 输出相应教学动作。
