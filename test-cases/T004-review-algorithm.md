# T004 — 复习算法

覆盖：复习模式状态机（recall → assess → reinforce → next）、间隔重复、掌握降级（`AGENT-PROTOCOL.md` §6.5、`MASTERY-CRITERIA.md` §2/§6、`SESSION-LIFECYCLE.md` §1.2）。

## GT-050 start_review 进入复习模式

**模块**：review-algorithm | **优先级**：P0 | **覆盖规则**：§6.5 复习模式进入

**前置状态**：
```yaml
review_queue: [家, 的, 电]
lesson_state: { phase: decide, allowed_actions: [start_review, advance_phase, ...] }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "我们复习一下吧" }
- toolCall: { name: start_review, text: "好，我们先复习'家'字。" }
```

**期望行为**：
```yaml
toolCalls:
  required: [start_review]
state:
  mode: review
  allowed_actions: [next, start_review, end_session]   # advance_phase/complete_character 不可用
text:
  contains: [家]                     # 第一个复习字
```

**备注**：复习模式进入条件：review_queue 非空（本地校验）；allowed_actions 切换（§6.5）。

---

## GT-051 recall：先回忆，不展示答案

**模块**：review-algorithm | **优先级**：P0 | **覆盖规则**：§6.5 recall；提取练习

**前置状态**：
```yaml
lesson_state: { mode: review, review_stage: recall }
review_queue: [家, 的]
```

**事件序列**：
```yaml
- event: SessionStarted   # 复习字进入 recall
- llm_output:
    text: "还记得'家'字怎么写吗？"
    toolCalls: []
```

**期望行为**：
```yaml
toolCalls:
  forbidden: [show_character]      # 复习不直接展示答案
  forbidden: [show_pinyin]
text:
  contains: [还记得, 家]            # 引导回忆
```

**备注**：即使回忆有误，回忆本身强化记忆；学生明确失败后才展示（降为 reinforce）。

---

## GT-052 assess：听音选字判题 → record_result

**模块**：review-algorithm | **优先级**：P0 | **覆盖规则**：§4 练习事件 payload；§7.1

**前置状态**：
```yaml
lesson_state: { mode: review, review_stage: assess }
current_review_char: 家
```

**事件序列**：
```yaml
- toolCall: { name: show_options, arguments: { exercise_id: e1, prompt: "哪个是'家'？" } }
- event: ButtonTapped, payload: { exercise_id: e1, selected_option_id: opt_2, is_correct: true, exercise_type: audio_choice }
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: assess, exercise_type: audio_choice, score: 1.0, idempotency_key: gt052-1 } } }
```

**期望行为**：
```yaml
toolCalls:
  required: [record_result]
toolCall_args:
  record_result: { result: { phase: assess, exercise_type: audio_choice, score: 1.0 } }
storage:
  session_character_results_count: 1        # 判对落库 1 条
# state.stage（next 或 reinforce）由真实 LLM 决策，mock 模式不精确断言（复习阶段推进见 GT-051 单元测试）
```

**备注**：复习优先用听音选字/听写而非闪卡（间隔重复规则）；判题在本地（ButtonTapped 带 is_correct）。

---

## GT-053 复习出错 + ≥L3 提示 → 掌握降一级

**模块**：review-algorithm | **优先级**：P1 | **覆盖规则**：MASTERY-CRITERIA §2 降级规则

**前置状态**：
```yaml
lesson_state: { mode: review, review_stage: reinforce }
characters: { 家: { mastery_recognize: 2, streak_success: 2, streak_errors: 0 } }
```

**事件序列**：
```yaml
- event: WritingEvaluated, payload: { phase: independent_write, score: 0.4, ok: false, prompt_level: full_demo }   # L4 提示，出错
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: assess, score: 0.4, prompt_level: "3", idempotency_key: gt053-1 } } }   # 复习判题落库（§6.4 触发点）
```

**期望行为**：
```yaml
storage:
  characters: { 家: { mastery_recognize: 1, streak_errors: 1, streak_success: 0 } }   # 降一级
```

**备注**：复习时出错且需要 ≥L3 提示 → 任何等级降一级；稳定掌握连续 2 次需提示 → 降到初步掌握。

---

## GT-054 next 推进复习字；队列清空 → 本地拒绝

**模块**：review-algorithm | **优先级**：P0 | **覆盖规则**：§6.2 next；§6.5 退出

**前置状态**：
```yaml
lesson_state: { mode: review, review_stage: next }
review_queue: [家]        # 仅剩 1 个
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "下一个" }
- toolCall: { name: next }          # 推进到下一个复习字
- toolCall: { name: next }          # 队列已清空 → 本地拒绝（GT-054 守卫）
```

**期望行为**：
```yaml
local_handling:
  review_empty_guard: true      # 队列清空时 next 被本地拒绝
state:
  current_review_char: 家        # 保持当前字
```

**备注**：next 仅复习模式可用；队列清空后本地拒绝，等待 Agent 决定结束或返回主线（review-03 P1-1 修复后）。

---

## GT-055 复习队列处理完毕 → 结束或返回主线

**模块**：review-algorithm | **优先级**：P1 | **覆盖规则**：§6.5 退出

**前置状态**：
```yaml
mode: review
review_queue: []          # 已清空
session_results: { 复习完成: [家, 的, 电] }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "都复习完了" }
- llm_output:
    text: "复习完成，我们下次再继续巩固。"
    toolCalls: []
```

**期望行为**：
```yaml
toolCalls:
  required_any: [end_session]   # 队列已空：结束 session 或返回主线；start_review 会被本地拒绝（§6.5）
text:
  contains: [复习, 完成]
```

**备注**：队列清空后 Agent 二选一：结束 session 或回到主线；本用例锁定"不能再次 start_review"（队列空时本地会拒绝）。

---

## GT-056 复习间隔排期：最弱维度决定

**模块**：review-algorithm | **优先级**：P2 | **覆盖规则**：MASTERY-CRITERIA §6

**前置状态**：
```yaml
characters: { 家: { mastery_recognize: 3, mastery_write: 1, mastery_understand: 2, mastery_apply: 1 } }
```

**事件序列**：
```yaml
- event: SessionStarted
```

**期望行为**：
```yaml
local_handling:
  weakest_dimension: write       # 最弱维度=书写（1）
  review_mode_preferred: dictation  # 复习优先听写而非看字读音
storage:
  schedule: 按最弱维度计算 ease_factor / interval_days
```

**备注**：复习排期取 4 维度最弱等级；复习时优先检测最弱维度（写弱于认 → 优先听写）。

---

## GT-057 名字字复习间隔短 30%

**模块**：review-algorithm | **优先级**：P2 | **覆盖规则**：MASTERY-CRITERIA §6；STORAGE source 字段

**前置状态**：
```yaml
characters:
  张: { source: name_plan, mastery_write: 3, mastery_recognize: 3, mastery_understand: 3, mastery_apply: 3 }
  家: { source: life_pack, mastery_write: 3, mastery_recognize: 3, mastery_understand: 3, mastery_apply: 3 }
# 两字 4 维度等级相同，最弱维度同为 3
```

**事件序列**：
```yaml
- event: SessionStarted      # 启动刷新计算复习排期
```

**期望行为**：
```yaml
local_handling:
  name_char_interval_factor: 0.7    # 名字字间隔 × 0.7
storage:
  张: { interval_days: ≈ 家的 70% }
```

**备注**：名字字与身份认同相关，遗忘代价更大（MASTERY-CRITERIA §6）；两字同等级时名字字排期更短。`source='name_plan'` 是判定依据。
