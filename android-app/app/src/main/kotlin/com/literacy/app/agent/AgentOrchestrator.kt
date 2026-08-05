package com.literacy.app.agent

import com.literacy.agent.engine.PhaseMachine
import com.literacy.agent.engine.MasteryAdjudicator
import com.literacy.agent.model.Event
import com.literacy.agent.model.SessionStarted
import com.literacy.agent.model.VoiceInput
import com.literacy.agent.model.WritingEvaluated
import com.literacy.agent.model.ButtonTapped
import com.literacy.agent.model.HelpRequested
import com.literacy.agent.model.PauseRequested
import com.literacy.agent.model.SkipRequested
import com.literacy.agent.model.EndRequested
import com.literacy.agent.model.CharacterCompleted
import com.literacy.agent.model.ConfusableDetected
import com.literacy.agent.model.IdleTimeout
import com.literacy.agent.model.RecognitionRepeatedFailures
import com.literacy.agent.model.StrokeFinished
import com.literacy.agent.model.TtsCompleted
import com.literacy.agent.model.LessonState
import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.Phase
import com.literacy.agent.replay.ReplayRunner
import com.literacy.agent.provider.LlmProvider
import com.literacy.agent.learning.IntentResolver
import com.literacy.agent.data.HanziDataSource

/**
 * App 层教学编排（真实交互会话，区别于测试用 CaseRunner）。
 *
 * 交互循环：用户事件 → 本地裁决（ReplayRunner 状态机）→ LLM 决策（provider）→
 * 工具执行 → UI 状态。复用 agent-core 的确定性裁决 + 真实 LLM。
 */
/** 本地选择题干扰字候选（从字库校验可用性后取 3 个）。 */
private val DISTRACTOR_CHARS = listOf("家", "国", "爱", "好", "学", "天", "人", "大", "小", "水", "火", "山", "门", "日", "月")

class AgentOrchestrator(
    private val provider: LlmProvider,
    private val hanzi: HanziDataSource? = null,
    store: com.literacy.agent.store.LearningStore? = null,
    private val displayName: String = "",   // P1-2：称呼（建档采集），注入 learner_profile
) {
    private val runner = ReplayRunner(
        store = store ?: com.literacy.agent.store.InMemoryStore(),
        hanziRepository = hanzi,   // P1-1：注入字库——跟写笔画数门禁 + 真实参考骨架生效
    ).apply {
        // 真实模式：推进由 LLM 的 advance_phase 工具触发（事件只记录 + 本地裁决），
        // 避免"事件自动推进 + 模型 advance_phase"双重推进（JVM 真实模式同语义）
        autoAdvance = false
        // review-09 P1-7：生产严格校验——record_result 必须回传 App 签发的幂等键 + 本地 attempt
        strictResultValidation = true
        // review-09 P1-6：complete_character 后进入下一字——姓名目标未掌握的字优先，
        // 其次复习队列；无则保持当前字（多字闭环生产链路）
        nextCharSelector = {
            val s = store ?: com.literacy.agent.store.InMemoryStore()
            val current = state.char   // 闭包延迟执行时 runner 已就绪（避免 apply 内自引用）
            // review-10 P1-7：排除当前字（否则未掌握的字完成后再选自己）；fully_mastered 也算完成
            fun done(c: String): Boolean =
                s.getCharacter(c)?.deriveStatus() in setOf("mastered", "fully_mastered")
            s.namePlan?.targetChars?.firstOrNull { c -> !done(c) && c != current }
                ?: s.characters.keys.firstOrNull { c -> !done(c) && c != current }
        }
    }
    private val intentResolver = IntentResolver()

    /** UI 可观察状态快照。 */
    var lastText: String? = null
        private set

    /** 过滤后实际用于 TTS 的文本（P1-13：越界内容过滤后再朗读，不用原始 lastText）。 */
    var ttsText: String? = null
        private set
    var micRequested: Boolean = false
        private set

    /** Provider 是否失败过（key 未配 / 网络错误）——UI 提示配置问题。 */
    var providerFailed: Boolean = false
        private set

    /** review-09 P1-13：离页取消标记——置位后在途回包不再执行任何工具副作用。 */
    @Volatile var cancelled: Boolean = false
        private set

    /** 离页取消（LearnViewModel 调用；同时取消底层 OkHttp Call）。 */
    fun cancelInFlight() {
        cancelled = true
    }

    /** 当前学习状态（UI 渲染依据）。 */
    val state: LessonState get() = runner.state

    /** 最近声明的 UI 工具（含参数，P1-12 + review-09 P1-5：App 渲染 show_options 选项/show_sentence 等）。 */
    val recentUiTools: List<com.literacy.agent.model.ToolCall> get() = runner.recentUiTools.toList()

    /** review-10 P1-9：SafetyGuard 过滤后的展示文本（UI 与 TTS 共用——屏幕不再显示原文）。 */
    val displayText: String get() = runner.ttsText ?: (runner.lastText ?: "")

    val strokeCount: Int
        get() = state.char?.let { hanzi?.strokeCount(it) } ?: 0

    /** 当前字的参考笔画骨架（米字格引导 + 手写评估参考）。 */
    val referenceStrokes: List<List<com.literacy.agent.model.StrokePoint>>
        get() = state.char?.let { hanzi?.referenceStrokes(it) } ?: emptyList()

    /** 当前字的结构拆解（难字拆分教学）。 */
    val decomposition: String
        get() = state.char?.let { hanzi?.find(it)?.decomposition } ?: ""

    /** 字库信息查询（UI 用：拼音/结构等）。 */
    fun hanziInfo(char: String): com.literacy.agent.data.HanziInfo? = hanzi?.find(char)

    /** 当前字的 SVG 笔画路径（米字格渲染用）。 */
    val currentCharStrokes: List<String>
        get() = state.char?.let { hanzi?.find(it)?.strokes } ?: emptyList()

    /** 会话是否已结束（end_session）。 */
    val sessionEnded: Boolean get() = runner.sessionEnded

    /** 是否暂停中（本地状态）。 */
    val isPaused: Boolean get() = runner.paused

    /** TTS 播放完成（listen 预约的开麦时机，§5）——转发到 runner。 */
    fun onTtsCompleted() = runner.onTtsCompleted()

    /** 已完成的跟写笔画数（P1-1：笔序由本地维护，非 promptLevel）。 */
    val completedStrokes: Int get() = runner.completedStrokes

    /** P0-1：App 为每次真实尝试签发唯一幂等键（§7.1，注入 lesson_state，record_result 必须回传匹配）。
     *  review-09 P1-7：同时绑定本次尝试的本地裁决结果（attempt），record_result 以本地为权威。 */
    private fun beginAttempt(attempt: com.literacy.agent.model.AttemptContext? = null) {
        runner.configureState(
            runner.state.copy(
                idempotencyKey = java.util.UUID.randomUUID().toString(),
                attempt = attempt,
            ),
        )
    }

    /** P1-2：today_brief 缓存（SESSION-LIFECYCLE §1.4，启动时生成注入）。 */
    private var todayBrief: String? = null

    /** 会话开始（首次问候）。greet=false 用于直达模式（先进入目标状态再教学）。
     *  启动刷新：上次 active → aborted + 创建新 active session（§7.3 / SESSION-LIFECYCLE §1.0，review-05 P0-2）。
     *  P1-10：启动时生成真实复习队列（SESSION-LIFECYCLE §1.2，替代 debug 硬编码）。 */
    fun startSession(char: String, greet: Boolean = true) {
        runner.sessionRefresh()   // aborted 检测 + 新 active session（证据链归属）
        // P1-10：生产复习队列从 characters 排期生成
        if (runner.reviewQueue.isEmpty()) {
            runner.reviewQueue.addAll(
                com.literacy.agent.learning.SessionLifecycle(runner.store)
                    .buildReviewQueue(java.time.LocalDate.now()),
            )
        }
        runner.startSession(char)
        // P1-2：生成 today_brief（今日日期/待复习字/姓名进度/建议重点）
        todayBrief = com.literacy.agent.learning.SessionLifecycle(runner.store)
            .buildTodayBrief(java.time.LocalDate.now(), runner.reviewQueue, runner.store.namePlan)
        if (greet) llmTurn(SessionStarted)
    }

    /** 开发模式：直达指定阶段（测试用；绕过自然推进，不改变其余裁决逻辑）。 */
    fun jumpTo(phase: com.literacy.agent.model.Phase) {
        runner.configureState(
            runner.state.copy(phase = phase, allowedActions = com.literacy.agent.replay.ReplayRunner.allowedFor(phase)),
        )
    }

    /** 开发模式：直达复习模式（预置复习队列）。进入后触发复习首次教学（recall 语义）。 */
    fun jumpToReview() {
        if (runner.reviewQueue.isEmpty()) runner.reviewQueue.addAll(listOf("家", "的"))
        val ok = runner.startReview()
        android.util.Log.d("AgentOrchestrator", "jumpToReview ok=$ok queue=${runner.reviewQueue} mode=${runner.state.mode}")
        if (ok) llmTurn(SessionStarted)   // 复习上下文（mode=REVIEW + recall）下的首次教学
    }

    /**
     * 复习内部阶段推进（§6.5：recall → assess → reinforce → next）。
     * 每推进一阶段触发 LLM 对新阶段教学（recall 引导回忆 / assess 出题 / reinforce 再学习）。
     * 到达 NEXT 时返回 true（由 UI 提示用户点"下一复习字"）。
     */
    fun advanceReview(): Boolean {
        beginAttempt()   // P0-1：复习检测新 key
        val stage = runner.state.reviewStage
        val next = runner.advanceReview() ?: return false
        llmTurn(VoiceInput("进入复习阶段 ${next.name.lowercase()}", com.literacy.agent.model.VoiceIntent.OTHER))
        return next == com.literacy.agent.model.ReviewStage.NEXT
    }

    /** 用户语音（STT 文本或文本框输入）。forcedIntent 用于开发模式模拟（绕过中文输入）。 */
    fun userSpoke(text: String, forcedIntent: com.literacy.agent.model.VoiceIntent? = null) {
        // review-11 P1-1.1：先解析 intent（含 RECOGNIZED/WRONG 判定）——本地真值（STT 文本 vs 目标字）
        // 在 beginAttempt 前确定，score 由本地判定绑定（模型不得改写为假分）
        val intent = forcedIntent
            ?: intentResolver.activeIntent(text)
            // review-09 P1-9：复习轮语音目标比较也进入认读判定——复习模式 phase 可能不在
            // RECOGNIZE（startReview 后遗留），但听音作答仍需本地对错真值（正确读出复习字不得判错）
            ?: if (runner.state.phase == Phase.RECOGNIZE || runner.state.mode == com.literacy.agent.model.Mode.REVIEW) {
                val target = runner.state.char
                if (target != null && intentResolver.isRecognitionCorrect(text, target)) {
                    com.literacy.agent.model.VoiceIntent.RECOGNIZED
                } else com.literacy.agent.model.VoiceIntent.WRONG
            } else com.literacy.agent.model.VoiceIntent.OTHER
        // review-10 P1-1：按当前阶段绑定本地 phase/dimension（不再固定 recognize——
        // explain/sentence 等语音结果写错维度/被拒）
        // review-11 批A：复习轮语音作答与 tapped 一致——phase 留空（record_result 按 reviewStage
        // 校验 assess/reinforce，不再被误绑 recognize 拒绝），score 仍本地绑定（听音判题真值），
        // 维度由 record_result 的 exercise_type 题型推导（不在此猜）
        val reviewMode = runner.state.mode == com.literacy.agent.model.Mode.REVIEW
        val phase = if (reviewMode) null else when (runner.state.phase) {
            Phase.RECOGNIZE -> "recognize"
            Phase.EXPLAIN -> "explain"
            Phase.SENTENCE -> "sentence"
            else -> "recognize"
        }
        val dim = when {
            // 残余修复：复习口答模态明确是识读——本地绑定 RECOGNIZE 维度，
            // 不信任模型 exercise_type/最弱维度（口答正确不得更新 WRITE 等错误维度）
            reviewMode -> com.literacy.agent.model.Dimension.RECOGNIZE
            phase == "explain" -> com.literacy.agent.model.Dimension.UNDERSTAND
            phase == "sentence" -> com.literacy.agent.model.Dimension.APPLY
            else -> com.literacy.agent.model.Dimension.RECOGNIZE
        }
        // review-11 P1-1.1：认读分数本地绑定——recognize 阶段有本地真值（STT vs 目标字）：
        // RECOGNIZED=1.0、其余（WRONG/看拼音）=0.0；explain/sentence 本地只判「尝试即可」
        // （PhaseMachine §6.3 无对错真值），score 保持 null 由模型裁决——本地权威优先但无本地值可绑。
        // 复习轮同样绑本地判题真值（听音选字：STT 命中目标字=1.0 否则 0.0，与 tapped 语义一致）
        val score = if (phase == "recognize" || reviewMode) {
            if (intent == com.literacy.agent.model.VoiceIntent.RECOGNIZED) 1.0 else 0.0
        } else null
        beginAttempt(com.literacy.agent.model.AttemptContext(
            phase = phase,
            score = score,
            dimension = dim,
            promptLevel = runner.state.promptLevel,   // review-11 P1-1.1：本地提示等级绑定（裁决权威）
        ))   // P0-1 + P1-7
        // P2-18：不记录完整用户话语（隐私），截断
        val truncated = if (text.length > 20) text.take(20) + "…" else text
        // review-09 P2-9：用户话语不落日志（隐私）；仅 DEBUG 构建记录 intent/阶段
        if (com.literacy.app.BuildConfig.DEBUG) {
            android.util.Log.d("AgentOrchestrator", "userSpoke phase=${runner.state.phase} intent=$intent")
        }
        val ev = VoiceInput(text, intent)
        runner.voice(intent, text)
        llmTurn(ev)
    }

    /** 跟写笔画完成（guided_write）：本地评估 → WritingEvaluated → LLM 反馈（§1/§4）。 */
    fun strokeFinished(stroke: Int, path: List<com.literacy.agent.model.StrokePoint>) {
        // P1-7：先本地评估（产生 lastWritingEval）再绑定权威结果（跟写评估分数为 record_result 权威）
        runner.onStrokeFinished(stroke, path)
        val eval = runner.lastWritingEval
        beginAttempt(com.literacy.agent.model.AttemptContext(
            phase = "guided_write",
            score = eval?.score,
            dimension = com.literacy.agent.model.Dimension.WRITE,
            issues = eval?.issues ?: emptyList(),   // review-10 P1-1：绑定当次评估，不读上一笔
        ))
        // 本地评估结果作为事件触发 LLM 反馈（成功反馈 / 起笔偏差等具体问题）
        runner.lastWritingEval?.let { llmTurn(it) }
    }

    /**
     * 独立书写完成（全部笔画轨迹，独立写阶段）：逐笔 vs 字库骨架综合评估 → WritingEvaluated → LLM。
     * 语义：不看提示写完整字，完成后整体评估（MASTERY-CRITERIA §4 掌握检测点）。
     * P1-7：缺笔（paths < 参考笔画数）整字判失败，不允许一笔通过多笔字。
     */
    fun completeIndependentWrite(paths: List<List<com.literacy.agent.model.StrokePoint>>) {
        val char = runner.state.char ?: return
        val refs = hanzi?.referenceStrokes(char) ?: return
        if (paths.isEmpty()) return
        val evaluator = com.literacy.agent.learning.RuleStrokeEvaluator()
        // P1-7/P1-3：笔画数必须与参考完全一致（缺笔或多画都判失败）
        if (paths.size != refs.size) {
            val ev = WritingEvaluated(
                "independent_write", 0.2, false, runner.state.promptLevel,
                issues = listOf("笔画数不符（${paths.size}/${refs.size}）"),
            )
            runner.writing(ev.phase, ev.ok, ev.promptLevel, ev.score)
            beginAttempt(com.literacy.agent.model.AttemptContext(
                phase = "independent_write",
                score = ev.score,
                dimension = com.literacy.agent.model.Dimension.WRITE,
            ))
            llmTurn(ev)
            return
        }
        // 每笔对比对应骨架；少于 2 点的短轨迹按失败笔画计入（review-09 P1-12：不虚高——
        // 此前从平均分剔除，全部短轨迹还直接返回无证据）
        val evals = paths.mapIndexedNotNull { i, path ->
            when {
                path.size < 2 -> com.literacy.agent.learning.StrokeEvaluation(0.2, false, issues = listOf("轨迹过短"))
                refs[i].size >= 2 -> evaluator.evaluate(path, refs[i])
                else -> null   // 参考数据退化（<2 点）：数据问题非用户失败，跳过
            }
        }
        if (evals.isEmpty()) return
        val avgScore = evals.map { it.score }.average()
        val ok = avgScore >= 0.6
        val issues = evals.flatMap { it.issues }.distinct().take(3)   // 合并问题，最多 3 条
        val ev = WritingEvaluated("independent_write", avgScore, ok, runner.state.promptLevel, issues)
        runner.writing(ev.phase, ev.ok, ev.promptLevel, ev.score)
        // P1-7：评估完成后绑定权威结果（independent_write 的 record_result 以本地 avgScore 为准）
        beginAttempt(com.literacy.agent.model.AttemptContext(
            phase = "independent_write",
            score = ev.score,
            dimension = com.literacy.agent.model.Dimension.WRITE,
            issues = ev.issues,   // review-10 P1-1：绑定当次评估
        ))
        llmTurn(ev)
    }

    /** 按钮动作。暂停中只响应恢复（AGENT-PROTOCOL §1：暂停本地处理，不调 LLM）。 */
    fun button(action: String) {
        if (runner.paused) {
            if (action == "resume") {
                runner.resume()
                llmTurn(ButtonTapped("resume", null, null))
            }
            return   // 暂停中其他按钮不响应、不调 LLM
        }
        when (action) {
            "help" -> llmTurn(HelpRequested)
            "skip" -> {   // review-09 P1-8：跳过是真实尝试，需新幂等键（旧 key 已落库会被幂等预检吞掉）
                beginAttempt(com.literacy.agent.model.AttemptContext(
                    phase = "skip",
                    dimension = com.literacy.agent.model.Dimension.RECOGNIZE,
                ))
                llmTurn(SkipRequested)
            }
            "pause" -> runner.pause()
            "resume" -> {
                runner.resume()
                llmTurn(ButtonTapped("resume", null, null))
            }
            "end" -> llmTurn(EndRequested)
            "next" -> {
                // 复习模式：推进下一复习字（队列空时本地拒绝，由 LLM 决定结束）
                beginAttempt()   // P2-D：推进是真实尝试，签发新 key
                if (runner.nextReviewChar()) llmTurn(VoiceInput("下一个", com.literacy.agent.model.VoiceIntent.OTHER))
            }
            else -> {   // P2-D：判题选项/模拟认对认错等真实尝试才签发 key（help/skip/pause/end 是元动作）
                // review-10 P1-5：选择题本地判题——正确答案=当前目标字（听音选字/选字填空选项
                // 含目标字+干扰项），点击即本地裁决；一次性消费（旧题不可重复点）
                val correct = action == runner.state.char
                val exerciseId = lastExerciseId ?: action   // review-11 P1-1.4：事件携带题目 id（模型按练习记录，GT-052 语义）
                beginAttempt(com.literacy.agent.model.AttemptContext(
                    phase = null,
                    score = if (correct) 1.0 else 0.0,
                    dimension = null,
                    exerciseType = lastExerciseType,
                ))
                runner.tapped(action, correct, exerciseId = exerciseId)
                runner.markAnswered()   // review-09 P1-4：复习判题选项点击=作答完成（推进门禁证据）
                lastExerciseId = null   // 一次性消费：旧题清空
                lastExerciseType = null
                currentExercise = null   // review-11 P1-1.4：本地选择题一次性消费（UI 不再渲染旧题）
                if (exerciseId != null) consumedExerciseIds += exerciseId   // review-11 批A：旧题不可复活
                llmTurn(ButtonTapped(action, correct, exerciseId))
            }
        }
    }

    /** 整字完成（decide 阶段决策）。 */
    fun characterCompleted() {
        llmTurn(CharacterCompleted)
    }

    /** LLM 决策回合：上下文 → provider → 工具执行。P1-11：complete_character 执行后自动触发下一字决策。 */
    /** 当前题目 id/题型（review-10 P1-5：从最近 show_options 提取，判题一次性消费）。 */
    var lastExerciseId: String? = null
        private set
    var lastExerciseType: String? = null
        private set

    /** review-11 批A：已作答消费的题目 id——llmTurn 每次从 recentUiTools 重提 show_options，
     *  作答后 currentExercise 已清空但旧题仍在 recentUiTools，无条件重提会让旧题复活可再点；
     *  消费记录让提取跳过旧题（新题出现时旧记录作废）。 */
    private val consumedExerciseIds = mutableSetOf<String>()

    /** review-11 P1-1.4：本地选择题真值——show_options 执行后提取（选项 + 题目 id + 正确答案=当前字）。
     *  UI 渲染本地保存的选项（不直接信模型 show_options 参数渲染选项）；判题 correct=选项==当前字；
     *  作答后清空（一次性消费，UI 侧 answerLocked 兜底禁用）。 */
    data class LocalExercise(
        val options: List<String>,
        val exerciseId: String,
        val correct: String,
    )
    var currentExercise: LocalExercise? = null
        private set

    private fun llmTurn(event: Event) {
        // review-09 P1-13：离页取消——在途回包（含 Provider 失败兜底）不再执行工具/推进/落库
        if (cancelled) return
        val producedBefore = runner.producedEvents.size
        val output = try {
            provider.respond(buildContext(event))
        } catch (e: Exception) {
            providerFailed = true   // Provider 失败：本地兜底（§7.2）
            android.util.Log.e("AgentOrchestrator", "LLM 调用失败（${event::class.simpleName}）", e)
            // §7.2 第 3 条（review-05 P1-2）：结束请求时 Provider 失败 → 本地兜底结束，session 不遗留 active
            if (event is EndRequested) {
                runner.endSessionFallback()
                // P2：用兜底话术（不被"好的，我们继续"覆盖，页面与 session 状态一致）
                LlmOutput(runner.lastText ?: "好的，我们继续。", emptyList())
            } else {
                LlmOutput("好的，我们继续。", emptyList())
            }
        }
        // review-09 W6：在途取消（离页）——Provider 失败兜底同样不得继续执行
        // （runner.llmTurn/refreshUi 播报/落库都属离页后副作用）
        if (cancelled) return
        runner.llmTurn(output)
        lastText = runner.lastText
        ttsText = runner.ttsText   // P1-13：SafetyGuard 过滤后的文本（TTS 用）
        micRequested = runner.listenRequested
        // review-10 P1-5：从最近 show_options 提取当前题目（exercise_id 供判题一次性消费）
        // review-11 P1-1.4：选项本地持有（UI 渲染源——不直接信模型参数渲染选项）
        // 残余修复（验收 P1）：canonical show_options 只传 exercise_id/prompt（AGENT-PROTOCOL），
        // 选项一律本地生成（当前字 + 字库校验的干扰字）——不依赖模型 options 参数，标准调用也能出题
        runner.recentUiTools.lastOrNull { it.name == "show_options" }?.let { tool ->
            val exId = tool.arguments["exercise_id"]?.toString()
            // review-11 批A：作答后旧题不得复活——已消费的题目 id 跳过（currentExercise 保持 null）；
            // 新题（未消费）出现时旧消费记录作废（题目 id 可复用）
            if (exId != null) {
                if (exId in consumedExerciseIds) return@let
                consumedExerciseIds.clear()
            }
            lastExerciseId = exId
            lastExerciseType = "audio_choice"   // show_options 即选择题（选项本地持有）
            // 本地生成选项：当前字 + 字库中可用的干扰字（不依赖模型 options）
            val target = runner.state.char ?: ""
            val distractors = DISTRACTOR_CHARS.filter { it != target && hanzi?.find(it) != null }.take(3)
            val opts = if (target.isNotEmpty()) listOf(target) + distractors else null
            if (!opts.isNullOrEmpty()) {
                currentExercise = LocalExercise(
                    options = opts,
                    exerciseId = lastExerciseId ?: opts.hashCode().toString(),
                    correct = target,
                )
            }
        }
        // review-09 P2-9：模型文本不落日志（隐私）；仅记录工具名
        if (com.literacy.app.BuildConfig.DEBUG) {
            android.util.Log.d("AgentOrchestrator", "llmTurn(${event::class.simpleName}) toolCalls=${output.toolCalls.map { it.name }}")
        }
        // P1-11：complete_character 执行后 → 整字完成决策 turn（模型决定下一字/复习/结束）
        val completed = runner.producedEvents.size > producedBefore &&
            runner.producedEvents.lastOrNull() is com.literacy.agent.model.CharacterCompleted
        if (completed) {
            llmTurn(com.literacy.agent.model.CharacterCompleted)
        }
    }

    /** 简化上下文（§2：lesson_state + name_plan + 事件；完整 prompt 见 SYSTEM-PROMPT.md）。 */
    private fun buildContext(event: Event): String {
        val s = runner.state
        val sb = StringBuilder()
        // P1-2：learner_profile（称呼，隐私：不传真实姓名）+ 复习队列 + today_brief
        if (displayName.isNotBlank()) {
            sb.appendLine(com.literacy.agent.replay.ContextBuilder().learnerProfile(
                displayName, runner.state.learningPath,
            ))
        }
        // §2 姓名目标（review-05 P1-3：ContextBuilder 隐私边界——只传 target_chars 不含完整姓名）
        val plan = runner.store.namePlan
        if (plan != null && plan.targetChars.isNotEmpty()) {
            sb.appendLine(com.literacy.agent.replay.ContextBuilder().namePlan(plan))
        }
        todayBrief?.let { sb.appendLine("<session_brief>$it</session_brief>") }   // P1-2：今日日期/待复习/建议
        // P1-B：上一轮 record_result 拒绝原因注入下一 turn（§10 error 承诺，review-08）
        // review-09 P2-4：注入后消费即清（否则永不清空，旧拒绝污染后续所有 turn）
        if (runner.rejectReasons.isNotEmpty()) {
            sb.appendLine("上一轮工具调用被拒绝：${runner.rejectReasons.joinToString("；")}（请按提示修正后重试）")
            runner.rejectReasons.clear()
        }
        // review-10 P1-9：SafetyGuard 过滤命中注入（协议：过滤后需告知模型避免重犯）
        if (runner.filterHit) {
            sb.appendLine("上一轮输出含被过滤内容（已对用户隐藏），请勿再输出此类内容")
        }
        s.idempotencyKey?.let { sb.appendLine("本次尝试幂等键=$it（record_result 必须回传此键）") }   // P0-1
        // P1-7：本地权威结果注入（模型 record_result 必须与此一致，不得改写本地裁决）
        s.attempt?.let { a ->
            sb.appendLine("本地裁决：phase=${a.phase} score=${a.score ?: "（模型判）"} 维度=${a.dimension?.name?.lowercase() ?: "-"}")
        }
        sb.append("当前状态：阶段=${s.phase?.display ?: "无"} 字=${s.char ?: "-"} 模式=${s.mode} ")
            .append("学习路径=${s.learningPath.name.lowercase()} 提示等级=${s.promptLevel} ")
            .append("允许动作=${s.allowedActions.joinToString(",")}")
            .append(if (s.reviewStage != null) " 复习阶段=${s.reviewStage.name.lowercase()}" else "")
            .append(if (runner.reviewQueue.isNotEmpty()) " 复习队列=${runner.reviewQueue.joinToString(",")}" else "")
        val eventDesc = when (event) {
            is SessionStarted ->
                if (runner.state.mode == com.literacy.agent.model.Mode.REVIEW)
                    "事件：复习会话开始，按复习阶段教学（RECALL 引导回忆不展示答案，不 show_character）"
                else "事件：会话开始，请打招呼并开始教学"
            is VoiceInput -> "事件：用户语音「${event.text}」（意图=${event.intent}）"
            is WritingEvaluated -> "事件：书写评估 ${event.phase} 分数=${event.score} 通过=${event.ok}" +
                (if (event.issues.isNotEmpty()) " 问题=${event.issues.joinToString("；")}" else "")
            is ButtonTapped -> "事件：按钮 ${event.action}"
            is HelpRequested -> "事件：用户请求帮助"
            is SkipRequested -> "事件：用户请求跳过"
            is CharacterCompleted -> "事件：整字完成，请决定下一字"
            is EndRequested -> {
                // P1-2 + review-09 P2-3：current_session_results（§2 仅 EndRequested 注入）——
                // 只聚合本 session（boundSessionId 匹配），且排除 skip 与失败尝试（<0.6）
                val sessionResults = runner.store.results.filter { it.sessionId == runner.currentBoundSessionId }
                val learned = sessionResults
                    .filter { it.phase != "skip" && (it.score ?: 0.0) >= 0.6 }
                    .map { it.char }.distinct()
                val brief = if (learned.isEmpty()) "（本 session 暂无已完成尝试）"
                else "本 session 已学：${learned.joinToString("、")}；证据 ${sessionResults.size} 条"
                "事件：用户请求结束，请告别并 end_session 总结。\n<current_session_results>$brief</current_session_results>"
            }
            else -> "事件：$event"
        }
        return sb.append("\n").append(eventDesc).toString()
    }
}
