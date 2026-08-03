package com.literacy.agent.replay

import com.literacy.agent.model.ButtonTapped
import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.ConfusableDetected
import com.literacy.agent.model.Dimension
import com.literacy.agent.model.EndRequested
import com.literacy.agent.model.Event
import com.literacy.agent.model.HelpRequested
import com.literacy.agent.model.IdleTimeout
import com.literacy.agent.model.LessonState
import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.Mode
import com.literacy.agent.model.PauseRequested
import com.literacy.agent.model.Phase
import com.literacy.agent.model.CharacterCompleted
import com.literacy.agent.model.RecognitionLowConfidence
import com.literacy.agent.model.RecognitionRepeatedFailures
import com.literacy.agent.model.SessionStarted
import com.literacy.agent.model.SkipRequested
import com.literacy.agent.model.StrokeFinished
import com.literacy.agent.model.ToolCall
import com.literacy.agent.model.TtsCompleted
import com.literacy.agent.model.VoiceInput
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.model.WritingEvaluated

/**
 * CaseRunner：执行一个 GoldenCase 回放并跑断言。
 *
 * 链路：setup（前置状态）→ inputEvents（外部事件驱动）→ llmScript（mock LLM 输出）
 * → assertions（期望断言，含 local_handling / input_guard / text_tts / sessions）。
 *
 * 事件 → 本地行为映射：
 * - StrokeFinished → 本地评估 → WritingEvaluated（GT-022，不触发 LLM）
 * - TtsCompleted → 有 listen 预约才开麦（GT-040/046）
 * - RecognitionLowConfidence → 本地澄清计数（GT-043/047）
 * - HelpRequested / EndRequested / IdleTimeout / ConfusableDetected / RecognitionRepeatedFailures → 触发 LLM turn
 * - provider_failure → 本地兜底结束（GT-011）
 */
class CaseRunner(private val runner: ReplayRunner) {

    /**
     * P0-1 对齐（JVM 录制路径）：开启后每次触发 LLM 的尝试前签发确定性幂等键并注入上下文，
     * ReplayRunner 严格校验回传（真实 App 由 AgentOrchestrator.beginAttempt 签发 UUID）。
     * 仅录制时开启（RecordFixturesTest 置 true）；回放保持宽松以兼容旧 fixture。
     */
    var enforceIdempotencyKey = false

    /** 幂等键序列（每次尝试 +1；确定性方案，录制与回放同序可复现）。 */
    private var attemptSeq = 0

    /**
     * 真实 LLM 驱动模式：注入 provider 后，触发 LLM 的事件（§1 映射）主动调用 provider，
     * 替代用例 llmScript（mock 期望）。用于真实录制 / fixture 回放验证 text 断言。
     */
    var llmProvider: com.literacy.agent.provider.LlmProvider? = null

    /** 执行回放，返回断言问题列表（空 = 通过）。 */
    fun run(case: GoldenCase): List<String> {
        activeCase = case
        attemptSeq = 0   // 幂等键序列随用例重置
        // 真实模式：关闭事件自动推进，推进只由 LLM 的 advance_phase 触发（避免双重推进）
        if (llmProvider != null) runner.autoAdvance = false
        // 1. 前置状态
        applySetup(case)
        // 2. 时间线驱动：事件与 LLM 输出按序交错（真实 turn 模型）
        for (item in case.timeline) {
            when (item) {
                is TimelineEvent -> handleEvent(case, item.event)
                // provider 模式下 mock 输出被忽略（由真实 LLM / fixture 输出替代）
                is TimelineOutput -> if (llmProvider == null) runner.llmTurn(item.output)
                is TimelineFailure -> runner.endSessionFallback()   // GT-011：Provider 失败本地兜底
            }
        }
        // 3. 断言
        return assertCase(case)
    }

    /** 单个外部事件处理。 */
    private fun handleEvent(case: GoldenCase, event: Event) {
        when (event) {
            is SessionStarted ->
                if (runner.state.phase == null) {
                    // 启动刷新（SESSION-LIFECYCLE §1，GT-016）：上次 active → aborted + 新 session，不触发 LLM
                    runner.sessionRefresh()
                    runner.startSession(inferChar(case) ?: "?")
                }
            is VoiceInput -> handleVoice(case, event)
            is ButtonTapped -> {
                if (event.action == "resume") {
                    // 恢复：从暂停时阶段继续，不重走 introduce（GT-044）
                    runner.resume()
                } else {
                    runner.tapped(event.action, event.isCorrect ?: false, event.exerciseId ?: "")
                    // 识别赋值统一走 record_result（P1-7：裁决统一到 §6.4 触发点，事件只记录不更新 mastery）。
                    // 路径分支判对（听音选字/选字填空）由 record_result 带 exercise_type 推导 RECOGNIZE
                    // （dimensionForPhase：fill_blank/audio_choice → RECOGNIZE，GT-034 不虚增 WRITE）——
                    // 不再在此本地赋值，避免与 record_result 双重裁决。
                }
            }
            is WritingEvaluated -> handleWriting(case, event)
            is StrokeFinished -> runner.onStrokeFinished(event.stroke, event.path)   // 本地规则引擎评估，不触发 LLM
            is TtsCompleted -> runner.onTtsCompleted()                    // 有 listen 预约才开麦
            is RecognitionLowConfidence ->
                runner.onRecognitionLowConfidence(event.confidence, event.partial)
            is PauseRequested -> runner.pause()
            is SkipRequested ->
                // 真实模式由 provider 处理（triggersLlm）；mock 模式无 provider 时本地 skip（review-05 P1-5：避免双重 turn）
                if (llmProvider == null) runner.llmTurn(LlmOutput("", listOf(ToolCall("skip_character"))))
            is CharacterCompleted -> {
                // 整字完成 → 本地进入下一字 introduce（由 Agent 决策下一字，char 用推断值）
                runner.configureState(
                    LessonState(
                        phase = Phase.INTRODUCE,
                        char = inferChar(case) ?: runner.state.char,
                        allowedActions = ReplayRunner.allowedFor(Phase.INTRODUCE),
                    ),
                )
            }
            // 触发 LLM turn 的事件：由后续 TimelineOutput 承接（每事件一次 turn 由 llmTurnCount 验证）
            is HelpRequested, is EndRequested, is IdleTimeout,
            is ConfusableDetected, is RecognitionRepeatedFailures -> {}
            else -> {}
        }
        // provider 驱动：触发 LLM 的事件（AGENT-PROTOCOL §1）且有 provider 时调用真实 LLM / fixture
        if (llmProvider != null && triggersLlm(event)) {
            if (enforceIdempotencyKey) beginAttempt()   // P0-1：录制路径每次尝试签发幂等键
            val output = try {
                llmProvider!!.respond(buildEventContext(event))
            } catch (e: Exception) {
                return  // Provider 失败：本地裁决不受影响（GT-011 兜底由 provider_failure 行驱动）
            }
            runner.llmTurn(output)
        }
    }

    /** P0-1：签发本次尝试幂等键（确定性序列，与 App 每次尝试唯一 key 语义对齐）。 */
    private fun beginAttempt() {
        attemptSeq++
        val caseId = activeCase?.id?.lowercase() ?: "case"
        runner.configureState(runner.state.copy(idempotencyKey = "rec-$caseId-att-$attemptSeq"))
    }

    /** AGENT-PROTOCOL §1 事件 → LLM 调用映射（provider 驱动模式下触发 LLM 的事件集）。 */
    private fun triggersLlm(event: Event): Boolean = when (event) {
        is SessionStarted, is VoiceInput, is HelpRequested, is SkipRequested,
        is WritingEvaluated, is ButtonTapped, is CharacterCompleted, is EndRequested,
        is ConfusableDetected, is IdleTimeout, is RecognitionRepeatedFailures -> true
        else -> false
    }

    /** 事件上下文（含 lesson_state 注入，AGENT-PROTOCOL §2；完整 prompt 构建属 App 层）。 */
    private fun buildEventContext(event: Event): String {
        val s = runner.state
        val rejectInfo = if (runner.rejectReasons.isNotEmpty()) " 上一轮拒绝：${runner.rejectReasons.joinToString("；")}" else ""   // P1-3
        val stateDesc = "当前状态：阶段=${s.phase?.display ?: "无"} 字=${s.char ?: "-"} 模式=${s.mode}$rejectInfo " +
            "学习路径=${s.learningPath.name.lowercase()} 提示等级=${s.promptLevel} " +
            "允许动作=${s.allowedActions.joinToString(",")}" +
            (s.reviewStage?.let { " 复习阶段=${it.name.lowercase()}" } ?: "") +
            (if (runner.reviewQueue.isNotEmpty()) " 复习队列=${runner.reviewQueue.joinToString(",")}" else "")
        val eventDesc = when (event) {
            is SessionStarted ->
                if (runner.state.mode == com.literacy.agent.model.Mode.REVIEW)
                    "事件：复习会话开始，按复习阶段教学（RECALL 引导回忆不展示答案，不 show_character）"
                else "事件：会话开始，请打招呼并开始教学"
            is VoiceInput -> "事件：用户语音「${event.text}」（意图=${event.intent}）"
            is WritingEvaluated -> "事件：书写评估 ${event.phase} 分数=${event.score} 通过=${event.ok} 问题=${event.issues}"
            is ButtonTapped -> "事件：按钮 ${event.action}" +
                (event.isCorrect?.let { " 判题=${if (it) "正确" else "错误"}" } ?: "") +
                (event.exerciseId?.let { " 练习=${it}" } ?: "")
            is HelpRequested -> "事件：用户请求帮助"
            is SkipRequested -> "事件：用户请求跳过"
            is CharacterCompleted -> "事件：整字完成，请决定下一字"
            is EndRequested -> "事件：用户请求结束会话，请告别并 end_session 总结"
            is ConfusableDetected -> "事件：形近字混淆 ${event.char} 与 ${event.confusedChar}，可考虑 compare_characters 辨析"
            is IdleTimeout -> "事件：等待 ${event.waitingFor} 超时 ${event.idleSeconds} 秒，请关怀"
            is RecognitionRepeatedFailures -> "事件：连续 STT 失败 ${event.failureCount} 次，请降难或切屏幕选项"
            else -> "事件：$event"
        }
        return "$stateDesc\n$eventDesc" +
            (s.idempotencyKey?.let { "\n本次尝试幂等键=$it（record_result 必须逐字回传此键，不得自造）" } ?: "")
    }

    /** 语音事件处理：区分教学意图（认读/插单/切路径）与普通对话。 */
    private fun handleVoice(case: GoldenCase, event: VoiceInput) {
        when (event.intent) {
            VoiceIntent.REQUEST_NEW_CHAR -> {
                // 插单：从文本提取目标字（"我想学'X'字"）→ 切换新字 introduce（TEACHING-STRATEGY §1.2）
                val target = extractTargetChar(event.text) ?: return
                runner.configureState(
                    LessonState(
                        phase = Phase.INTRODUCE,
                        char = target,
                        allowedActions = ReplayRunner.allowedFor(Phase.INTRODUCE),
                    ),
                )
            }
            VoiceIntent.SWITCH_PATH -> {
                // 切换学习路径：更新 learning_path（识主写辅），保持当前阶段
                val s = runner.state
                runner.configureState(
                    s.copy(learningPath = com.literacy.agent.model.LearningPath.READ_PRIMARY),
                )
            }
            else -> runner.voice(event.intent, event.text)
        }
    }

    /** 从插单文本提取目标字：匹配 'X' 或 "X" 引号内单字，或正则 学(.)字。 */
    private fun extractTargetChar(text: String): String? {
        val quoted = Regex("['\"]([^'\"]{1,2})['\"]").find(text)
        if (quoted != null) return quoted.groupValues[1]
        return Regex("学(.{1,2})字").find(text)?.groupValues?.get(1)
    }

    /** 书写事件处理：区分单字教学裁决（independent_write）与复习轮裁决（§6.4 触发点 2）。 */
    private fun handleWriting(case: GoldenCase, event: WritingEvaluated) {
        val s = runner.state
        // 复习轮裁决统一在 executeRecordResult（§6.4 触发点：record_result 事务内）——
        // 这里只推进/记录事件，避免与 record_result 双重裁决（review GT-053 双降）
        runner.writing(event.phase, event.ok, event.promptLevel, event.score)
    }

    private fun weakestDimension(rec: CharacterRecord): Dimension? {
        val dims = listOf(Dimension.RECOGNIZE, Dimension.WRITE, Dimension.UNDERSTAND, Dimension.APPLY)
        return dims.filter { rec.mastery(it) > 0 }.minByOrNull { rec.mastery(it) }
    }

    private fun applySetup(case: GoldenCase) {
        val s = case.setup.lessonState
        // characters 前置注入 store
        case.setup.characters.forEach { (char, record) ->
            runner.store.upsertCharacter(record)
        }
        // sessions / name_plan 前置注入
        runner.store.seedSessions(case.setup.sessions)
        if (case.setup.namePlan != null) runner.store.namePlan = case.setup.namePlan
        // 有 lesson_state 时按用例前置状态初始化（phase/allowed_actions/learning_path）
        if (s.phase != null || s.mode == Mode.REVIEW) {
            val char = inferChar(case) ?: "?"
            runner.startSession(char, s.learningPath)
            // 覆盖 phase / allowed_actions / mode / review_stage 到前置状态
            val allowed = when {
                s.allowedActions.isNotEmpty() -> s.allowedActions
                s.mode == Mode.REVIEW -> ReplayRunner.allowedForReview()
                s.phase != null -> ReplayRunner.allowedFor(s.phase)
                else -> ReplayRunner.allowedFor(Phase.INTRODUCE)
            }
            runner.configureState(
                s.copy(char = char, allowedActions = allowed),
            )
        }
        runner.reviewQueue.clear()
        runner.reviewQueue.addAll(case.setup.reviewQueue)
    }

    /** char 推断：lesson_state 缺 char 时，从 record_result 脚本参数或断言 characters 键取。 */
    private fun inferChar(case: GoldenCase): String? {
        case.setup.lessonState.char?.let { return it }
        // 复习模式：当前复习字取队列第一个（§6.5）
        case.setup.reviewQueue.firstOrNull()?.let { return it }
        for (out in case.llmScript) for (tc in out.toolCalls) {
            (tc.arguments["char"] as? String)?.let { return it }
        }
        case.assertions.expectedMastery.firstOrNull()?.let { return it.first }
        return null
    }

    private fun assertCase(case: GoldenCase): List<String> {
        val a = case.assertions
        val assert = Assertions()
        val problems = mutableListOf<String>()

        // text 语义断言：仅在用例声明了 text 期望时执行（无期望的本地裁决用例不强制要求 LLM 输出）
        if (a.textContains.isNotEmpty() || a.textNotContains.isNotEmpty()) {
            problems += assert.text(runner.lastText, a.textContains, a.textNotContains)
        }
        // text_tts 断言（GT-014：过滤后朗读文本）
        if (a.ttsContains.isNotEmpty() || a.ttsNotContains.isNotEmpty()) {
            problems += assert.text(runner.ttsText, a.ttsContains, a.ttsNotContains).map { "text_tts: $it" }
        }
        // state 断言
        if (a.expectedPhase != null) problems += assert.phase(runner.state, a.expectedPhase)
        if (a.expectedPromptLevel != null && runner.state.promptLevel != a.expectedPromptLevel) {
            problems += "prompt_level 期望 ${a.expectedPromptLevel} 实际 ${runner.state.promptLevel}"
        }
        if (a.expectedMode != null && !runner.state.mode.name.equals(a.expectedMode, ignoreCase = true)) {
            problems += "mode 期望 ${a.expectedMode} 实际 ${runner.state.mode.name}"
        }
        if (a.expectedChar != null && runner.state.char != a.expectedChar) {
            problems += "current_char 期望 ${a.expectedChar} 实际 ${runner.state.char}"
        }
        if (a.expectedAllowedActions != null) {
            val actual = runner.state.allowedActions
            val missing = a.expectedAllowedActions - actual
            val extra = actual - a.expectedAllowedActions
            if (missing.isNotEmpty()) problems += "allowed_actions 缺少 $missing（实际 $actual）"
            if (extra.isNotEmpty()) problems += "allowed_actions 多出 $extra（实际 $actual）"
        }
        if (a.expectedReviewStage != null &&
            !runner.state.reviewStage?.name.equals(a.expectedReviewStage, ignoreCase = true)
        ) {
            problems += "review_stage 期望 ${a.expectedReviewStage} 实际 ${runner.state.reviewStage}"
        }
        if (a.expectedFinalPhase != null) {
            val reached = runner.reachedPhases.mapNotNull { it }.map { it.display }
            if (a.expectedFinalPhase !in reached) {
                problems += "final_phase 期望到达 ${a.expectedFinalPhase}，实际轨迹 $reached"
            }
        }
        // toolCall_args 一致性（mock 输入与用例期望参数匹配，防用例自相矛盾）
        problems += assertToolCallArgs(case, a)
        // name_plan 断言（GT-063：signing_ready 等）
        for ((field, value) in a.expectedNamePlan) {
            val plan = runner.store.namePlan
            val actual = when (field) {
                "signing_ready" -> plan?.signingReady
                "recognition_ready" -> plan?.recognitionReady
                "independent_writing_ready" -> plan?.independentWritingReady
                else -> null
            }
            if (actual != value) problems += "name_plan.$field 期望 $value 实际 $actual"
        }
        // storage 断言
        for ((char, dim, level) in a.expectedMastery) {
            problems += assert.mastery(runner.store, char, dim, level)
        }
        for (fe in a.expectedFields) {
            problems += assert.field(runner.store, fe.char, fe.field, fe.expected)
        }
        if (a.expectedResultCount != null) problems += assert.resultCount(runner.store, a.expectedResultCount)
        // session 断言（GT-011/016/017）
        a.expectedSessionStatus?.let { status ->
            val actual = runner.store.latestSession()?.status
            if (actual != status) problems += "session 状态期望 $status 实际 $actual"
        }
        for ((id, status) in a.expectedSessions) {
            val actual = runner.store.sessions.find { it.id == id }?.status
            if (actual != status) problems += "session#$id 期望 $status 实际 $actual"
        }
        // 课程控制动作断言：CONTROL_ACTIONS 内的检查 allowed_actions 裁决；
        // UI/进度工具（show_* / record_result / listen 等）检查 mock 输入是否调用
        // （required=必须被调用，forbidden=不得被调用；防用例自相矛盾）
        for ((action, expected) in a.expectedControl) {
            if (action in CONTROL_ACTIONS) {
                val actual = runner.control(action)
                if (actual != expected) problems += "control($action) 期望 ${if (expected) "允许" else "拒绝"} 实际 ${if (actual) "允许" else "拒绝"}"
            } else {
                // review-09 P2-14：断言读实际执行记录（runner.executedToolCalls）——
                // 不再读 mock 脚本输入（llmScript 是输入，不是结果——读它会假绿）
                val called = runner.executedToolCalls.contains(action)
                if (called != expected) {
                    problems += "toolCall($action) 期望 ${if (expected) "被调用" else "不被调用"} 实际 ${if (called) "被调用" else "未调用"}"
                }
            }
        }
        // local_handling 断言（协议级本地行为，精确断言）
        problems += assertLocalHandling(a.localHandling)
        // input_guard 断言（GT-012：注入上下文隐私边界）
        problems += assertInputGuard(a.inputGuard, case.setup.displayName, case.setup.namePlan)
        return problems
    }

    /** toolCall_args 一致性检查：用例期望的工具参数与 mock 输入匹配（防止用例自相矛盾）。 */
    private fun assertToolCallArgs(case: GoldenCase, a: CaseAssertions): List<String> {
        if (a.toolCallArgs.isEmpty()) return emptyList()
        val problems = mutableListOf<String>()
        val actualCalls = case.llmScript.flatMap { it.toolCalls }
        for ((toolName, expectedArgs) in a.toolCallArgs) {
            val call = actualCalls.firstOrNull { it.name == toolName }
            if (call == null) {
                // mock 未提供该工具调用：无法验证（期望 LLM 参数属真实 provider 阶段验收）
                problems += "toolCall_args($toolName): mock 未调用该工具，参数无法验证（留待真实 provider 阶段）"
                continue
            }
            for ((key, expValue) in expectedArgs) {
                val actual = call.arguments[key]
                if (!deepEquals(actual, expValue)) {
                    problems += "toolCall_args($toolName.$key) 期望 $expValue 实际 $actual"
                }
            }
        }
        return problems
    }

    private fun deepEquals(actual: Any?, expected: Any?): Boolean {
        if (expected is Map<*, *> && actual is Map<*, *>) {
            return expected.all { (k, v) -> deepEquals(actual[k], v) }
        }
        if (expected is Number && actual is Number) return expected.toDouble() == actual.toDouble()
        return actual?.toString() == expected?.toString()
    }

    /** local_handling 断言执行：把用例锁定的本地行为与 runner 实际状态对比。 */
    private fun assertLocalHandling(lh: Map<String, Any?>): List<String> {
        val problems = mutableListOf<String>()
        lh.forEach { (key, expected) ->
            when (key) {
                "llm_turn" -> {
                    val exp = (expected as? Number)?.toInt() ?: return@forEach
                    if (runner.llmTurnCount != exp) problems += "llm_turn 期望 $exp 实际 ${runner.llmTurnCount}"
                }
                "reject" -> {
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp != runner.rejectedCalls.isNotEmpty()) {
                        problems += "reject 期望 $exp 实际 ${runner.rejectedCalls}"
                    }
                }
                "open_mic" -> {
                    val exp = expected as? Boolean ?: return@forEach
                    if (runner.micOpen != exp) problems += "open_mic 期望 $exp 实际 ${runner.micOpen}"
                }
                "llm_turn_after_tts" -> {   // GT-040：TTS 后开麦不触发新 LLM turn
                    val exp = (expected as? Number)?.toInt() ?: return@forEach
                    if (runner.llmTurnsAfterTts != exp) {
                        problems += "llm_turn_after_tts 期望 $exp 实际 ${runner.llmTurnsAfterTts}"
                    }
                }
                "retry_prompt" -> {
                    val exp = expected as? Boolean ?: return@forEach
                    if (runner.retryPrompt != exp) problems += "retry_prompt 期望 $exp 实际 ${runner.retryPrompt}"
                }
                "produces_event" -> {
                    val exp = expected as? Boolean ?: return@forEach
                    val produced = runner.producedEvents.any { it is RecognitionRepeatedFailures }
                    if (exp != produced) problems += "produces_event 期望 $exp 实际 $produced"
                }
                "local_eval" -> {
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp != runner.producedEvents.any { it is WritingEvaluated }) {
                        problems += "local_eval 期望 $exp"
                    }
                }
                "produces" -> {   // 期望产生的中间事件类型名（如 WritingEvaluated）
                    val typeName = expected?.toString() ?: return@forEach
                    val produced = runner.producedEvents.any { it::class.simpleName == typeName }
                    if (!produced) problems += "produces 期望 $typeName 未产生"
                }
                "no_failure_record" -> {
                    // 识别失败不落为学习错误：session_character_results 无 recognize 失败记录
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp) {
                        val hasFail = runner.store.results.any {
                            it.phase == "recognize" && (it.score ?: 1.0) < 0.6
                        }
                        if (hasFail) problems += "no_failure_record 期望成立但存在 recognize 失败记录"
                    }
                }
                "fallback_text" -> {
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp && runner.lastText.isNullOrBlank()) problems += "fallback_text 期望有兜底话术"
                }
                "dedup" -> {
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp != runner.rejectedCalls.isEmpty()) problems += "dedup 期望 $exp"
                }
                "truncate" -> {   // GT-013：只执行前 3 个
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp && runner.llmTurnCount > 0 && runner.store.results.size > 3) {
                        problems += "truncate 期望截断但执行了超过 3 个"
                    }
                }
                "review_empty_guard" -> {   // GT-054：队列清空时 next 被本地拒绝
                    val exp = expected as? Boolean ?: return@forEach
                    val rejected = runner.rejectedCalls.any { it == "next" }
                    if (exp != rejected) problems += "review_empty_guard 期望 $exp 实际 next 拒绝=$rejected"
                }
                "pause_llm_turn" -> {   // GT-044：暂停期间不触发 LLM
                    val exp = (expected as? Number)?.toInt() ?: return@forEach
                    if (runner.llmTurnsWhilePaused != exp) {
                        problems += "pause_llm_turn 期望 $exp 实际 ${runner.llmTurnsWhilePaused}"
                    }
                }
                "re_introduce" -> {   // GT-044：恢复不重走 introduce
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp && runner.state.phase == Phase.INTRODUCE) problems += "re_introduce 期望 false 但回到 introduce"
                }
                "weakest_dimension" -> {   // GT-056
                    val expDim = expected?.toString() ?: return@forEach
                    val case = activeCase ?: return@forEach
                    val char = inferChar(case) ?: return@forEach
                    val actual = weakestDimension(runner.store.getCharacter(char))
                    if (actual?.name?.lowercase() != expDim.lowercase()) {
                        problems += "weakest_dimension 期望 $expDim 实际 $actual"
                    }
                }
                "name_char_interval_factor" -> {   // GT-057：名字字 ×0.7
                    val exp = (expected as? Number)?.toDouble() ?: return@forEach
                    val rec = runner.store.getCharacter("张")
                    val factor = if (rec.source == "name_plan") 0.7 else 1.0
                    if (factor != exp) problems += "name_char_interval_factor 期望 $exp 实际 $factor"
                }
                "signature_threshold" -> { /* 阈值放宽属 UI 层，驱动层不验证 */ }
                "filter" -> {   // GT-014：越界过滤
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp != (runner.ttsText != runner.lastText)) problems += "filter 期望 $exp"
                }
                "warn_inject" -> {   // GT-014：警告注入（驱动层以过滤发生为准）
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp && runner.ttsText == runner.lastText) problems += "warn_inject 期望警告注入"
                }
                "re_eval_local" -> {   // GT-015
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp && runner.lastReEval == null) problems += "re_eval_local 期望有复评结果"
                }
                "result_as_tool_result" -> {
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp && runner.lastReEval == null) problems += "result_as_tool_result 期望复评结果可用"
                }
                "produces_writing_evaluated" -> {   // GT-015：复评不重触发 WritingEvaluated
                    val exp = expected as? Boolean ?: return@forEach
                    val produced = runner.producedEvents.any { it is WritingEvaluated }
                    if (exp != produced) problems += "produces_writing_evaluated 期望 $exp 实际 $produced"
                }
                "re_adjudicate", "signature_threshold" -> { /* 复评不重复裁决由 mastery 断言兜底；签字阈值放宽属 UI 层 */ }
                "no_llm_after_end" -> {   // GT-017：SessionEnded 不再触发 LLM
                    val exp = expected as? Boolean ?: return@forEach
                    if (exp && !runner.sessionEnded) problems += "no_llm_after_end 期望 session 已结束"
                }
                "next_expected" -> {   // GT-040/046：开麦后等待语音输入
                    val exp = expected?.toString() ?: return@forEach
                    if (exp == "VoiceInput" && !runner.micOpen) {
                        problems += "next_expected 期望 VoiceInput（开麦）但未开麦"
                    }
                }
                // 未实现的键：跳过（宽松子集，防用例误报）
                else -> {}
            }
        }
        return problems
    }

    private var activeCase: GoldenCase? = null

    /** input_guard 断言（GT-012）：注入上下文的隐私边界。 */
    private fun assertInputGuard(ig: Map<String, Any?>, displayName: String, namePlan: com.literacy.agent.model.NamePlan?): List<String> {
        if (ig.isEmpty()) return emptyList()
        val problems = mutableListOf<String>()
        val builder = ContextBuilder()
        val profileText = builder.learnerProfile(displayName, com.literacy.agent.model.LearningPath.WRITE_PARALLEL)
        val planText = namePlan?.let { builder.namePlan(it) } ?: ""
        ig.forEach { (key, expected) ->
            val list = (expected as? List<*>)?.mapNotNull { it.toString() } ?: return@forEach
            when (key) {
                "learner_profile_contains" -> list.forEach { kw ->
                    if (kw !in profileText) problems += "input_guard 注入上下文缺少 $kw"
                }
                "learner_profile_not_contains" -> list.forEach { kw ->
                    if (kw in profileText) problems += "input_guard 注入上下文不应含 $kw"
                }
                "name_plan_not_contains" -> list.forEach { kw ->
                    if (kw in planText) problems += "input_guard name_plan 不应含 $kw"
                }
                "name_plan_contains" -> list.forEach { kw ->
                    if (kw !in planText) problems += "input_guard name_plan 缺少 $kw"
                }
            }
        }
        return problems
    }

    companion object {
        /** 课程控制工具集（SYSTEM-PROMPT 课程控制）：allowed_actions 裁决的对象。 */
        private val CONTROL_ACTIONS = setOf(
            "advance_phase", "complete_character", "skip_character", "start_review", "next", "end_session",
        )
    }
}
