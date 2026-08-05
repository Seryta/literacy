package com.literacy.agent.model

/** 笔画轨迹点（MotionEvent 坐标 + 时间戳，供本地手写评估，RESEARCH-TECH）。 */
data class StrokePoint(val x: Float, val y: Float, val t: Long = 0L)

/**
 * 单字教学 9 阶段（AGENT-PROTOCOL §6.1）。
 * canonical phase 由本地持有，Agent 只能通过 allowed_actions 内动作请求迁移。
 */
enum class Phase(val display: String) {
    INTRODUCE("introduce"),
    RECOGNIZE("recognize"),
    DEMONSTRATE("demonstrate"),
    GUIDED_WRITE("guided_write"),
    INDEPENDENT_WRITE("independent_write"),
    EXPLAIN("explain"),
    SENTENCE("sentence"),
    RECORD("record"),
    DECIDE("decide");

    companion object {
        val SEQUENCE: List<Phase> = entries.toList()

        /** 下一阶段；DECIDE 之后为 null（进入下一字/复习/结束决策） */
        fun next(p: Phase): Phase? = SEQUENCE.getOrNull(SEQUENCE.indexOf(p) + 1)
    }
}

/** 学习路径（TEACHING-STRATEGY §3）：影响 independent_write 的检测方式（AGENT-PROTOCOL §6.3） */
enum class LearningPath(val check: IndependentCheck) {
    WRITE_PARALLEL(IndependentCheck.WRITE),
    READ_PRIMARY(IndependentCheck.AUDIO_CHOICE),
    READ_ONLY(IndependentCheck.FILL_BLANK);
}

/** independent_write 的检测通道（路径分支） */
enum class IndependentCheck { WRITE, AUDIO_CHOICE, FILL_BLANK }

/** 4 个能力维度（MASTERY-CRITERIA §1），独立评价 */
enum class Dimension { RECOGNIZE, WRITE, UNDERSTAND, APPLY }

/** 事件模型（AGENT-PROTOCOL §1）。仅列出本地裁决需要消费的字段。 */
sealed interface Event

data object SessionStarted : Event

/** 用户语音转写。intent 由 STT + 本地理解给出（mock 场景直接构造）。 */
data class VoiceInput(
    val text: String,
    val intent: VoiceIntent = VoiceIntent.OTHER,
) : Event

enum class VoiceIntent {
    RECOGNIZED,          // 认读正确
    WRONG,               // 认读错误
    REQUEST_PINYIN,      // 主动请求看拼音（recognize 成功条件之一）
    REQUEST_NEW_CHAR,    // 插单：用户要求学别的字（TEACHING-STRATEGY §1.2）
    SWITCH_PATH,         // 切换学习路径（TEACHING-STRATEGY §3.2）
    OTHER,
}

/** 本地书写评估结果（StrokeFinished → 本地评估 → WritingEvaluated，§4）。 */
data class WritingEvaluated(
    val phase: String,        // guided_write / independent_write / signature
    val score: Double,        // 0.0-1.0
    val ok: Boolean,          // 偏差在阈值内
    val promptLevel: Int,     // 本次尝试使用的提示等级（降难矩阵 L0-L6）
    val issues: List<String> = emptyList(),  // 本地评估给出的具体问题（"起笔偏左"等）
) : Event

/** 按钮动作；选择题判题结果由 isCorrect + exerciseId 携带（§4）。 */
data class ButtonTapped(
    val action: String,
    val isCorrect: Boolean? = null,
    val exerciseId: String? = null,
) : Event

data object CharacterCompleted : Event

data object EndRequested : Event

/** HelpRequested：用户按"帮助"按钮（触发 LLM）。 */
data object HelpRequested : Event

/** SkipRequested：用户按"跳过"按钮（触发 LLM）。 */
data object SkipRequested : Event

/** PauseRequested：本地暂停，不调 LLM（§1 暂停与恢复）。 */
data object PauseRequested : Event

/** IdleTimeout：等待点超时未响应（触发 LLM，§1）。 */
data class IdleTimeout(val waitingFor: String, val idleSeconds: Int) : Event

/** StrokeFinished：本地事件，书写完成后由本地评估并产生 WritingEvaluated（§4）。
 *  path 为用户笔画坐标序列（MotionEvent 轨迹），供本地规则引擎评估（RESEARCH-TECH）。 */
data class StrokeFinished(val stroke: Int, val path: List<StrokePoint> = emptyList()) : Event

/** TtsCompleted：信号事件；本轮预约了 listen 时此刻才真正开麦（§5）。 */
data object TtsCompleted : Event

/** RecognitionLowConfidence：本地澄清提示，不触发 LLM（§1）。 */
data class RecognitionLowConfidence(val confidence: Double, val partial: String? = null) : Event

/** RecognitionRepeatedFailures：连续多次 STT 失败，触发 LLM 降难（§1）。 */
data class RecognitionRepeatedFailures(val failureCount: Int, val lastPartialText: String? = null) : Event

/** ConfusableDetected：形近字混淆被检测到，触发 LLM 决定是否插入辨析（§1）。 */
data class ConfusableDetected(val char: String, val confusedChar: String, val trigger: String) : Event

/** 课程控制动作（AGENT-PROTOCOL §6.2 / SYSTEM-PROMPT 课程控制）。 */
enum class ControlAction(val toolName: String) {
    ADVANCE_PHASE("advance_phase"),
    COMPLETE_CHARACTER("complete_character"),
    SKIP_CHARACTER("skip_character"),
    START_REVIEW("start_review"),
    NEXT("next"),
    END_SESSION("end_session"),
}

/** 当前教学状态（对应 <lesson_state> 注入，AGENT-PROTOCOL §2）。
 *  mode：learning（单字教学）或 review（复习模式，§6.5）；
 *  reviewStage：复习模式内部阶段（recall → assess → reinforce → next）。 */
enum class Mode { LEARNING, REVIEW }

enum class ReviewStage { RECALL, ASSESS, REINFORCE, NEXT }

data class LessonState(
    val phase: Phase? = null,
    val char: String? = null,
    val allowedActions: Set<String> = emptySet(),
    val promptLevel: Int = 3,
    val learningPath: LearningPath = LearningPath.WRITE_PARALLEL,
    /**
     * 本次尝试的幂等键（§7.1，P1-6）：App 为每次真实尝试生成注入，
     * record_result 必须回传匹配（不信任模型自造 key）。null = mock/未注入（宽松）。
     */
    val idempotencyKey: String? = null,
    val mode: Mode = Mode.LEARNING,
    val reviewStage: ReviewStage? = null,
    /**
     * 本次尝试的本地裁决上下文（review-09 P1-7）：App 签发幂等键时绑定本地事件
     * （跟写/独立写评估分数、判题对错、识别方向）。record_result 以本地裁决为
     * 权威——模型不可把本地失败写成其他维度成功；null = mock/无本地事件（宽松）。
     */
    val attempt: AttemptContext? = null,
)

/**
 * 本地权威尝试结果（review-09 P1-7）：phase/score/dimension 由本地事件裁决，
 * record_result 校验时覆盖模型输入（score 优先本地、phase 必须匹配、维度按题型）。
 */
data class AttemptContext(
    val phase: String? = null,
    val score: Double? = null,
    val dimension: Dimension? = null,
    val exerciseType: String? = null,
    val promptLevel: Int? = null,   // review-11 P1-1.2：本地绑定的提示等级（裁决权威，模型字段仅落库展示）
    val issues: List<String> = emptyList(),   // review-10 P1-1：本次尝试的本地评估问题（绑定当次，不读上一笔）
)

/** LLM 输出（AGENT-PROTOCOL §3.1：完整 JSON，text + toolCalls）。 */
data class ToolCall(val name: String, val arguments: Map<String, Any?> = emptyMap())

data class LlmOutput(val text: String, val toolCalls: List<ToolCall> = emptyList())

/** characters 记录（STORAGE-DESIGN canonical schema 的内存版）。 */
data class CharacterRecord(
    val char: String,
    val masteryRecognize: Int = 0,
    val masteryWrite: Int = 0,
    val masteryUnderstand: Int = 0,
    val masteryApply: Int = 0,
    val status: String = "new",
    val currentPromptLevel: Int = 3,
    // P1-17：streak 按维度独立（MASTERY-CRITERIA §2：升级/降级要求的是该维度自身的连续计数）
    val streakRecognizeSuccess: Int = 0,
    val streakRecognizeErrors: Int = 0,
    val streakWriteSuccess: Int = 0,
    val streakWriteErrors: Int = 0,
    val streakUnderstandSuccess: Int = 0,
    val streakUnderstandErrors: Int = 0,
    val streakApplySuccess: Int = 0,
    val streakApplyErrors: Int = 0,
    // review-09 P1-11：达标链计数持久化（L3→L4 需 3 次间隔复习，跨天/重启不清零）
    val gateStreakRecognize: Int = 0,
    val gateStreakWrite: Int = 0,
    val gateStreakUnderstand: Int = 0,
    val gateStreakApply: Int = 0,
    // 各维度上次间隔累计的日期（随 gateStreak 持久化）——
    // lastReview 是整字共享，同日先 RECOGNIZE 后 WRITE 复习会被误当重复不累计；
    // 按维度记录后跨维度互不误伤（L3→L4 间隔日判定只认本维度上次达标日）
    val gateStreakDateRecognize: String? = null,
    val gateStreakDateWrite: String? = null,
    val gateStreakDateUnderstand: String? = null,
    val gateStreakDateApply: String? = null,
    val commonMistakes: List<String> = emptyList(),
    val source: String? = null,
    // SM-2 参数（按最弱维度计算，MASTERY-CRITERIA §5）
    val easeFactor: Double = 2.5,
    val intervalDays: Int = 0,
    val lastReview: String? = null,
    val nextReview: String? = null,
) {
    fun mastery(dim: Dimension): Int = when (dim) {
        Dimension.RECOGNIZE -> masteryRecognize
        Dimension.WRITE -> masteryWrite
        Dimension.UNDERSTAND -> masteryUnderstand
        Dimension.APPLY -> masteryApply
    }

    fun withMastery(dim: Dimension, level: Int): CharacterRecord = when (dim) {
        Dimension.RECOGNIZE -> copy(masteryRecognize = level)
        Dimension.WRITE -> copy(masteryWrite = level)
        Dimension.UNDERSTAND -> copy(masteryUnderstand = level)
        Dimension.APPLY -> copy(masteryApply = level)
    }

    /** 维度连续成功计数（P1-17：per-dimension streak）。 */
    fun streakSuccess(dim: Dimension): Int = when (dim) {
        Dimension.RECOGNIZE -> streakRecognizeSuccess
        Dimension.WRITE -> streakWriteSuccess
        Dimension.UNDERSTAND -> streakUnderstandSuccess
        Dimension.APPLY -> streakApplySuccess
    }

    /** 维度连续失败计数（P1-17：per-dimension streak）。 */
    fun streakErrors(dim: Dimension): Int = when (dim) {
        Dimension.RECOGNIZE -> streakRecognizeErrors
        Dimension.WRITE -> streakWriteErrors
        Dimension.UNDERSTAND -> streakUnderstandErrors
        Dimension.APPLY -> streakApplyErrors
    }

    /** 维度 streak 写入（成功/失败计数一次更新，另一个清零由调用方传 0）。 */
    fun withStreak(dim: Dimension, success: Int, errors: Int): CharacterRecord = when (dim) {
        Dimension.RECOGNIZE -> copy(streakRecognizeSuccess = success, streakRecognizeErrors = errors)
        Dimension.WRITE -> copy(streakWriteSuccess = success, streakWriteErrors = errors)
        Dimension.UNDERSTAND -> copy(streakUnderstandSuccess = success, streakUnderstandErrors = errors)
        Dimension.APPLY -> copy(streakApplySuccess = success, streakApplyErrors = errors)
    }

    /** 达标链计数（review-09 P1-11：MasteryAdjudicator 升级判定用，随记录持久化）。 */
    fun gateStreak(dim: Dimension): Int = when (dim) {
        Dimension.RECOGNIZE -> gateStreakRecognize
        Dimension.WRITE -> gateStreakWrite
        Dimension.UNDERSTAND -> gateStreakUnderstand
        Dimension.APPLY -> gateStreakApply
    }

    /** 达标链计数写入。 */
    fun withGateStreak(dim: Dimension, n: Int): CharacterRecord = when (dim) {
        Dimension.RECOGNIZE -> copy(gateStreakRecognize = n)
        Dimension.WRITE -> copy(gateStreakWrite = n)
        Dimension.UNDERSTAND -> copy(gateStreakUnderstand = n)
        Dimension.APPLY -> copy(gateStreakApply = n)
    }

    /** 该维度上次间隔累计日期（间隔日判定按维度，不共享整字 lastReview）。 */
    fun gateStreakDate(dim: Dimension): String? = when (dim) {
        Dimension.RECOGNIZE -> gateStreakDateRecognize
        Dimension.WRITE -> gateStreakDateWrite
        Dimension.UNDERSTAND -> gateStreakDateUnderstand
        Dimension.APPLY -> gateStreakDateApply
    }

    /** 该维度间隔累计日期写入（null = 链被打断/重置，下次成功重新起算）。 */
    fun withGateStreakDate(dim: Dimension, date: String?): CharacterRecord = when (dim) {
        Dimension.RECOGNIZE -> copy(gateStreakDateRecognize = date)
        Dimension.WRITE -> copy(gateStreakDateWrite = date)
        Dimension.UNDERSTAND -> copy(gateStreakDateUnderstand = date)
        Dimension.APPLY -> copy(gateStreakDateApply = date)
    }

    /** 任一维度存在连续失败计数（复习队列"最近出错"判定，SESSION-LIFECYCLE §1.2）。 */
    fun anyErrorStreak(): Boolean = Dimension.entries.any { streakErrors(it) > 0 }

    /** 整体状态推导（MASTERY-CRITERIA §3 简化）
     *  review-09 P1-14：reviewing 识别+书写都 ≥2、mastered 识别+书写都 ≥3（对齐 MASTERY-CRITERIA） */
    fun deriveStatus(): String = when {
        masteryRecognize == 0 && masteryWrite == 0 && masteryUnderstand == 0 && masteryApply == 0 -> "new"
        masteryRecognize >= 4 && masteryWrite >= 4 && masteryUnderstand >= 4 && masteryApply >= 4 -> "fully_mastered"   // P2
        masteryRecognize >= 3 && masteryWrite >= 3 && masteryUnderstand >= 2 -> "mastered"
        masteryRecognize >= 2 && masteryWrite >= 2 -> "reviewing"
        else -> "learning"
    }
}

/** name_plan 名字学习计划（STORAGE-DESIGN name_plan 表 / SESSION-LIFECYCLE §1.3）。 */
data class NamePlan(
    val fullName: String = "",
    val targetChars: List<String> = emptyList(),
    val priorityMode: String = "soft",
    val currentStage: String? = null,
    val recognitionReady: Boolean = false,
    val guidedWritingReady: Boolean = false,
    val independentWritingReady: Boolean = false,
    val signingReady: Boolean = false,
)

/** name_plan 派生摘要（SESSION-LIFECYCLE §1.3：不单独存库，由应用层派生）。 */
data class NamePlanStatus(
    val achievedSummary: String,
    val nextMilestone: String,
)

/** sessions 会话记录（STORAGE-DESIGN sessions 表 / SESSION-LIFECYCLE §1）。 */
data class Session(
    val id: Int = 0,
    val date: String,
    val startedAt: String,
    val endedAt: String? = null,
    val status: String = "active",   // active / completed / aborted
    val charsLearned: Int = 0,
    val charsReviewed: Int = 0,
    val namePlanProgress: String? = null,
    val highlights: String? = null,
    val struggles: String? = null,
    val durationSeconds: Int = 0,
)

/** session_character_results 证据记录（AGENT-PROTOCOL §8）。 */
data class SessionResult(
    val sessionId: Int,
    val char: String,
    val phase: String,        // recognize / guided_write / independent_write / explain / sentence / assess / signature / skip
    val exerciseType: String? = null,
    val score: Double? = null,
    val promptLevel: String? = null,
    val issues: List<String> = emptyList(),
    val idempotencyKey: String,
)
