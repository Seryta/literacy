# T002 — 单字教学闭环

覆盖：9 阶段状态机、阶段成功条件、掌握等级裁决（`AGENT-PROTOCOL.md` §6、`MASTERY-CRITERIA.md` §2/§4）。

## GT-020 新字完整 9 阶段闭环

**模块**：character-closed-loop | **优先级**：P0 | **覆盖规则**：§6.1 阶段序列；MASTERY-CRITERIA §4

**前置状态**：
```yaml
learner_profile: { display_name: 张阿姨, learning_path: write_parallel }
lesson_state: { phase: null, current_char: 家 }
```

**事件序列**：
```yaml
- event: SessionStarted                              # → introduce（自动通过）
- event: VoiceInput, payload: { text: "家", intent: RECOGNIZED }          # recognize 正确 → advance_phase
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: recognize, score: 1.0, idempotency_key: gt020-1 } } }   # 认对落库（§6.4 触发点）
- event: StrokeFinished, payload: { stroke: 1 }       # guided_write 逐笔（本地评估）
- event: WritingEvaluated, payload: { phase: guided_write, stroke: 1, ok: true }   # ×10 笔后
- event: WritingEvaluated, payload: { phase: independent_write, score: 0.9 }  # L0 独立写成功
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: independent_write, score: 0.9, prompt_level: none, idempotency_key: gt020-2 } } }   # 独立写落库
- event: VoiceInput, payload: { text: "家就是我的家，一家人住的地方" }   # explain
- event: VoiceInput, payload: { text: "我家有三口人" }                  # sentence
- toolCall: { name: advance_phase }                  # record → decide（自动通过）
- event: CharacterCompleted
```

**期望行为**：
```yaml
state:
  final_phase: decide
storage:
  characters: { 家: { mastery_recognize: 1, mastery_write: 2, status: learning } }
  session_character_results: { 家: 多条记录, 幂等键唯一 }
```

**备注**：完整闭环锁定的主路径——9 阶段全部走完、落库正确；guided_write 由本地逐笔评估生成 WritingEvaluated（不经 LLM 判断每笔）。
真实模式限制（review-06 3-3）：末段 `toolCall: advance_phase`（record→decide）为 mock 表达，真实模式忽略 TimelineOutput 且 decide 到达依赖模型调 advance_phase——fixture 回放中 decide 未达属用例时序，JVM mock 模式仍验证 9 阶段闭环（ReplayRunnerTest GT-020）。

---

## GT-021 recognize 认错 → 澄清 → 认对（提取练习）

**模块**：character-closed-loop | **优先级**：P0 | **覆盖规则**：§6.3 recognize；提取练习

**前置状态**：
```yaml
lesson_state: { phase: recognize, char: 家, attempt: 1 }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "妈", intent: WRONG }    # 错
- event: VoiceInput, payload: { text: "家", intent: RECOGNIZED }    # 对
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: recognize, score: 1.0, idempotency_key: gt021-1 } } }   # 认对落库
- llm_output:
    text: "对，门牌上那个字就是'家'。"
    toolCalls: []
```

**期望行为**：
```yaml
state:
  phase: demonstrate          # 认对后推进
storage:
  characters: { 家: { streak_success: 1, mastery_recognize: 1 } }   # 认对赋值识别等级 1
text:
  not_contains: [真聪明]      # 具体反馈而非空洞表扬
```

**备注**：一次认错不降级；认对后给具体反馈（如"对，门牌上那个字"）并推进。

---

## GT-022 guided_write 逐笔画跟写（StrokeFinished 本地评估流）

**模块**：character-closed-loop | **优先级**：P0 | **覆盖规则**：§1/§4 StrokeFinished → WritingEvaluated

**前置状态**：
```yaml
lesson_state: { phase: guided_write, char: 家, stroke: 4 }   # 第 4/10 笔
```

**事件序列**：
```yaml
- event: StrokeFinished, payload: { stroke: 4, path: [{x: 5, y: 95}, {x: 50, y: 50}, {x: 95, y: 5}] }   # 用户笔画坐标（近似参考方向）
```

**期望行为**：
```yaml
local_handling:
  llm_turn: 0               # StrokeFinished 本身不触发 LLM
  local_eval: true          # 本地规则引擎完成书写评估（坐标 vs 参考特征）
  produces: WritingEvaluated  # 生成 WritingEvaluated 事件
```

**备注**：StrokeFinished 是本地事件；只有评估完成后的 WritingEvaluated 才触发 LLM。逐笔反馈由本地给出（起笔偏左等）。

---

## GT-023 independent_write L0 独立成功 → 书写等级 2

**模块**：character-closed-loop | **优先级**：P0 | **覆盖规则**：MASTERY-CRITERIA §4 掌握检测点

**前置状态**：
```yaml
lesson_state: { phase: independent_write, char: 家, prompt_level: 0 }
ui_state: { grid: 空白, revealStrokes: 0 }
```

**事件序列**：
```yaml
- event: WritingEvaluated, payload: { phase: independent_write, score: 0.9, ok: true }   # 偏差在阈值内
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: independent_write, score: 0.9, prompt_level: none, idempotency_key: gt023-1 } } }
- toolCall: { name: advance_phase }
```

**期望行为**：
```yaml
toolCalls:
  required: [advance_phase, record_result]
toolCall_args:
  record_result: { result: { phase: independent_write, score: 0.9, prompt_level: none } }
storage:
  characters: { 家: { mastery_write: 2, streak_success: 1, current_prompt_level: 0 } }
  session_character_results_count: 1
state:
  phase: explain             # 或按路径进入下一阶段
```

**备注**：L0（无提示）独立完成 → 书写等级 2（初步掌握），但升级到稳定掌握（3）需连续 2 次 L0（见 GT-024 备注）。

---

## GT-024 independent_write L1 提示完成 → 书写等级 1

**模块**：character-closed-loop | **优先级**：P1 | **覆盖规则**：MASTERY-CRITERIA §4 + 衔接说明

**前置状态**：
```yaml
lesson_state: { phase: independent_write, char: 家, prompt_level: 1 }
```

**事件序列**：
```yaml
- event: WritingEvaluated, payload: { phase: independent_write, score: 0.8, prompt_level: hint }
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: independent_write, score: 0.8, prompt_level: hint, idempotency_key: gt024-1 } } }   # 提示完成落库（单次赋值 1）
- toolCall: { name: advance_phase, text: "写得很认真，这次有提示完成，我们继续。" }
```

**期望行为**：
```yaml
storage:
  characters: { 家: { mastery_write: 1, streak_success: 1 } }   # 单次赋值 1，非 2
text:
  not_contains: [已经完全会了]
```

**备注**：锁定的就是 review-03 P1-2 的语义——**单次 L1 成功赋值等级 1**，与等级表"初步掌握(2) 偶尔需要提示"不冲突；升级需连续 2 次成功（streak 达标）。

---

## GT-025 explain 费曼解释（尝试即可）

**模块**：character-closed-loop | **优先级**：P1 | **覆盖规则**：§6.3 explain；费曼学习法

**前置状态**：
```yaml
lesson_state: { phase: explain, char: 家, allowed_actions: [advance_phase, ...] }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "就是一家人住的地方" }
- llm_output:
    text: "说得对，一家人住的地方就是'家'。"
    toolCalls: []
```

**期望行为**：
```yaml
toolCalls:
  required: [advance_phase]
  forbidden: [record_result]      # 解释不判对错、不落分
text:
  contains: [说得, 对]            # 反馈但不过度
state:
  phase: sentence
```

**备注**：explain/sentence 成功条件=尝试即可，不判对错；理解维度由间隔复习中的表现累积（非单次）。

---

## GT-026 sentence 造句

**模块**：character-closed-loop | **优先级**：P1 | **覆盖规则**：§6.3 sentence

**前置状态**：
```yaml
lesson_state: { phase: sentence, char: 家 }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "我家住在三号路" }
```

**期望行为**：
```yaml
toolCalls:
  required: [advance_phase]
state:
  phase: record
```

**备注**：无法口语表达的用户可跳过 sentence（§6.3）；句子内容不判对错，但明显无关的回答可温和拉回。

---

## GT-027 CharacterCompleted → decide 决策下一字

**模块**：character-closed-loop | **优先级**：P0 | **覆盖规则**：§1 CharacterCompleted

**前置状态**：
```yaml
lesson_state: { phase: record, char: 家, 已完成 }
priority_queue: [name_plan: 国, life_pack: 家庭]
```

**事件序列**：
```yaml
- event: CharacterCompleted
- toolCall: { name: show_character, arguments: { char: 国 }, text: "接下来我们学'国'字。" }
```

**期望行为**：
```yaml
toolCalls:
  required: [show_character]
  forbidden: [complete_character]   # 不能重复完成
text:
  contains: [国]                    # 下一目标按优先级队列
state:
  phase: introduce
```

**备注**：CharacterCompleted 后 Agent 按优先级队列（name_plan 优先）决定下一字、复习或结束。

---

## GT-028 降难：连续 2 次卡住 → 提示降一级

**模块**：character-closed-loop | **优先级**：P0 | **覆盖规则**：TEACHING-STRATEGY §2.2；L0-L6 矩阵

**前置状态**：
```yaml
lesson_state: { phase: independent_write, char: 家, prompt_level: 3, stuck_count: 1 }
characters: { 家: { streak_errors: 1 } }   # 第 1 次失败已发生（lesson_state.stuck_count 内存态）
```

**事件序列**：
```yaml
- event: WritingEvaluated, payload: { phase: independent_write, score: 0.4, ok: false }   # 第 2 次失败
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: independent_write, score: 0.4, prompt_level: hint, idempotency_key: gt028-1 } } }   # 失败落库（streak_errors）
- toolCall: { name: show_character, arguments: { char: 家 }, text: "先看我示范，看清楚了再写。" }
```

**期望行为**：
```yaml
state:
  prompt_level: 4           # L3 → L4（完整示范）
text:
  contains: [先, 看, 写]     # 转为示范引导
storage:
  characters: { 家: { streak_errors: 2 } }
```

**备注**：连续 2 次同一阶段卡住才降级；最低提示仍卡住 → L5 拼音 → L6 建议跳过。降难不重新从 L4 开始（仅降一级）。

---

## GT-029 升提示：成功 → 提示升一级

**模块**：character-closed-loop | **优先级**：P1 | **覆盖规则**：TEACHING-STRATEGY §2.2/§4

**前置状态**：
```yaml
lesson_state: { phase: independent_write, char: 家, prompt_level: 4 }
```

**事件序列**：
```yaml
- event: WritingEvaluated, payload: { phase: independent_write, score: 0.9 }
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: independent_write, score: 0.9, prompt_level: full_demo, idempotency_key: gt029-1 } } }   # 成功落库（升提示）
```

**期望行为**：
```yaml
state:
  prompt_level: 3           # L4 → L3（下次少提示）
storage:
  characters: { 家: { current_prompt_level: 3 } }
```

**备注**：成功升一级对应脚手架撤除（L4 完整示范 → L3 逐笔引导 → … → L0）。

---

## GT-030 插单：user_request 优先

**模块**：character-closed-loop | **优先级**：P0 | **覆盖规则**：TEACHING-STRATEGY §1.2；CURRICULUM-DESIGN §3.2

**前置状态**：
```yaml
lesson_state: { phase: guided_write, char: 国 }
priority_queue: [{ type: name_plan, chars: [国] }, { type: life_pack, chars: [家] }]
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "我想学'药'字，药盒上那个", intent: REQUEST_NEW_CHAR }
- toolCall: { name: show_character, arguments: { char: 药 }, text: "好，我们来看'药'字。" }
```

**期望行为**：
```yaml
toolCalls:
  required: [show_character]
toolCall_args:
  show_character: { char: 药 }
text:
  contains: [药]
state:
  phase: introduce
  current_char: 药          # 切换
```

**备注**：user_request 始终最高；当前进度被记录，完成后回到原优先级项（"要不要继续学之前的？"）；同一字包插单 ≥2 次 → Agent 建议切换字包。

---

## GT-031 识主写辅路径：independent_write 改听音选字

**模块**：character-closed-loop | **优先级**：P1 | **覆盖规则**：TEACHING-STRATEGY §3；AGENT-PROTOCOL §6.3

**前置状态**：
```yaml
learner_profile: { learning_path: read_primary }
lesson_state: { phase: independent_write, char: 家, allowed_actions: [..., show_options] }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "我不会写" }
- toolCall: { name: show_options, arguments: { exercise_type: audio_choice }, text: "那我们换一种方式，听一听选一选。" }
```

**期望行为**：
```yaml
toolCalls:
  required: [show_options]          # 路径分支：识别替代书写
toolCall_args:
  show_options: { exercise_type: audio_choice }
text:
  not_contains: [必须, 写出来]       # 不强迫书写
```

**备注**：识主写辅/识读优先路径下，independent_write 成功条件变为听音选字/选字填空（§6.3 路径分支）；用户可随时语音切换路径。

---

## GT-032 独立书写不泄露答案（L0 空白米字格）

**模块**：character-closed-loop | **优先级**：P0 | **覆盖规则**：SYSTEM-PROMPT 独立书写规则

**前置状态**：
```yaml
lesson_state: { phase: independent_write, char: 家 }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "可以开始写了" }
- toolCall: { name: clear_grid, arguments: {} }
- toolCall: { name: show_character, arguments: { char: 家, revealStrokes: 0 }, text: "好，米字格已经清空，请独立写。" }
```

**期望行为**：
```yaml
toolCalls:
  required:
    - name: clear_grid
    - name: show_character
toolCall_args:
  show_character: { char: 家, revealStrokes: 0 }   # 不显示任何笔画
text:
  not_contains: [家字是, 左边, 右边]                # 不口头描述字形
```

**备注**：独立书写 = 空白米字格 + 0 笔揭示；先 clear_grid 再 show_character 的顺序被锁定（SYSTEM-PROMPT 工具指南）。

---

## GT-033 语音切换学习路径（写 → 识主写辅）

**模块**：character-closed-loop | **优先级**：P1 | **覆盖规则**：AGENT-PROTOCOL §6.3；TEACHING-STRATEGY §3.2

**前置状态**：
```yaml
learner_profile: { learning_path: write_parallel }
lesson_state: { phase: independent_write, char: 家 }
```

**事件序列**：
```yaml
- event: VoiceInput, payload: { text: "我今天手不太方便，不写字了", intent: SWITCH_PATH }
- toolCall: { name: show_options, arguments: { exercise_type: audio_choice }, text: "好的，那我们用听音选字来学。" }
```

**期望行为**：
```yaml
storage:
  profile: { learning_path: read_primary }   # 切换为识主写辅
toolCalls:
  required: [show_options]                    # 本轮独立写改听音选字
toolCall_args:
  show_options: { exercise_type: audio_choice }
text:
  not_contains: [必须, 写出来]
```

**备注**：路径可在任何 session 语音切换（§3.2）；切换后独立书写阶段成功条件立即改为听音选字（§6.3），不强迫书写。

---

## GT-034 识读优先路径：independent_write 改选字填空

**模块**：character-closed-loop | **优先级**：P1 | **覆盖规则**：AGENT-PROTOCOL §6.3 read_only 分支

**前置状态**：
```yaml
learner_profile: { learning_path: read_only }
lesson_state: { phase: independent_write, char: 家 }
```

**事件序列**：
```yaml
- toolCall: { name: show_options, arguments: { exercise_id: e9, prompt: "句子缺一个字，选哪个？" } }
- event: ButtonTapped, payload: { exercise_id: e9, selected_option_id: opt_1, is_correct: true, exercise_type: fill_blank }
- toolCall: { name: record_result, arguments: { char: 家, result: { phase: independent_write, exercise_type: fill_blank, score: 1.0, idempotency_key: gt034-1 } } }
```

**期望行为**：
```yaml
toolCalls:
  required: [record_result]
toolCall_args:
  record_result: { result: { phase: independent_write, exercise_type: fill_blank, score: 1.0 } }
storage:
  characters: { 家: { mastery_recognize: 1 } }   # 选字填空正确 → 识别等级 1
  session_character_results_count: 1
state:
  phase: explain             # 按路径分支（识读优先：选字填空通过 → 进入下一阶段）
```

**备注**：read_only 独立书写阶段成功条件 = 选字填空正确（§6.3），不进入书写评估；与 GT-031（read_primary 听音选字）互补覆盖三条路径分支。
