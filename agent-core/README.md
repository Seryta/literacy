# 测试基线框架（第六版）

纯 Kotlin JVM 工程，实现 AGENT-PROTOCOL / MASTERY-CRITERIA 定义的**本地裁决核心**，
作为 golden turn 用例集（`../test-cases/`）的执行引擎与实现阶段的验证基线。

## 范围（本地裁决核心 + 阶段 A）

| 模块 | 对应协议 | 状态 |
|------|---------|------|
| `model/Model.kt` | 9 阶段 / 4 维度 / 学习路径 / 事件 / characters / sessions / name_plan（含 SM-2 字段） | ✅ |
| `engine/PhaseMachine.kt` | §6.2 allowed_actions 校验、§6.3 阶段成功条件（含路径分支） | ✅ |
| `engine/MasteryAdjudicator.kt` | §6.4 掌握等级裁决（升级/降级 + streak 计数） | ✅ |
| `store/InMemoryStore.kt` | §7.1 幂等落库 + sessions 支持（内存版，替代 Room） | ✅ |
| `provider/LlmProvider.kt` | LLM provider 抽象 + Scripted mock | ✅ |
| `provider/HttpLlmProvider.kt` | 真实 provider：OkHttp 传输 + OpenAI 兼容解析（choices 包装）+ text 必填校验（§3.1/§3.2） | ✅ |
| `provider/ProviderConfigLoader.kt` | 配置加载：baseUrl/model + 环境变量 key（pi 同款 openai-completions） | ✅ |
| `provider/Fixtures.kt` | 真实模型输出录制/回放（golden file testing：录制 → fixture → 回放） | ✅ |
| `provider/HttpTransport.kt` | 传输层抽象（OkHttp 实现 / 测试注入 Fake） | ✅ |
| `learning/SpacedRepetition.kt` | SM-2 简化版：最弱维度排期（GT-056）+ 名字字 ×0.7（GT-057）+ 复习队列（§1.2） | ✅ |
| `learning/SessionLifecycle.kt` | 启动刷新：aborted 标记（GT-016）/ 复习队列 / name_plan 派生 / today_brief | ✅ |
| `learning/StrokeEvaluator.kt` | 手写评估规则引擎：坐标特征对比（起笔/收笔/长度/方向），RESEARCH-TECH | ✅ |
| `learning/IntentResolver.kt` | 语音意图本地理解：STT 文本 → 看拼音/插单/切路径 | ✅ |
| `data/HanziRepository.kt` | 字库访问层：SQLite（data/hanzi.db，makemeahanzi 9574 字），zlib 解压，参考笔画骨架线 | ✅ |
| `replay/ReplayRunner.kt` | 事件序列驱动 + 复习模式 + llmTurn 工具执行链（截断/参数校验/拒绝记录/复评/listen/结束流程） | ✅ |
| `replay/SafetyGuard.kt` | §10 越界内容按句过滤（GT-014） | ✅ |
| `replay/ContextBuilder.kt` | §9 隐私上下文构建（learner_name / full_name 不上送，GT-012） | ✅ |
| `replay/Assertions.kt` | 断言器：text / text_tts / phase / prompt_level / mastery / 字段 / 幂等条数 / sessions | ✅ |
| `replay/CaseLoader.kt` | 用例自动解析：时间线（事件 + LLM 输出 + provider 失败交错）+ 完整断言字段 | ✅ |
| `replay/CaseRunner.kt` | 时间线驱动回放 + local_handling / input_guard 断言执行 | ✅ |

**测试**：96 个 JUnit5 测试，全部通过（另有 fixture 回放 / 录制工具按需运行）。

**全量回放（第六版）**：test-cases/ 全部 **53 个 golden turn 用例自动解析 + 批量回放通过**（解析问题 0，回放断言 0 失败）。
覆盖：GT-001~017（协议）、GT-020~034（单字闭环）、GT-040~047（语音）、GT-050~057（复习）、GT-060~064（练习）。

## 构建与测试（按项目规则在容器中执行）

```bash
# 首次建议持久化 gradle 缓存（避免每次重新下载依赖）
docker volume create gradle-cache
docker run --rm -v gradle-cache:/home/gradle/.gradle \
  -v "$PWD/agent-core:/workspace" -v "$PWD/test-cases:/test-cases" \
  -w /workspace gradle:8.10-jdk17 gradle test --no-daemon
```

## 阶段 A 达成说明（第六版）

1. **真实 Provider 接入**：`HttpLlmProvider`（OkHttp 传输 + 完整 JSON 解析 + text 必填 + 错误 → ProviderException）。
   传输层抽象可注入 Fake，请求构建 / 响应解析 / 失败处理无需真实网络即可验证（7 个专项测试）。
2. **text 语义断言激活**：断言从"lastText 非空才执行"改为"用例声明 text 期望才执行"；53 个用例的
   mock LLM 输出补齐 text（`toolCall` 行新增 `text` 字段 / `llm_output` 行），text 断言全部真实执行。
3. **被忽略事件的本地逻辑**：TtsCompleted → listen 预约开麦（GT-040/046）；StrokeFinished → 本地评估 →
   WritingEvaluated（GT-022）；RecognitionLowConfidence → 本地澄清计数（GT-043/047，3 次升级）；
   HelpRequested / IdleTimeout / ConfusableDetected / RecognitionRepeatedFailures / EndRequested 触发 LLM；
   evaluate_writing 复评不重触发不重复裁决（GT-015）；非法工具参数拒绝 + 注入 error（GT-008）；
   越界内容按句过滤（GT-014）；EndRequested + Provider 失败 → 本地兜底结束（GT-011）。
4. **间隔重复算法**：SM-2 简化版——最弱非零维度决定排期（GT-056）、等级 → 间隔（当天/1-3 天/7-14-30 递增/90 天）、
   失败重置 + ease_factor 降低、名字字 ×0.7（GT-057）、复习队列排序（出错 > 过期 > 最弱维度，§1.2）。
5. **启动刷新**：上次 active → aborted + 新 session active（GT-016）、name_plan 进度派生、today_brief 生成。
6. **降难/升提示**：独立写连续 2 次失败 → prompt_level +1（GT-028）；成功 → -1（GT-029）；同步 current_prompt_level。
7. **回放模型修正**：事件与 LLM 输出按时间线交错执行（真实 turn 模型，修复 GT-040 时序）；
   53 个用例中 28 个此前因断言未执行而"假通过"，本次全部转为真实断言通过。

## 测试基线完善（第六版 · 真实化 + 假通过清零）

1. **手写评估转真实**：`RuleStrokeEvaluator` 规则引擎（坐标序列 vs 参考笔画特征对比：
   起笔/收笔位置、长度比、主方向），`onStrokeFinished` 走真实评估；GT-022 补真实坐标样本。
   参考笔画以几何近似占位（字库标准笔画属阶段 B）。
2. **意图解析转真实**：`IntentResolver`（STT 文本 → 看拼音/插单/切路径）；
   用例 VoiceInput 缺省 intent 时由本地理解推导（真实链路 STT→意图）。
3. **签名达标真实化**：连续 2 次独立签名成功 → name_plan.signing_ready（失败重置连续，GT-063）。
4. **假通过清零**：state 断言扩展（mode / current_char / allowed_actions / review_stage / final_phase）；
   toolCall_args 一致性检查（mock 输入与期望参数匹配，防用例自相矛盾）；
   required/forbidden 对 UI/进度工具断言（required=被调用，forbidden=不被调用）；
   name_plan 断言（signing_ready）；next_expected 实现。
5. **用例规范化**：GT-023/034/052/060 补 mock record_result（落库链路真实验证）；
   GT-020 补 decide 推进（9 阶段闭环 final_phase 断言激活）；GT-022 补真实坐标；
   GT-052/063 无效断言修正。
6. **测试 74 → 86**：RealComponentTest（手写评估 5 + 意图解析 5）、签名达标 2。

## 字库集成（第七版）

1. **数据管线**：`data/build_hanzi_db.py` 将 makemeahanzi（9574 字，LGPL-3.0 + Arphic）
   转为 SQLite（39MB → 18MB，zlib 压缩笔画数据）；含拼音 / IDS 结构拆解（难字拆分）/ 部首 / 释义 / 笔画 SVG / 骨架线。
2. **HanziRepository**：单字按需查询（不全部入内存）；`referenceStrokes` 用 medians（书写轨迹骨架）
   作评估参考——修正对 strokes（轮廓，UI 渲染用）的误用。
3. **手写评估真实化**：`onStrokeFinished` 参考笔画从几何占位升级为字库真实骨架线；
   测试用真实数据验证（完美匹配 / 轻微偏差通过 / 方向相反失败）。
4. **许可**：THIRD-PARTY-NOTICES.md 记录数据来源；App「设置 → 关于」页展示。

## 真实 LLM 端到端（第七版）

1. **配置化**：ProviderConfig baseUrl 驱动（兼容 pi 同款 openai-completions，deepseek 实测）；
   配置从 provider-config.json + 环境变量取 key（key 不入 git）。
2. **OpenAI 兼容解析**：实测发现 deepseek 返回标准格式（choices[0].message.content）而非顶层
   {text, toolCalls}——parseResponse 兼容包装层 + 内嵌业务 JSON（含 markdown 代码块包裹）。
3. **真实驱动模式**：CaseRunner 注入 LlmProvider 后，触发 LLM 的事件主动调用真实模型
   （替代 mock llmScript）；上下文注入 lesson_state（阶段/字/允许动作/提示等级）。
4. **fixture 录制/回放**：真实 provider 跑一次 → 输出录为 fixtures/GT-xxx.json → 回放验证
   （text 教学语质量 review + 本地裁决稳定性）。49 个用例真实快照已入库。
5. **提示词迭代（两轮）**：真实模型从"只会说话"到正确调工具——
   - 迭代 1：成功条件 → 必须 advance_phase；学习尝试 → 必须 record_result（GT-003/010 打通）
   - 迭代 2：introduce 先展示再推进、识别失败不落库（GT-042 show_options 降难）、复习判题落库
   - 架构修正：真实模式关闭事件自动推进（autoAdvance=false），推进只由 LLM 决策触发
   - 当前基线：认读推进/落库/降难/复习进入全部打通；剩余差异（复习优先、
     复习模式内推进）需完整 prompt 构建（today_brief 等，属 Android App 层）

## Android App（第八版 · 最小闭环）

- **构建链**：`docker/android-sdk.dockerfile`（SDK 34，组件宿主预下载 COPY）→ `literacy-android` 镜像；
  `android-app/` 工程（app 模块 kotlin.srcDir 共享 agent-core 源码，容器路径 /agent-core）
- **学习闭环**：AgentOrchestrator（事件 → 本地裁决 → 真实 LLM → 工具执行）+ LearnScreen
  （当前字/拼音/阶段/教学语）+ MizigeGrid（字库 SVG 笔画按阶段揭示 + 手写轨迹）+
  TTS 朗读（Android 原生，标准普通话）
- **输入**：首版文本框模拟语音（STT 接入后替换）；操作按钮：帮助/跳过/暂停/结束
- **LLM**：HttpLlmProvider（deepseek 配置占位）；API key 未配时 Provider 失败走本地兜底（§7.2）
- **未做**：Room 持久化（内存态，后续替换）、STT/TTS 引擎 POC、建档页、运行验证（需设备）

## review-05 修复（2026-08-02）

承接全量评审（reviews/review-05.md），P0/P1 全部闭环、P2 代码类完成：

| 项 | 修复 |
|----|------|
| P0-3 | advance_phase 语义：事件到达时判定成功条件（phaseReady），advance_phase 只做迁移 |
| P0-1 | end_session 结构化总结（highlights/struggles/name_plan_progress）解析落库 |
| P0-2 | sessionId 动态归属 latestSession + Android sessionRefresh（aborted 检测 + active 创建） |
| P1-1 | 复习轮 record_result → nextSchedule + scheduleNextReview（next_review 推进） |
| P1-2 | 结束请求 Provider 失败 → 本地兜底结束（session 不遗留 active） |
| P1-3 | buildContext 注入 name_plan（ContextBuilder 隐私边界） |
| P1-4 | recordResultWithUpsert 原子事务（§7.1，Room @Transaction） |
| P1-5 | SkipRequested 真实模式不硬编码（避免双重 turn） |
| P2-1 | fixture 全量重录（恢复回放信号） |
| P2-2/3/4/6/7 | viewModel key 去敏感 / key 掩码 / updateSession 补全 / 认读模糊 / 裁决匹配 |
| P2-5/8 | guided_write 逐笔 / StrokeEvaluator 特征——已知简化，留待迭代 |

模拟器端到端验证：sessions `completed` + highlights/struggles/name_plan_progress 落库，
模型总结引用姓名目标（P1-3 生效）。

## 新评审修复（2026-08-02，main@41776de 全量只读评审）

16 P1 + 12 P2 全量处理：

| 类 | 项 | 状态 |
|----|-----|------|
| 数据幂等与信任边界 | P1-1 Room 幂等唯一索引 / P1-2 record_result 校验（char/phase/score）/ P2 status 派生 | ✅ |
| session 归属 | P1-4 真实时间 / P1-5 runner 绑定 session / P2 end_session 原子（endedAt） | ✅ |
| 书写状态机 | P1-6 笔画累计 / P1-7 缺笔拒绝+轨迹重置 / P1-8 坐标统一 | ✅ |
| 复习/维度 | P1-10 复习队列接线+首项消费 / P1-11 复习升降 mastery / P1-12 explain/sentence 维度 | ✅ |
| 并发与安全 | P1-13 TTS 过滤文本 / P1-14 并发控制+暂停禁用 / P1-9 complete_character 事件 / P2 onTtsCompleted / P2 兜底话术 | ✅ |
| Android 生命周期 | P1-15 Profile 主线程 / P1-16 同字重学+配置生效 / P2 displayName / P2 allowBackup / P2 JSON 转义 | ✅ |
| 基础设施 | P2 字库构建复现 / Makefile check 含 Android | ✅ |

标注项（留待迭代）：P1-3 普通学习证据-mastery 原子（需裁决时机重构）、
字库升级替换、Android instrumented test、dockerfile zip 依赖宿主预下载、
HomeScreen 重组查询性能。

模拟器验证：sessions 真实时间（active 2026-08-02）、学习链路正常。

## 新评审修复（2026-08-02 · 第三轮，b35624c 全量复核）

17 P1 + 18 P2：明确 bug 批已修（12 项），架构级标注，1 项实测误报。

已修：
- P1-1 跟写注入字库 + 笔序（非 promptLevel）/ P1-3 独立写笔画数完全一致 /
  P1-4 独立写与 RECALL 隐藏答案 / P1-5 Room 真实 migration（不清数据）/
  P1-8 复习 mastery 升降接线（reviewDimension 调用，裁决统一到 record_result）/
  P1-9 复习首字消费 + next 仅 NEXT 阶段 / P1-10 结束绑定 boundSessionId /
  P1-13 enterCount 递增（重学/配置刷新生效）/ P1-14 米字格门禁 /
  P1-15 make record key 经 --env-file（不泄漏）/ P2-11 提示等级恢复 /
  P2-18 日志截断（隐私）
- P1-2 坐标系方向：**实测误报**（模拟器'张'字方向正常，Y 轴一致）

架构级标注（需设计决策/大重构）：
- P1-6 幂等键 App 签发（attempt token 机制）、P1-7 普通学习 mastery 与证据
  原子（裁决时机重构）、P1-11 complete_character 下一字闭环（App 编排）、
  P1-12 声明工具 no-op（show_* UI 工具）、P1-16 签字场景校验、P1-17 全局
  streak 四维分离（Model 重构）
- P2 剩余：测试基建（Android test / make test inputs）、明文 key 加密、
  字库升级替换、HomeScreen/MizigeGrid 性能、Provider 严格 JSON 等

## 架构级处理完成（2026-08-02）

- **P1-6+P1-7**：record_result 统一入口——App 签发 idempotencyKey（注入
  lesson_state，record_result 校验匹配，幂等预检同 key 整次跳过）；掌握裁决
  统一到 record_result（§6.4 触发点，事件不再裁决）；维度推导 + 裁决 + 证据
  原子（recordResultWithUpsert）；副作用（降难/升提示）仅首次落库。用例迁移
  （GT-020/021/024/028/029 补 record_result），53 用例全过。
- **P1-11**：complete_character 后自动触发整字完成决策 turn（App 编排）。
- **P1-12**：声明工具（show_*）记录 recentUiTools 供 App 渲染（不再静默忽略）。
- **P1-16**：签名达标需 name_plan + 无提示 + 连续 2 次。
- **P1-17**：文档澄清 streak 按目标维度独立（per-dimension 待 schema 演进）。
- fixture 全量重录（新裁决语义），回放 20 干净 + 已知模型行为差异标注。

## review-07 修复（2026-08-02，第六轮全量评审）

- **P0-1**：App 每次尝试签发 UUID 幂等键注入 lesson_state（beginAttempt），
  buildContext 携带；record_result 严格校验回传 key——模拟器实测模型自造
  key 被拒（防线生效；模型不遵守需 error 闭环+提示词迭代）
- **P1-1**：学习轮 record_result 也排期 next_review（等级1→当天）——复习
  队列生产链路不再为空
- **P1-2**：buildContext 注入 learner_profile（称呼）+ today_brief +
  current_session_results（EndRequested 聚合证据）
- **P1-3**：拒绝原因 rejectReasons 注入下一 turn（§10 error 承诺）；提示词
  明确 record_result 内嵌结构与 score 0-1
- **P1-4**：completeSession 统计 chars_learned/chars_reviewed/duration
  （证据聚合 + endedAt-startedAt，不再恒 0）
- **P2**：README 标题去重 / 米字格揭示权威笔数 / 首页复习队列入口
- 模拟器验证：P0-1 防线实测（模型自造 key 被拒）

## review-08 修复（2026-08-02，第七轮代码级验收）

- **P1-A**：ProfileScreen 建档写 settings.displayName（此前只写 namePlan，
  displayName 恒空 → learner_profile 死代码）——模拟器实测开场
  「张阿姨，您好！」个性化称呼生效（评审误判根因：LearnViewModel 实参
  早已接线，真实缺环在建档不写 displayName）
- **P1-B**：AgentOrchestrator.buildContext 注入 rejectReasons（§10 error
  闭环生产链路，格式对齐 CaseRunner）
- **P2-A**：countLearnedChars 排除 assess/reinforce——新学/复习口径分离
- **P2-B**：duration 跨天补一天（不再 coerce 成 0）
- **P2-C**：复习模式 record_result phase 校验落地（assess/reinforce）
- **P2-D**：beginAttempt 限定真实尝试（help/skip/pause/end 不签发 key）

## review-10 修复（2026-08-03，第十轮复审 P0+P1×14+P2×6）

- **P0**：v3 schema 原地修改崩溃——升 v4 + MIGRATION_3_4（全局→复合索引），
  历史 2.json/3.json 恢复不可变（3.json hash ee8a5e）；instrumented 新增 3→4 迁移测试
- **P1**：本地权威持久化（attempt 绑定按阶段 phase/dimension/issues，落库统一
  本地值）；skip 例外（null score 不裁决不排期）；复习 phase 按 reviewStage
  校验 + assess 维度按题型；跟写先累计再判定 + 当前笔显示 + Y 锚点 900
  （数据范围 -124~900）；选择题本地判题（correct=目标字）+ 一次性消费；
  REQUEST_NEW_CHAR/SWITCH_PATH 生效；streak 判定达标过滤（L3 认对仍累计）；
  nextCharSelector 排除当前字；空称呼不兜底姓名；UI 显示过滤文本；clear_grid
  序号消费；busy 不丢 pause/end + 旋转保留计数 + 离页取消；事务保留 pinyin；
  release 签名缺失构建失败；SDK 镜像标准 platform-34（离线构建通过）
- **P2**：名字字间隔存未折扣档位（日期应用 ×0.7 不卡档）；ProfileScreen 字库
  检查移 IO；readApiKey 异常捕获；ATOMIC_MOVE 原子替换；check 加 androidtest
  门禁；ReplayV2Test 类外测试移回（P1-1 测试此前未被发现）；pii metadata
  白名单校验
- 验证：JVM 110 全绿 + instrumented 13/13 + lint 过 + 离线构建 + make pii 通过

## 已知取舍
## 已知取舍
## 已知取舍
## 已知取舍

- text 语义断言：用例声明 text 期望时才执行（mock 输出提供 text）；真实 LLM 输出
  验证走 fixture 录制/回放（RecordFixturesTest 录制，FixtureReplayTest 回放 review）
- 真实模型工具调用行为（调哪些工具、何时调）由提示词引导，fixture 差异报告是迭代信号；
  mock 模式仍负责穷举本地防御（任何 LLM 输出下本地裁决正确）
- 手写评估参考笔画以几何近似占位（直线），字库标准笔画数据属阶段 B；评估算法（特征对比）本身是真实实现
- GT-052 复习阶段推进（next 或 reinforce）由真实 LLM 决策，mock 模式不精确断言，落库链路已锁定
- input_guard 的 raw_audio_upload 为架构约束（上下文构建无音频上传路径），驱动层不单独验证
- StrokeFinished 本地评估为简化版（笔画完成即 ok，逐笔反馈属 UI 层）；复评在无 prior 评估时
  从当前阶段生成结果
- 降难/升提示按用例锁定的语义实现（连续 2 次失败 / 单次成功），参数为基线值，后续可按真实教学调优
- 插单目标字从语音文本提取（'X' 引号内），依赖用例文本格式约定
- 复杂 Agent 决策用例（如 GT-064 难字拆解回退）由 mock 编排 Agent 行为，非真实 LLM 决策

## 设计说明

- 本地裁决逻辑**不信任 Agent**：allowed_actions 按阶段裁剪（complete_character 仅 decide 可用），
  不在集合内的动作静默拒绝（GT-009）；工具参数本地校验（GT-008）
- 掌握等级裁决只消费本地首次评估结果（复评不重复裁决，GT-015 语义）
- 无 Android 依赖，domain 层可在 JVM 直接测试，后续可整体移入 Android app 的 domain 层
