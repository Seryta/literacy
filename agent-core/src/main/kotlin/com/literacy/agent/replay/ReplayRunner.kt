package com.literacy.agent.replay

import com.literacy.agent.data.HanziDataSource
import com.literacy.agent.engine.MasteryAdjudicator
import com.literacy.agent.engine.PhaseMachine
import com.literacy.agent.learning.RuleStrokeEvaluator
import com.literacy.agent.learning.SessionLifecycle
import com.literacy.agent.learning.StrokeEvaluator
import com.literacy.agent.model.ButtonTapped
import com.literacy.agent.model.CharacterCompleted
import com.literacy.agent.model.Dimension
import com.literacy.agent.model.Event
import com.literacy.agent.model.LearningPath
import com.literacy.agent.model.LessonState
import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.Mode
import com.literacy.agent.model.Phase
import com.literacy.agent.model.ReviewStage
import com.literacy.agent.model.RecognitionLowConfidence
import com.literacy.agent.model.RecognitionRepeatedFailures
import com.literacy.agent.model.Session
import com.literacy.agent.model.SessionResult
import com.literacy.agent.model.ToolCall
import com.literacy.agent.model.VoiceInput
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.model.WritingEvaluated
import com.literacy.agent.store.LearningStore

/**
 * 回放驱动器：按事件序列驱动本地裁决 + LLM 工具执行，暴露状态快照供断言。
 *
 * 第一版：本地裁决核心（阶段状态机 + 掌握等级 + 幂等落库）。
 * 第二版新增：复习模式驱动（§6.5）、llmTurn 工具执行链路（§3/§4，含超限截断与参数校验）、text 记录。
 */
class ReplayRunner(
    val machine: PhaseMachine = PhaseMachine(),
    val adjudicator: MasteryAdjudicator = MasteryAdjudicator(),
    val store: LearningStore = com.literacy.agent.store.InMemoryStore(),
    private val strokeEvaluator: StrokeEvaluator = RuleStrokeEvaluator(),
    /** 字库（可空）：提供真实参考笔画；无字库时用几何占位（测试确定性）。 */
    private val hanziRepository: HanziDataSource? = null,
) {
    var state: LessonState = LessonState()
        private set

    /** 用例前置状态注入（CaseRunner 使用；覆盖 phase / allowed_actions / 路径等）。 */
    fun configureState(state: LessonState) {
        this.state = state
    }

    /** 复习队列（session 启动时由本地生成，对应 <review_queue>）。 */
    val reviewQueue: MutableList<String> = mutableListOf()

    /**
     * mock 模式（默认 true）：事件满足成功条件即自动推进（用例不必写 advance_phase）。
     * 真实模式（autoAdvance=false）：推进只由 advance_phase（LLM 决策）触发——
     * 避免“事件自动推进 + 模型 advance_phase”双重推进（GT-003 phase 多走一级）。
     */
    var autoAdvance: Boolean = true

    /**
     * review-09 P1-7：生产严格校验——App 层（AgentOrchestrator）置 true。
     * 要求 record_result 必须回传 App 签发的幂等键（无真实本地尝试即拒绝模型自造 key/分数）；
     * 学习轮还要求本地 attempt 非空。用例/mock（宽松）不受影响。
     */
    var strictResultValidation: Boolean = false

    /** 最近一次 LLM 输出的 text（供 text 语义断言，GT 用例 contains/not_contains）。 */
    var lastText: String? = null
        private set

    /** 过滤后实际用于 TTS 的文本（GT-014：越界内容过滤后朗读）。 */
    var ttsText: String? = null
        private set

    /** 本次用例执行的 LLM turn 数（local_handling.llm_turn 断言）。 */
    var llmTurnCount: Int = 0
        private set

    /** 本地裁决拒绝的工具名列表（GT-008：参数非法 → 拒绝执行并注入 error）。 */
    val rejectedCalls: MutableList<String> = mutableListOf()

    /** 拒绝原因（P1-3：注入下一 turn 的 error result，§10「注入 error」承诺）。 */
    val rejectReasons: MutableList<String> = mutableListOf()

    /** 本地产生的中间事件（StrokeFinished → WritingEvaluated、低置信度升级等，供断言）。 */
    val producedEvents: MutableList<Event> = mutableListOf()

    private var lastEvent: Event? = null
    private var sessionId = 1
    private val spacedRepetition = com.literacy.agent.learning.SpacedRepetition()

    /** runner 绑定的 session（P1-5：sessionRefresh 创建后绑定，旧回包/并发 runner 不串写）。 */
    private var boundSessionId: Int? = null

    /** 当前绑定的 session id（P2-3：session 聚合用；null = 未绑定）。 */
    val currentBoundSessionId: Int? get() = boundSessionId

    /** 当前 session id：优先绑定值（P1-5），兜底 latestSession。 */
    private fun currentSessionId(): Int = boundSessionId ?: store.latestSession()?.id ?: sessionId

    /**
     * 当前阶段成功条件是否已满足（事件到达时本地判定，review-05 P0-3）。
     * advance_phase 工具只做迁移，不重新判定事件（避免 lastEvent 滞后误判）。
     */
    var phaseReady: Boolean = false
        private set

    /** 事件到达时判定当前阶段成功条件并记录标记。 */
    private fun judgePhase(event: Event?) {
        phaseReady = machine.successCriteriaMet(state, event)
        // P1-6：跟写需全部笔画完成（字库笔画数；无字库时保持单次成功语义）
        if (state.phase == Phase.GUIDED_WRITE && phaseReady) {
            val total = state.char?.let { hanziRepository?.strokeCount(it) } ?: 0
            if (total > 0 && completedStrokes < total) phaseReady = false
        }
    }

    /** 跟写成功累计笔画数（P1-6：一笔不能完成全部笔画）。 */
    var completedStrokes: Int = 0
        private set

    private fun strokeCompleted(ev: WritingEvaluated) {
        if (ev.phase == "guided_write" && ev.ok) completedStrokes++
    }

    /** 阶段迁移（advance_phase 工具 / mock 自动推进共用）：
     *  自动通过阶段（introduce/demonstrate/record）恒可推进；检测阶段需 phaseReady（成功条件已判定）。 */
    private fun advanceStep(): Boolean {
        val auto = state.phase in AUTO_PASS
        if (!auto && !phaseReady) return false
        val next = machine.advanceStep(state) ?: return false
        state = state.copy(phase = next, allowedActions = allowedFor(next))
        reachedPhases += next
        phaseReady = false   // 成功条件已消费；新阶段需新事件重新判定
        return true
    }

    /** SessionStarted → introduce（§6.1 起始阶段）。P2-11：提示等级从 characters.currentPromptLevel 恢复。 */
    fun startSession(char: String, path: LearningPath = LearningPath.WRITE_PARALLEL): ReplayRunner {
        val savedLevel = store.getCharacter(char).currentPromptLevel
        state = LessonState(
            phase = Phase.INTRODUCE,
            char = char,
            learningPath = path,
            promptLevel = savedLevel,
            allowedActions = allowedFor(Phase.INTRODUCE),
        )
        return this
    }

    /**
     * 启动刷新（SESSION-LIFECYCLE §1.0，GT-016）：上次 active → aborted，写入新 active session。
     * 返回新 session 并绑定到 runner（P1-5：证据/结束状态归属本 runner 创建的 session）。
     * 时间默认真实值（P1-4：生产会话时间不再写死）。
     */
    fun sessionRefresh(
        date: String = java.time.LocalDate.now().toString(),
        startedAt: String = java.time.LocalTime.now().toString(),
    ): Session {
        val s = SessionLifecycle(store).startSession(date, startedAt)
        boundSessionId = s.id
        return s
    }

    /** 无事件推进（仅用于自动通过阶段：introduce / demonstrate / record）。 */
    fun advance(): Boolean {
        judgePhase(null)
        return advanceStep()
    }

    /** 语音事件（认读 / 解释 / 造句）。intent 对应 recognize 成功条件。
     *  事件到达时本地判定成功条件（review-05 P0-3），advance_phase 只做迁移。 */
    fun voice(intent: VoiceIntent = VoiceIntent.OTHER, text: String = ""): Boolean {
        // review-10 P1-5：REQUEST_NEW_CHAR/SWITCH_PATH 不再只识别不改变状态
        if (intent == VoiceIntent.REQUEST_NEW_CHAR) {
            // review-11 P2-2：解析 text 中的目标字（"我想学'药'字"→药）——有则选该字，无则 nextCharSelector
            val target = extractTargetChar(text)
            val next = target ?: nextCharSelector?.invoke()
            state = state.copy(
                phase = Phase.INTRODUCE,
                char = next ?: state.char,
                learningPath = state.learningPath,
                allowedActions = allowedFor(Phase.INTRODUCE),
                idempotencyKey = null, attempt = null,
            )
            completedStrokes = 0
            phaseReady = false
            producedEvents += com.literacy.agent.model.CharacterCompleted
            return true
        }
        if (intent == VoiceIntent.SWITCH_PATH) {
            // review-11 P2-2：SWITCH_PATH 语义=拒绝书写（不写字/手不方便）——确定性映射到
            // 无书写路径 READ_ONLY，不再三态循环（重复"不写字"不会回到书写路径）
            state = state.copy(learningPath = com.literacy.agent.model.LearningPath.READ_ONLY)
            return true
        }
        val ev = VoiceInput(text, intent)
        lastEvent = ev
        micOpen = false   // 语音到达：开麦状态复位
        judgePhase(ev)    // 判定当前阶段成功条件
        // SessionStarted 后 phase=INTRODUCE：首个交互事件穿过（introduce 自动通过，本地裁决）
        if (state.phase == Phase.INTRODUCE && phaseReady) {
            advanceStep()
            judgePhase(ev)   // 推进后重新判定（demonstrate 自动通过等）
        }
        // P1-7：掌握等级裁决统一到 record_result（§6.4 触发点）——事件只记录，不更新 mastery
        // （recognize 赋值 / explain→UNDERSTAND / sentence→APPLY 由 record_result 按 phase 推导裁决）
        return if (autoAdvance) advanceStep() else phaseReady
    }

    /** 书写评估事件（guided_write / independent_write / signature）。
     *  事件到达时判定成功条件；自动通过阶段本地穿过；检测阶段推进由 advance_phase（真实）或事件（mock）。 */
    fun writing(phase: String, ok: Boolean, promptLevel: Int, score: Double = if (ok) 0.9 else 0.4): Boolean {
        val ev = WritingEvaluated(phase, score, ok, promptLevel)
        lastEvent = ev
        judgePhase(ev)
        // 自动通过阶段（introduce/demonstrate/record）：本地总是穿过（§6.3）
        while (state.phase in AUTO_PASS && phaseReady) {
            // review-11 P1-1.3：无法推进必须中止（复习模式 advance_phase 不在 allowed / 已到流程末尾）——
            // 否则 phaseReady 保持 true 死循环（复习轮书写事件暴露：startReview 后 phase 遗留 INTRODUCE）
            if (!advanceStep()) break
            judgePhase(ev)
        }
        // P1-7：掌握裁决统一到 record_result（§6.4）；这里保留签名达标本地逻辑 + 笔画累计
        adjudicateOnWriting(ev)
        strokeCompleted(ev)   // P1-6：跟写成功累计笔画
        // review-11 P1-1.3：复习轮书写事件即本地作答证据——绑定 attempt（score=本次评估、
        // phase=null 避免与 assess/reinforce 落库 phase 冲突），record_result 借此校验证据。
        // 学习轮 attempt 由 App 层 beginAttempt 绑定（此处不动，mock 测试保持宽松）
        if (state.mode == Mode.REVIEW) {
            state = state.copy(attempt = com.literacy.agent.model.AttemptContext(
                phase = null,
                score = ev.score,
                dimension = null,
                promptLevel = state.promptLevel,
                issues = ev.issues,
            ))
        }
        return if (autoAdvance) advanceStep() else phaseReady
    }

    /**
     * 提示等级调节（TEACHING-STRATEGY §2.2 降难矩阵 + §4 脚手架撤除）：
     * - 独立写连续 2 次失败 → 降一级（提示更多，L3→L4，GT-028）
     * - 独立写成功 → 升一级（脚手架撤除，L4→L3，GT-029）
     * 同步 characters.current_prompt_level（跨 session 保持）。
     */
    /** 降难/升提示（§2.2/§4）：裁决后基于目标维度 streak（GT-028 失败 2 次+1；GT-029 成功-1）。
     *  P1-17：读本次裁决维度的 streak——independent_write 书写通道练 WRITE、
     *  识主写辅/识读优先练 RECOGNIZE，各维度独立计数。 */
    private fun adjustPromptLevel(
        phase: String,
        dim: Dimension?,
        rec: com.literacy.agent.model.CharacterRecord,
        ok: Boolean,
    ) {
        if (phase != "independent_write" || dim == null) return
        val newLevel = when {
            !ok && rec.streakErrors(dim) >= 2 -> (state.promptLevel + 1).coerceAtMost(6)
            ok -> (state.promptLevel - 1).coerceAtLeast(0)
            else -> return
        }
        state = state.copy(promptLevel = newLevel)
        store.upsertCharacter(rec.copy(currentPromptLevel = newLevel))
    }

    /** 选项判题事件（识主写辅 / 识读优先的 independent_write 通道）。 */
    fun tapped(action: String, correct: Boolean, exerciseId: String): Boolean {
        // review-09 P1-8：保留本地绑定的维度/题型——App 层 beginAttempt 已绑定 exerciseType，
        // 此处只覆盖本地判题分数（不把 dimension/exerciseType 冲回 null，落库时按题型推维度）
        val prev = state.attempt
        configureState(state.copy(attempt = (prev ?: com.literacy.agent.model.AttemptContext()).copy(
            score = if (correct) 1.0 else 0.0,
        )))
        reviewAnswered = true   // review-09 P1-4：判题证据
        reviewAnsweredScore = if (correct) 1.0 else 0.0   // 残余修复：本地判题真值（补记 assess 用）
        reviewAnsweredAttempt = state.attempt?.copy(score = reviewAnsweredScore)   // 完整本地上下文（题型+维度+分数）
        val ev = ButtonTapped(action, correct, exerciseId)
        lastEvent = ev
        judgePhase(ev)
        return if (autoAdvance) advanceStep() else phaseReady
    }

    /** 课程控制动作裁决（complete_character / skip_character / next / end_session 等，§6.2）。 */
    fun control(action: String): Boolean = machine.isActionAllowed(action, state)

    // ---- 复习模式（§6.5）----

    /** start_review：本地校验 review_queue 非空才进入。P1-9：消费首项为当前复习字（不重复）。 */
    fun startReview(): Boolean {
        if (state.mode == Mode.REVIEW) return false   // review-09 P1-4：复习中禁止再次 start_review（防绕过 NEXT 门禁）
        if (reviewQueue.isEmpty()) return false
        // review-09 P1-9：按复习字边界清零判题证据（新复习字需重新作答）
        reviewAnswered = false
        reviewAnsweredScore = null
        reviewAnsweredAttempt = null
        assessRecordedForRound.clear()   // 新复习字新轮次，ASSESS 记账重置
        state = state.copy(
            mode = Mode.REVIEW,
            reviewStage = ReviewStage.RECALL,
            char = reviewQueue.removeFirst(),   // P1-9：消费首项（next 不再拿到同字）
            allowedActions = allowedForReview(),
        )
        return true
    }

    /** review-10 P1-9：SafetyGuard 过滤命中标记（UI/TTS 用过滤文本；告警注入下一轮上下文）。 */
    var filterHit: Boolean = false
        private set

    /** 插单目标字提取（"我想学'药'字"→药；支持引号包裹与 学X字 正则）。review-11 P2-2。 */
    private fun extractTargetChar(text: String): String? {
        val quoted = Regex("['\"]([^'\"]{1,2})['\"]").find(text)
        if (quoted != null) return quoted.groupValues[1]
        return Regex("学(.{1,2})字").find(text)?.groupValues?.get(1)
    }

    /** 复习判题证据标记（review-09 P1-4）：判题（tapped）或 record_result（assess/reinforce）后置位，
     *  ASSESS→REINFORCE 门禁要求有证据——用户不能从 recall 无证据点到 next。 */
    var reviewAnswered: Boolean = false
    /** 本地判题真值分数（reviewAnswered 对应 1.0/0.0）——补记 assess 用本地分，防模型改分。 */
    var reviewAnsweredScore: Double? = null
    /** 完整本地判题上下文（score+题型+维度）——补记 assess 恢复本地绑定，防模型题型/最弱维度误更新。 */
    var reviewAnsweredAttempt: com.literacy.agent.model.AttemptContext? = null
        private set

    /** 本复习轮（当前字）已落库 assess 的字集合——
     *  REINFORCE 补记 assess 是延迟落库容错（判题未当场落库），
     *  但同一作答只能记账一次：key A 已落库后再用 advanceReview 新签发的 key B
     *  补记同一次判题 = 重复记账（同一作答裁决两次，gateStreak/mastery 双计）。
     *  startReview/nextReviewChar 换字时清空（每字每轮一次 ASSESS）。
     *
     *  一刀切限制的取舍：REINFORCE 阶段若出现 record_result(assess)，当前产品语义下
     *  只可能是「ASSESS 阶段判题未当场落库的补记」——复习流程 RECALL→ASSESS→REINFORCE→NEXT
     *  一轮一字只有一次判题（ASSESS 无证据无法 advance 到 REINFORCE，门禁保证），
     *  REINFORCE 没有第二道真实新题。故同字同轮第二次 assess 一律视为重复记账拒绝。
     *  若未来产品在 REINFORCE 引入真实新题（新 attempt 绑定新作答），需在此区分
     *  「补记旧作答」与「新题新作答」（如按 attempt 是否为空/新幂等键判定），届时放宽。 */
    private val assessRecordedForRound = mutableSetOf<String>()

    /** 作答完成标记（review-09 P1-4）：App 端判题选项点击即作答完成（对错由模型 record_result 裁决）。 */
    fun markAnswered() {
        if (state.mode == Mode.REVIEW && state.reviewStage == ReviewStage.ASSESS) reviewAnswered = true
    }

    /** 下一字选择器（review-09 P1-6）：complete_character 后选下一字，由 App 注入（姓名目标/复习队列/推荐）。
     *  返回 null 时重复学习当前字。 */
    var nextCharSelector: (() -> String?)? = null

    /** 复习内部阶段推进：recall → assess → reinforce → next。NEXT 之后返回 null（由 nextReviewChar 处理）。 */
    fun advanceReview(): ReviewStage? {
        val stage = state.reviewStage ?: return null
        // review-09 P1-4：ASSESS→REINFORCE 必须有判题证据（本地判题或 record_result 落库）
        if (stage == ReviewStage.ASSESS && !reviewAnswered) return stage
        val next = when (stage) {
            ReviewStage.RECALL -> ReviewStage.ASSESS
            ReviewStage.ASSESS -> ReviewStage.REINFORCE
            ReviewStage.REINFORCE -> ReviewStage.NEXT
            ReviewStage.NEXT -> null
        }
        if (next != null) state = state.copy(reviewStage = next)
        return next
    }

    /** next 动作：仅在 NEXT 阶段（复习字检测完成）推进到下一字；队列清空时本地拒绝（GT-054）。 */
    fun nextReviewChar(): Boolean {
        if (state.mode != Mode.REVIEW) return false
        if (state.reviewStage != ReviewStage.NEXT) return false   // P1-9：不能从 RECALL 直接跳字
        if (reviewQueue.isEmpty()) return false
        reviewAnswered = false   // review-09 P1-4：下一复习字重新判题
        reviewAnsweredScore = null   // 残余修复：本地判题真值同步清（防跨字借用）
        reviewAnsweredAttempt = null
        assessRecordedForRound.clear()   // 新复习字新轮次，ASSESS 记账重置
        state = state.copy(char = reviewQueue.removeFirst(), reviewStage = ReviewStage.RECALL)
        return true
    }

    // ---- 语音时序（§5 等待点 / listen 预约语义，GT-040/046）----

    /** listen 预约状态：工具被调用 → 预约；TtsCompleted 后开麦。 */
    var listenRequested: Boolean = false
        private set

    /** 麦克风是否已开（TtsCompleted + 有 listen 预约 → true；收到语音后复位）。 */
    var micOpen: Boolean = false
        private set

    /** TtsCompleted：有 listen 预约才真正开麦（§5）；未预约只是信号事件（GT-046）。 */
    fun onTtsCompleted() {
        ttsSeen = true
        if (listenRequested) {
            micOpen = true
            listenRequested = false
        }
    }

    /** TtsCompleted 是否已发生（用于统计 TTS 后新增 LLM turn，GT-040 llm_turn_after_tts: 0）。 */
    private var ttsSeen: Boolean = false

    /** TtsCompleted 之后触发的 LLM turn 数（开麦不应触发新 LLM turn）。 */
    var llmTurnsAfterTts: Int = 0
        private set

    // ---- 低置信度澄清（§1 RecognitionLowConfidence，GT-043/047）----

    /** 本 session 低置信度累计次数。 */
    var lowConfidenceCount: Int = 0
        private set

    /** 本地澄清提示是否已发出（"请再说一遍"）。 */
    var retryPrompt: Boolean = false
        private set

    /**
     * RecognitionLowConfidence：连续 <3 次本地澄清（不触发 LLM）；
     * ≥3 次产生 RecognitionRepeatedFailures（触发 LLM 降难，RESEARCH-VOICE 第 3 档）。
     * @return true = 本地处理完成；false = 已升级为 RecognitionRepeatedFailures。
     */
    fun onRecognitionLowConfidence(confidence: Double, partial: String?): Boolean {
        lowConfidenceCount++
        if (lowConfidenceCount >= 3) {
            producedEvents += RecognitionRepeatedFailures(lowConfidenceCount, partial)
            return false
        }
        retryPrompt = true
        return true
    }

    // ---- 书写评估（§1 StrokeFinished → 本地评估 → WritingEvaluated，GT-022）----

    /** 最近一次本地书写评估结果（供 evaluate_writing 复评，GT-015）。 */
    var lastWritingEval: WritingEvaluated? = null
        private set

    /**
     * StrokeFinished：本地事件，不触发 LLM。由本地规则引擎完成书写评估（RESEARCH-TECH：
     * 坐标序列 vs 标准笔画特征对比），产生 WritingEvaluated，之后才触发 LLM turn（§1/§4）。
     * 参考笔画优先取字库真实数据（hanzi.db），无字库/无数据时用几何占位（测试确定性）。
     */
    fun onStrokeFinished(stroke: Int, path: List<com.literacy.agent.model.StrokePoint> = emptyList()) {
        val reference = referenceStrokeFor(stroke)
        // review-09 P2-1：空轨迹（<2 点）不得当成功（0.9/true 伪造）；无参考数据用几何占位
        val eval = when {
            path.size < 2 -> com.literacy.agent.learning.StrokeEvaluation(0.2, false, issues = listOf("轨迹过短"))
            reference != null -> strokeEvaluator.evaluate(path, reference)
            else -> com.literacy.agent.learning.StrokeEvaluation(0.9, true)   // 几何占位（无字库兜底，测试确定性）
        }
        val ev = WritingEvaluated(
            phase = "guided_write",
            score = eval.score,
            ok = eval.ok,
            promptLevel = state.promptLevel,
            issues = eval.issues,
        )
        lastWritingEval = ev
        producedEvents += ev
        lastEvent = ev
        strokeCompleted(ev)   // review-09 P1-1：跟写成功笔数累计；review-10 P1-4：先累计再判定（最后一笔推进正确）
        judgePhase(ev)
        // 与 writing() 一致：自动通过阶段本地穿过（review-05 P2-7）
        while (state.phase in AUTO_PASS && phaseReady) {
            if (!advanceStep()) break   // 无法推进（复习模式）立即中止，防死循环（与 writing() 同守卫）
            judgePhase(ev)
        }
        if (autoAdvance) advanceStep()
    }

    /** 参考笔画：字库真实笔画（按序号）优先；否则几何占位直线。 */
    private fun referenceStrokeFor(stroke: Int): List<com.literacy.agent.model.StrokePoint>? {
        val char = state.char
        if (char != null) {
            hanziRepository?.referenceStrokes(char)?.getOrNull(stroke - 1)?.let { return it }
        }
        return defaultReferenceStroke()
    }

    /** 几何占位参考笔画（字库缺失时保持回放确定性）。 */
    private fun defaultReferenceStroke(): List<com.literacy.agent.model.StrokePoint> =
        listOf(
            com.literacy.agent.model.StrokePoint(0f, 100f),
            com.literacy.agent.model.StrokePoint(100f, 0f),
        )

    /** evaluate_writing 复评：本地重新评估最近一次书写，结果作为同步 tool result（GT-015）。
     *  不重新触发 WritingEvaluated，不重复参与掌握裁决。 */
    fun onEvaluateWriting(): WritingEvaluated? = lastWritingEval

    // ---- 结束流程（§7.2，GT-011/017）----

    /** session 是否已结束（end_session 执行或本地兜底）。 */
    var sessionEnded: Boolean = false
        private set

    /** 标记 session 为 completed（llmTurn end_session 或兜底结束时调用）。 */
    fun markSessionCompleted() {
        sessionEnded = true
        // review-09 P1-14：优先 runner 绑定的 session（boundSessionId），且只结束仍 active 的——
        // 不再用 latestSession（旧 ViewModel 的迟到失败回包可能结束一个更新的 session）
        val id = boundSessionId
        val target = id?.let { store.sessions.find { s -> s.id == it } }
        if (target != null && target.status == "active") {
            store.updateSession(target.id) { it.copy(status = "completed") }
        } else if (target == null) {
            store.insertSession(Session(date = "", startedAt = "", status = "completed"))
        }
    }

    /**
     * §7.2 原子结束：completed + 总结字段 + endedAt（P2：一次事务，endedAt 真实时间）。
     */
    fun completeSession(highlights: String?, struggles: String?, namePlanProgress: String?) {
        sessionEnded = true
        val boundId = boundSessionId
        val last = boundId?.let { store.sessions.find { s -> s.id == it } } ?: store.latestSession()   // P1-10：结束绑定本 runner 的 session
        if (last != null) {
            store.completeSession(
                last.id,
                endedAt = java.time.LocalTime.now().toString(),
                highlights = highlights ?: last.highlights,
                struggles = struggles ?: last.struggles,
                namePlanProgress = namePlanProgress ?: last.namePlanProgress,
            )
        } else {
            store.insertSession(Session(date = "", startedAt = "", status = "completed"))
        }
    }

    /** §7.2 第 3 条：Provider 失败时本地兜底结束（GT-011）——不阻塞退出，session 以 completed 收尾。 */
    fun endSessionFallback() {
        lastText = "好的，今天先到这里。你已经学了不少，下次我们再继续。"
        markSessionCompleted()
    }

    // ---- 暂停与恢复（§1 暂停与恢复，GT-044）----

    var paused: Boolean = false
        private set

    private var pausedState: LessonState? = null

    /** 暂停中触发的 LLM turn 数（暂停不触发 LLM，GT-044 pause_llm_turn: 0）。 */
    var llmTurnsWhilePaused: Int = 0
        private set

    /** PauseRequested：本地暂停，不调 LLM；保留当前 lesson_state（从暂停时阶段继续）。 */
    fun pause() {
        paused = true
        pausedState = state
    }

    /** 恢复：ButtonTapped(action=resume) → 从暂停时阶段继续，不重走 introduce。 */
    fun resume(): Boolean {
        if (!paused) return false
        state = pausedState ?: state
        paused = false
        return true
    }

    // ---- LLM 工具执行链路（§3/§4）----

    /**
     * LLM turn：记录 text，按序执行 toolCalls（最多 3 个，超限截断 GT-013）。
     * 每个工具调用前校验参数与 allowed_actions（§10 本地校验，不信任 LLM）。
     */
    fun llmTurn(output: LlmOutput) {
        llmTurnCount++
        if (ttsSeen) llmTurnsAfterTts++
        if (paused) llmTurnsWhilePaused++
        lastText = output.text
        val filtered = SafetyGuard.filter(output.text)
        ttsText = filtered.first
        // review-10 P1-9：过滤命中记录（UI 与 TTS 共用过滤文本；告警注入下一轮上下文）
        if (filtered.second) filterHit = true
        if (paused) return   // review-09 P1-12：暂停中迟到回包只记录文本，不执行任何工具副作用（落库/推进）
        if (sessionEnded) return   // review-11 P1-2：会话结束后迟到回包不执行工具副作用（end 串行化后的防御层）
        for (tc in output.toolCalls.take(MAX_TOOL_CALLS)) {
            if (!validateToolCall(tc)) {
                rejectedCalls += tc.name   // §10 参数非法 → 拒绝执行并注入 error result（GT-008）
                continue
            }
            executedToolCalls += tc.name   // review-09 P2-14：实际执行记录
            // review-10 P2-19：allowed 检查在 when 各分支——被拒绝的从记录移除（防"未执行被当已调用"）
            // 复习 recall：本地拒绝展示答案工具（§6.5 提取练习，GT-051——不依赖模型自觉）
            if (state.mode == Mode.REVIEW && state.reviewStage == ReviewStage.RECALL &&
                (tc.name == "show_character" || tc.name == "show_pinyin")
            ) {
                rejectedCalls += tc.name
                continue
            }
            when (tc.name) {
                "advance_phase" -> advanceStep()   // 成功条件由事件到达时判定（review-05 P0-3）
                "record_result" -> executeRecordResult(tc)
                // 语音：listen 只预约，不立即开麦（§5，GT-040）
                "listen" -> listenRequested = true
                // 复评：本地重新评估最近一次书写，结果作为同步 tool result（§4，GT-015）
                "evaluate_writing" -> {
                    lastReEval = lastWritingEval ?: WritingEvaluated(
                        phase = if (state.phase == Phase.GUIDED_WRITE) "guided_write" else "independent_write",
                        score = 0.9, ok = true, promptLevel = state.promptLevel,
                    )
                }
                // 课程控制工具：本地裁决 + 完整语义执行
                "start_review" -> if (machine.isActionAllowed("start_review", state)) startReview()
                else rejectedCalls += tc.name
                "next" -> if (machine.isActionAllowed("next", state)) {
                    if (!nextReviewChar()) rejectedCalls += tc.name   // 队列清空 → 本地拒绝（GT-054）
                } else rejectedCalls += tc.name
                // skip_character：本地记录跳过原因（reason → attempt.issues，随 record_result
                // phase=skip 落库，review-11 P1-7）并迁移到 record（§6.2；record_result phase=skip 由后续落库）
                "skip_character" -> if (machine.isActionAllowed("skip_character", state)) {
                    val reason = tc.arguments["reason"]?.toString()
                    state = state.copy(
                        phase = Phase.RECORD,
                        allowedActions = allowedFor(Phase.RECORD),
                        // review-11 批A：强制 phase="skip"——语音跳过路径 attempt 已绑 phase=recognize，
                        // 保留原 phase 会让 record_result(skip) 被"phase 与本地事件不符"拒绝
                        // （且跳过错记为认错失败污染 streak）；copy 保留 dimension/promptLevel 其余字段
                        attempt = state.attempt?.copy(phase = "skip", issues = listOfNotNull(reason))
                            ?: com.literacy.agent.model.AttemptContext(
                                phase = "skip", issues = listOfNotNull(reason),
                            ),
                    )
                } else rejectedCalls += tc.name
                // P1-9：complete_character 校验通过后产生 CharacterCompleted + 回到下一字 introduce（§5：本地完成当前字）
                "complete_character" -> if (machine.isActionAllowed(tc.name, state)) {
                    producedEvents += CharacterCompleted
                    // review-09 P1-6：进入下一字（App 注入的 selector；无下一字则重复当前字），
                    // 并清理旧字残留状态（笔画数/暂停/拒绝/UI 工具/幂等键/本地裁决）
                    val next = nextCharSelector?.invoke()
                    state = LessonState(
                        phase = Phase.INTRODUCE,
                        char = next ?: state.char,
                        learningPath = state.learningPath,
                        allowedActions = allowedFor(Phase.INTRODUCE),
                        idempotencyKey = null,   // 新字新尝试
                        attempt = null,
                    )
                    completedStrokes = 0
                    paused = false
                    rejectReasons.clear()
                    recentUiTools.clear()
                    phaseReady = false
                } else rejectedCalls += tc.name   // 越权请求 → 静默拒绝（GT-009）
                "end_session" -> if (machine.isActionAllowed("end_session", state) || state.mode == Mode.REVIEW) {
                    // §7.2：结构化总结 + completed + endedAt 一次原子写入（P2：不拆两次事务）
                    val args = tc.arguments
                    completeSession(
                        highlights = args["highlights"]?.toString(),
                        struggles = args["struggles"]?.toString(),
                        namePlanProgress = args["name_plan_progress"]?.toString(),
                    )
                }
                // P1-12 + review-09 P1-5：声明工具（show_* 等 UI 工具）记录完整 ToolCall 供 App 渲染
                // （参数含 options/句子/笔序——此前只存名字丢参数，UI 消费者渲染不了）
                else -> if (tc.name in UI_TOOLS) recentUiTools += tc
            }
            // review-10 P2-19：allowed 拒绝的工具从 executed 记录移除（防"未执行被当已调用"）
            if (rejectedCalls.isNotEmpty() && rejectedCalls.last() == tc.name) {
                executedToolCalls.remove(tc.name)
            }
        }
    }

    /** 复评结果缓存（最近一次 evaluate_writing 返回的 tool result）。 */
    var lastReEval: WritingEvaluated? = null
        private set

    /** §10 工具参数本地校验：不信任 LLM。参数缺失/非法 → false（拒绝执行）。 */
    private fun validateToolCall(tc: ToolCall): Boolean {
        val args = tc.arguments
        val char = args["char"]
        val charA = args["char_a"]
        val charB = args["char_b"]
        val result = args["result"]
        return when (tc.name) {
            "show_character", "show_pinyin", "show_image", "show_example" ->
                !(char?.toString() ?: "").isBlank()
            // review-11 P1-4.1：show_sentence 的 canonical 参数是 sentence_text（不是 char）——
            // 此前要求 char 会拒绝合法调用（有提示尝试被拒）
            "show_sentence" ->
                !(args["sentence_text"]?.toString() ?: "").isBlank()
            "compare_characters" ->
                !(charA?.toString() ?: "").isBlank() && !(charB?.toString() ?: "").isBlank()
            "record_result" -> {
                val key = if (result is Map<*, *>) result["idempotency_key"] else null
                !(char?.toString() ?: "").isBlank() && result is Map<*, *> && key != null
            }
            else -> true   // 协议外工具与 UI 工具（listen 等）：参数校验不适用，交由执行层处理
        }
    }

    /** 幂等落库（§7.1）。返回 true 表示新插入。 */
    fun recordResult(
        char: String,
        phase: String,
        score: Double?,
        promptLevel: String?,
        idempotencyKey: String,
    ): Boolean = store.recordResult(
        SessionResult(
            sessionId = currentSessionId(), char = char, phase = phase, score = score,
            promptLevel = promptLevel, idempotencyKey = idempotencyKey,
        ),
    )

    private fun executeRecordResult(tc: ToolCall) {
        val args = tc.arguments
        val char = args["char"] as? String ?: return
        val result = args["result"] as? Map<*, *> ?: return
        val key = result["idempotency_key"] as? String ?: return
        // P1-2 信任边界：校验模型提供的数据（不信任 LLM，§10）
        if (!validateRecordResult(char, result)) {
            rejectedCalls += "record_result"
            rejectReasons += "record_result: 数据非法（char/phase/score）"   // P1-3
            return
        }
        val score = (result["score"] as? Number)?.toDouble()
        val phase = result["phase"] as? String ?: ""
        // P1-6 + review-09 P1-7：key 必须匹配 App 注入的幂等键。
        // 生产（strictResultValidation）：App 未签发 key（无真实本地尝试）即拒绝——
        // 模型不得在无本地作答的回合自造 key/phase/score；mock/用例未注入则宽松
        val injected = state.idempotencyKey
        if (strictResultValidation) {
            if (injected == null) {
                rejectedCalls += "record_result"
                rejectReasons += "record_result: 缺少 App 签发的幂等键（本次尝试无本地证据）"   // P1-3
                return
            }
            if (key != injected) {
                rejectedCalls += "record_result"
                rejectReasons += "record_result: key 不匹配 App 签发的幂等键"   // P1-3
                return
            }
        } else if (injected != null && key != injected) {
            rejectedCalls += "record_result"
            rejectReasons += "record_result: key 不匹配 App 签发的幂等键"   // P1-3
            return
        }
        // review-09 P1-7 + P1-10：幂等预检——App 签发 key 全局去重（Room 与核心 Store 语义一致）；
        // 同 key 换 phase/session/char 不得重复计分（复合键曾允许换 phase 双计）
        if (store.results.any { it.idempotencyKey == key }) return
        // review-09 P1-7：本地权威结果——attempt 绑定时 score 用本地裁决值、phase 必须匹配
        val attempt = state.attempt
        // 生产学习轮：无本地尝试证据（attempt 为空）即拒绝（复习轮走下方独立证据门禁）
        if (strictResultValidation && state.mode != Mode.REVIEW && attempt == null) {
            rejectedCalls += "record_result"
            rejectReasons += "record_result: 缺少本地尝试证据（attempt）"   // P1-3
            return
        }
        if (attempt != null) {
            if (attempt.phase != null && phase != attempt.phase) {
                rejectedCalls += "record_result"
                rejectReasons += "record_result: phase 与本地事件不符（期望 ${attempt.phase}）"   // review-09 P1-7
                return
            }
        }
        // review-10 P1-3：复习轮按当前 reviewStage 校验落库 phase——RECALL/NEXT 无证据不落库；
        // ASSESS 写 assess（判题）；REINFORCE 写 reinforce（再学），且允许补记 assess（判题延迟落库，GT-053）
        if (state.mode == Mode.REVIEW) {
            // review-11 P1-1.3 + 批A：复习轮 record_result 必须带本地作答证据——attempt.score != null
            // （本地判题真值）或 reviewAnswered（tapped 判题已置位；advanceReview 的 beginAttempt 把
            // attempt 覆盖为 null 后仍成立——正是"ASSESS 已有判题证据"语义，review-10 P1-3 补记容错）——
            // 模型不能在出题回合直接 record_result 打开 reviewAnswered 门禁（无作答即无分数）
            if (state.attempt?.score == null && !reviewAnswered) {
                rejectedCalls += "record_result"
                rejectReasons += "record_result: 复习轮缺少本地作答证据（attempt.score）"   // review-11 P1-1.3
                return
            }
            // review-09 P1-9：REINFORCE 的 reinforce 落库必须绑定本阶段本地作答证据（attempt.score）——
            // 旧 ASSESS 判题证据（reviewAnswered）不得被下一阶段借用（补记 assess 仍走 reviewAnswered 容错）。
            // review-09 W4：门禁仅在 strict 模式生效——生产（App 层 strict=true）要求本阶段证据；
            // 宽松模式（mock/用例）允许纯讲解（无练习）的 reinforce 落库不被误拒
            if (strictResultValidation && phase == "reinforce" && state.attempt?.score == null) {
                rejectedCalls += "record_result"
                rejectReasons += "record_result: 复习 REINFORCE 缺少本阶段本地作答证据"   // review-09 P1-9
                return
            }
            // 同字同轮 ASSESS 只允许落库一次——REINFORCE 补记 assess
            // 是延迟落库容错（判题未当场落库），但同一作答已用 key A 落库后再用新 key B
            // 补记 = 重复记账（同一作答裁决两次）。补记仅当该字该轮尚未落库过 assess
            // （REINFORCE 无真实新题：一轮一字一次判题，见 assessRecordedForRound 注释）。
            if (phase == "assess" && char in assessRecordedForRound) {
                rejectedCalls += "record_result"
                rejectReasons += "record_result: 该字该轮 ASSESS 已落库（同一作答不得重复记账）"
                return
            }
            val validPhase = when (state.reviewStage) {
                ReviewStage.ASSESS -> phase == "assess"
                ReviewStage.REINFORCE -> phase == "reinforce" || phase == "assess"   // assess 仅补记容错（未落库过才放行）
                else -> false
            }
            if (!validPhase) {
                rejectedCalls += "record_result"
                rejectReasons += "record_result: 复习 ${state.reviewStage} 阶段不允许落 phase=$phase"   // review-10 P1-3
                return
            }
        }
        // review-09 P1-8 + review-10 P1-2：score 缺失拒绝（无分数不得当作成功提升 ease）；
        // 协议要求 skip 用 null score（跳过无分数）——skip 例外，但不参与 mastery/ease 排期
        val isSkip = phase == "skip"
        if (score == null && !isSkip) {
            rejectedCalls += "record_result"
            rejectReasons += "record_result: 缺 score（0-1，不得省略）"   // review-09 P1-8
            return
        }
        // review-11 P1-7：skip 落库 score=null（协议：跳过无分数，SessionResult.score 可空）；
        // 其余尝试本地权威优先（写评估/判题分数）
        // review-11 批A：非 skip 的 score==null 已在上方拒绝，attempt?.score ?: score 不可能为 null
        // （原 ?: 0.0 不可达，删除）——本地权威优先，模型分数兜底
        // 残余修复：补记 assess（attempt 被 advanceReview 清空、reviewAnswered 门禁路径）
        // 必须用本地判题真值（reviewAnsweredScore），不得用模型分数覆盖本地判题结果
        val backfillAttempt = if (phase == "assess" && attempt?.score == null && reviewAnswered) reviewAnsweredAttempt else null
        val effectiveScore = if (isSkip) null else if (backfillAttempt?.score != null) backfillAttempt.score else (attempt?.score ?: score)
        val ok = !isSkip && (effectiveScore ?: 0.0) >= 0.6
        // review-09 P1-10：attempt.dimension 优先（写评估本地绑定 WRITE）；
        // review-09 P1-8：题型优先用本地绑定的 attempt.exerciseType（选择题 App 层已绑定，
        // 模型回传缺失/篡改不得覆盖本地判题语义）；复习轮也按题型推维度（听音选字更新识读而非书写），
        // 无题型才兜底最弱（事务内）
        // 残余修复（验收 P1）：补记 assess 恢复完整本地上下文——题型/维度随本地判题保存，
        // 不重新信任模型 exercise_type（缺失/篡改）或退回最弱维度（audio_choice 不得误更新 WRITE）
        val localExerciseType = backfillAttempt?.exerciseType ?: attempt?.exerciseType ?: result["exercise_type"] as? String
        val baseDim = backfillAttempt?.dimension
            ?: attempt?.dimension
            ?: dimensionForPhase(phase, localExerciseType)
        val promptLevelStr = result["prompt_level"]?.toString()   // 兼容 YAML 数字与字符串
        // review-09 P1-15：裁决+排期全在事务内基于最新记录重算（并发 lost update 修复）；
        // 幂等预检保留作快路径，事务内 UNIQUE 索引兜底（同 key 并发 → insertResult=-1 → finalRecord=null）
        val today = java.time.LocalDate.now()
        val rec = store.getCharacter(char)
        val finalRecord = store.recordResultWithUpsert(
            SessionResult(
                sessionId = currentSessionId(),
                char = char,
                phase = phase,
                exerciseType = localExerciseType,   // review-09 P1-8：本地题型优先（模型回传缺失/篡改不覆盖）
                score = effectiveScore,   // review-10 P1-1：落库统一用本地权威值（模型不可写假分）
                promptLevel = promptLevelStr ?: (state.promptLevel.toString()),
                idempotencyKey = key,
                // review-10 P1-1：issues 用本次 attempt 绑定的（写评估当次 issues，不读上一笔）
                issues = attempt?.issues ?: emptyList(),
            ),
            rec,
        ) { latest ->
            val dim = baseDim ?: if (state.mode == Mode.REVIEW) weakestDimension(latest) else null
            val adjudicated = if (dim != null && !isSkip && phase != "guided_write") {
                // review-11 P1-1.2：裁决用本地绑定的提示等级（attempt.promptLevel 兜底 state.promptLevel）——
                // 模型 prompt_level 字段仅用于落库展示（缺失/有提示尝试不再被误判为 L0 无提示掌握）
                // review-09 P1-12：guided_write（跟写）是教学流程，不提升硬掌握度——
                // 仅 independent_write 是硬性检测点（MASTERY-CRITERIA §4）
                val localLevel = attempt?.promptLevel ?: state.promptLevel
                adjudicator.adjudicate(latest, dim, ok, localLevel, isReview = state.mode == Mode.REVIEW, today = java.time.LocalDate.now().toString())
            } else latest
            // P1-1：学习轮 + 复习轮都排期（等级1→当天，等级2→1-3天，§2/§6）——复习队列生产链路不再为空
            // review-10 P1-2：skip 不参与排期（跳过不改变间隔/ease）
            if (isSkip) latest
            else spacedRepetition.scheduleNextReview(spacedRepetition.nextSchedule(adjudicated, ok), today)
        }
        // 降难/升提示（§2.2/§4）：仅首次落库后调整（GT-028/029，重复 key 不重复调整）
        if (finalRecord != null && state.mode != Mode.REVIEW) {
            adjustPromptLevel(phase, baseDim, finalRecord, ok)
        }
        // review-09 P1-4：复习轮判题/强化落库即证据（推进门禁用）
        if (finalRecord != null && state.mode == Mode.REVIEW) {
            reviewAnswered = true
            if (phase == "assess") assessRecordedForRound += char   // 本字本轮已记账
        }
    }

    /** 最弱非零维度（复习轮裁决用，GT-053）。 */
    private fun weakestDimension(rec: com.literacy.agent.model.CharacterRecord): Dimension? =
        Dimension.entries.map { it to rec.mastery(it) }.filter { (_, l) -> l > 0 }.minByOrNull { it.second }?.first

    /** phase → 掌握维度（§6.4：record_result 事务内按 phase 更新对应维度，P1-7 统一推导）。
     *  路径通道优先：识主写辅/识读优先的 independent_write 检测通道（听音选字/选字填空）练的是识读，
     *  映射 RECOGNIZE——不虚增 WRITE（GT-034 read_only 判对后 mastery_write 不增长）。
     *  通道判定：模型显式回传 exercise_type 时以其为准；缺回传（真实模型常省略）时按学习路径推导
     *  （§6.3 LearningPath.check：路径决定检测通道，不依赖模型自觉）。 */
    private fun dimensionForPhase(phase: String, exerciseType: String?): Dimension? = when {
        phase == "recognize" -> Dimension.RECOGNIZE
        // review-10 P1-3：assess 按题型——听写（write 通道）更新 WRITE、选字/选音更新 RECOGNIZE；
        // 无题型（exerciseType=null）返回 null 由外层兜底（复习轮最弱维度 GT-053，学习轮不裁决）
        phase == "assess" -> exerciseType?.let { if (isWritingChannel(it)) Dimension.WRITE else Dimension.RECOGNIZE }
        phase == "independent_write" && !isWritingChannel(exerciseType) -> Dimension.RECOGNIZE
        phase == "guided_write" || phase == "independent_write" -> Dimension.WRITE
        phase == "explain" -> Dimension.UNDERSTAND
        phase == "sentence" || phase == "signature" -> Dimension.APPLY
        else -> null
    }

    /** independent_write 是否书写通道：显式 exercise_type 优先，缺回传时按学习路径推导（§6.3）。 */
    private fun isWritingChannel(exerciseType: String?): Boolean = when {
        exerciseType != null -> exerciseType !in NON_WRITE_EXERCISES
        else -> state.learningPath.check == com.literacy.agent.model.IndependentCheck.WRITE
    }

    /**
     * P1-2 record_result 校验：
     * - char 必须等于当前教学字（模型不能写任意字）
     * - phase 在 canonical 集合（recognize/guided_write/independent_write/explain/sentence/assess/signature/skip）
     * - score 在 [0,1]（模型不能写 999）
     * - 复习模式要求 phase=assess（或当前复习阶段语义）
     */
    private fun validateRecordResult(char: String, result: Map<*, *>): Boolean {
        // 当前教学字
        val current = state.char
        if (current != null && char != current) return false
        if (current == null && char.isBlank()) return false
        // canonical phase
        val phase = result["phase"] as? String ?: return false
        if (phase !in CANONICAL_PHASES) return false
        // 复习模式：落库 phase 必须是 assess（判题）或 reinforce（强化再学）（P2-C：注释承诺落地，review-08）
        if (state.mode == Mode.REVIEW && phase !in setOf("assess", "reinforce")) return false
        // score 范围（NaN 与 0/1 比较恒 false，会绕过范围校验——必须显式拒绝）
        val score = (result["score"] as? Number)?.toDouble()
        if (score != null && (!score.isFinite() || score < 0.0 || score > 1.0)) return false
        return true
    }

    private fun adjudicateOnWriting(ev: WritingEvaluated) {
        // P1-7：掌握等级裁决统一到 record_result（§6.4 触发点）——这里只保留签名达标本地逻辑
        // 签名达标（MASTERY-CRITERIA §7.2）：需 name_plan + 无提示 + 连续 2 次独立签名成功（P1-16）
        if (ev.phase == "signature") {
            val plan = store.namePlan
            val validScene = plan != null && plan.targetChars.isNotEmpty() && ev.promptLevel <= 0
            if (validScene) {
                signatureSuccessCount = if (ev.ok) signatureSuccessCount + 1 else 0
                if (signatureSuccessCount >= 2) {
                    store.namePlan = plan.copy(signingReady = true)
                }
            } else if (ev.ok) {
                signatureSuccessCount = 0   // 非签字场景/有提示：不累计（不伪造达标）
            }
        }
    }

    /** 最近执行的 UI 工具（show_character/show_options 等，含参数，供 App 渲染，P1-12 + review-09 P1-5）。 */
    val recentUiTools: MutableList<ToolCall> = mutableListOf()

    /** 实际执行的工具名记录（review-09 P2-14：断言读真实执行，不读 mock 脚本输入）。 */
    val executedToolCalls: MutableList<String> = mutableListOf()

    /** 本 session 连续签名成功次数（MASTERY-CRITERIA §7，GT-063）。 */
    var signatureSuccessCount: Int = 0
        private set



    /** 回放中到达过的阶段轨迹（供 GT-020 完整闭环验证：9 阶段全部到达）。 */
    val reachedPhases: MutableList<Phase?> = mutableListOf()

    companion object {
        /** 声明给模型的 UI/练习工具（同步展示，App 层渲染，P1-12）。 */
        private val UI_TOOLS = setOf(
            "show_character", "show_pinyin", "show_image", "show_example", "show_options",
            "show_sentence", "compare_characters", "highlight_stroke", "clear_grid", "navigate_screen",
        )

        /** §8 session_character_results.phase 枚举（P1-2 record_result 校验）。 */
        private val CANONICAL_PHASES = setOf(
            "recognize", "guided_write", "independent_write", "explain", "sentence", "assess", "signature", "skip", "reinforce",
        )

        /** 非书写检测通道（路径分支，LEARNING-PATH §3/§6.3）：听音选字/选字填空练识读，不练书写。 */
        private val NON_WRITE_EXERCISES = setOf("audio_choice", "fill_blank")

        const val MAX_TOOL_CALLS = 3

        /** 自动通过阶段（§6.3）：不依赖事件，教学流程占位。 */
        private val AUTO_PASS = setOf(Phase.INTRODUCE, Phase.DEMONSTRATE, Phase.RECORD)

        /** §6.2 允许动作按阶段裁剪：complete_character 仅在 decide（整字完成）可用。 */
        fun allowedFor(phase: Phase): Set<String> = when (phase) {
            Phase.DECIDE -> setOf(
                "advance_phase", "repeat", "skip_character", "start_review", "complete_character", "end_session",
            )
            else -> setOf(
                "advance_phase", "repeat", "skip_character", "start_review", "end_session",
            )
        }

        /** §6.5 复习模式：advance_phase / complete_character 不适用，推进用 next。 */
        fun allowedForReview(): Set<String> = setOf("next", "start_review", "end_session")
    }
}
