package com.literacy.agent.replay

import com.literacy.agent.model.ButtonTapped
import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.Dimension
import com.literacy.agent.model.Event
import com.literacy.agent.model.LearningPath
import com.literacy.agent.model.LessonState
import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.NamePlan
import com.literacy.agent.model.Phase
import com.literacy.agent.model.Session
import com.literacy.agent.model.ToolCall
import com.literacy.agent.model.VoiceInput
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.model.WritingEvaluated
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 前置状态（从用例"前置状态"YAML 解析，支持字段：
 * lesson_state / learner_profile / name_plan / review_queue / characters / sessions）。
 */
data class CaseSetup(
    val lessonState: LessonState = LessonState(),
    val reviewQueue: List<String> = emptyList(),
    val characters: Map<String, CharacterRecord> = emptyMap(),
    val displayName: String = "",
    val namePlan: NamePlan? = null,
    val sessions: List<Session> = emptyList(),
)

/**
 * golden turn 期望断言（从用例"期望行为"YAML 解析）。
 * 字段映射见 test-cases/README.md 断言粒度约定。
 */
data class CaseAssertions(
    val textContains: List<String> = emptyList(),
    val textNotContains: List<String> = emptyList(),
    val expectedPhase: String? = null,
    val expectedFinalPhase: String? = null,            // 回放中到达过的阶段（GT-020 闭环）
    val expectedPromptLevel: Int? = null,
    val expectedMode: String? = null,               // learning / review
    val expectedChar: String? = null,               // 当前字（GT-054 current_review_char）
    val expectedAllowedActions: Set<String>? = null,
    val expectedReviewStage: String? = null,
    val expectedMastery: List<Triple<String, String, Int>> = emptyList(), // (char, dimension, level)
    val expectedFields: List<StorageFieldExpectation> = emptyList(),     // (char, field, value)
    val expectedNamePlan: Map<String, Any?> = emptyMap(),                // GT-063 signing_ready 等
    val expectedResultCount: Int? = null,
    val expectedControl: Map<String, Boolean> = emptyMap(),  // 课程控制动作 -> 期望允许与否
    val toolCallArgs: Map<String, Map<String, Any?>> = emptyMap(),      // 工具名 -> 期望参数（一致性）
    val localHandling: Map<String, Any?> = emptyMap(),
    val inputGuard: Map<String, Any?> = emptyMap(),
    val ttsContains: List<String> = emptyList(),
    val ttsNotContains: List<String> = emptyList(),
    val expectedSessionStatus: String? = null,   // 最新 session 的期望状态（completed / aborted / active）
    val expectedSessions: List<Pair<Int, String>> = emptyList(),  // (session id, 期望状态)
)

/** storage 字段断言（characters 非维度字段：streak / prompt_level / interval 等）。 */
data class StorageFieldExpectation(val char: String, val field: String, val expected: Any?)

/** 事件序列时间线项：外部事件 / mock LLM 输出 / provider 失败按序交错（turn 模型）。 */
sealed interface TimelineItem

/** 外部事件（回放输入）。 */
data class TimelineEvent(val event: Event) : TimelineItem

/** mock LLM 输出（事件序列中的 toolCall / llm_output 行）。 */
data class TimelineOutput(val output: LlmOutput) : TimelineItem

/** 模拟 provider 失败（GT-011：EndRequested 后走本地兜底）。 */
data class TimelineFailure(val kind: String) : TimelineItem

/** 一个可执行的 golden turn 用例。 */
data class GoldenCase(
    val id: String,
    val title: String,
    val module: String,
    val setup: CaseSetup,
    val timeline: List<TimelineItem>,       // 事件与 LLM 输出按序交错（真实 turn 模型）
    val assertions: CaseAssertions,
) {
    /** 兼容派生：仅外部事件。 */
    val inputEvents: List<Event> get() = timeline.filterIsInstance<TimelineEvent>().map { it.event }

    /** 兼容派生：仅 mock LLM 输出。 */
    val llmScript: List<LlmOutput> get() = timeline.filterIsInstance<TimelineOutput>().map { it.output }

    /** provider 失败标记（GT-011）。 */
    val providerFailures: List<String> get() = timeline.filterIsInstance<TimelineFailure>().map { it.kind }
}

/** 解析结果：成功用例 + 无法解析的块清单（用于规范化报告）。 */
data class LoadResult(val cases: List<GoldenCase>, val problems: List<String>)

/**
 * legacy streak（不带维度）目标维度推断：掌握等级严格最高者；平局/全 0 → WRITE
 * （GT-028 独立写降难流的两个无锚点用例均为 independent_write，WRITE 承接独立写流语义）。
 *
 * 双轨差异（勿混读）：与 v2→v3 迁移 SQL（AppDatabase.MIGRATION_2_3）不同轨——
 * 迁移对平局/无锚点 → 8 列全 0（迁移无后续写流上下文，清零最保守）；此处 → WRITE。
 * 三条规则（迁移 SQL / CaseLoader / Assertions）各按自己的锚点语境解释。
 */
internal fun streakDimFor(rec: CharacterRecord): Dimension {
    val max = Dimension.entries.map { rec.mastery(it) }.maxOrNull() ?: 0
    val tops = Dimension.entries.filter { rec.mastery(it) == max }
    return if (max > 0 && tops.size == 1) tops.single() else Dimension.WRITE
}

/**
 * CaseLoader：从 test-cases/ 目录的 markdown 文件解析 golden turn 用例。
 *
 * 解析策略（宽松子集）：
 * - 按 "## GT-" 分节；提取 模块 / 前置状态 / 事件序列 / 期望行为 四个区块
 * - 事件序列：event 行 → 回放输入；toolCall 行 → mock LLM 脚本（TTD 中即期望输出）
 * - 期望行为：text / state / storage / local_handling 的规范字段；无法识别的字段跳过并记录
 *
 * 已知限制（规范化清单见 README）：一行格式（`- event: X, payload: {...}`）与
 * payload 内混入中文注释（如 `payload: { score: 0.85, 偏差在阈值内 }`）无法解析，
 * 记录为 problem 而非失败。
 */
class CaseLoader {

    private val intentResolver = com.literacy.agent.learning.IntentResolver()

    fun loadFiles(dir: File): LoadResult {
        val cases = mutableListOf<GoldenCase>()
        val problems = mutableListOf<String>()
        dir.listFiles { f -> f.name.startsWith("T") && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.forEach { file -> load(file).also { cases += it.cases; problems += it.problems } }
        return LoadResult(cases, problems)
    }

    fun load(file: File): LoadResult {
        val text = file.readText()
        val sections = text.split(Regex("(?m)^## GT-"))
        if (sections.size <= 1) return LoadResult(emptyList(), listOf("${file.name}: 未找到 GT 用例"))

        val cases = mutableListOf<GoldenCase>()
        val problems = mutableListOf<String>()
        for (section in sections.drop(1)) {
            val id = "GT-" + section.takeWhile { it.isDigit() }
            val title = section.substringAfter(" ").lineSequence().first().trim()
            try {
                val (case, caseProblems) = parseCase(id, title, file.name, section)
                cases += case
                problems += caseProblems
            } catch (e: Exception) {
                problems += "$id (${file.name}): 解析失败 - ${e.message}"
            }
        }
        return LoadResult(cases, problems)
    }

    /** 宽松解析：单个区块解析失败只记录 problem，不丢弃整个用例。 */
    private fun parseCase(id: String, title: String, fileName: String, section: String): Pair<GoldenCase, List<String>> {
        val problems = mutableListOf<String>()
        val module = Regex("\\*\\*模块\\*\\*：([^|]+)").find(section)?.groupValues?.get(1)?.trim() ?: ""
        val setupYaml = extractYamlBlock(section, "前置状态")
        val eventsYaml = extractYamlBlock(section, "事件序列")
        val expectYaml = extractYamlBlock(section, "期望行为")

        val setup = try {
            parseSetup(setupYaml)
        } catch (e: Exception) {
            problems += "$id (${fileName}): 前置状态解析失败 - ${e.message}"
            CaseSetup()
        }
        val parsed = try {
            parseEvents(eventsYaml)
        } catch (e: Exception) {
            problems += "$id (${fileName}): 事件序列解析失败 - ${e.message}"
            ParsedEvents()
        }
        val assertions = try {
            parseAssertions(expectYaml)
        } catch (e: Exception) {
            problems += "$id (${fileName}): 期望行为解析失败 - ${e.message}"
            CaseAssertions()
        }
        return Pair(
            GoldenCase(id, title, module, setup, parsed.timeline, assertions),
            problems,
        )
    }

    /** 提取指定标题下的第一个 ```yaml 代码块。 */
    private fun extractYamlBlock(section: String, header: String): String {
        val start = section.indexOf("**$header**") + "**$header**".length
        if (start < "**$header**".length) return ""
        val fence = section.indexOf("```yaml", start)
        if (fence < 0) return ""
        val contentStart = fence + "```yaml".length
        val contentEnd = section.indexOf("```", contentStart)
        if (contentEnd < 0) return ""
        return section.substring(contentStart, contentEnd)
    }

    private fun parseSetup(yaml: String): CaseSetup {
        if (yaml.isBlank()) return CaseSetup()
        val root = Yaml().load<Any>(yaml) as? Map<*, *> ?: return CaseSetup()
        var lesson: LessonState? = null
        var reviewQueue: List<String> = emptyList()
        var path: LearningPath? = null
        var characters: Map<String, CharacterRecord> = emptyMap()
        var displayName = ""
        var namePlan: NamePlan? = null
        var sessions: List<Session> = emptyList()
        // 先 lesson_state，再 learner_profile：profile 的 learning_path 优先
        //（路径归属 profile.learning_path，TEACHING-STRATEGY §3.2）
        root.forEach { (k, v) ->
            if (k.toString() == "lesson_state") lesson = parseLessonState(v as? Map<*, *> ?: return@forEach)
        }
        root.forEach { (k, v) ->
            when (k.toString()) {
                "learner_profile", "profile" -> {   // profile 与 learner_profile 同义（STORAGE-DESIGN）
                    val profile = v as? Map<*, *> ?: return@forEach
                    path = profile["learning_path"]?.toString()?.let(::parsePath)
                    displayName = profile["display_name"]?.toString() ?: ""
                }
                "name_plan" -> namePlan = parseNamePlan(v)
                "review_queue" ->
                    reviewQueue = (v as? List<*>)?.mapNotNull { it.toString() } ?: emptyList()
                "characters" -> characters = parseCharacters(v)
                "sessions" -> sessions = parseSessions(v)
            }
        }
        val base = lesson ?: LessonState()
        return CaseSetup(base.copy(learningPath = path ?: base.learningPath), reviewQueue, characters, displayName, namePlan, sessions)
    }

    /** name_plan 前置（target_chars / signing_ready 等，STORAGE-DESIGN）。 */
    private fun parseNamePlan(v: Any?): NamePlan {
        val m = v as? Map<*, *> ?: return NamePlan()
        return NamePlan(
            fullName = m["full_name"]?.toString() ?: "",
            targetChars = (m["target_chars"] as? List<*>)?.mapNotNull { it.toString() } ?: emptyList(),
            priorityMode = m["priority_mode"]?.toString() ?: "soft",
            currentStage = m["current_stage"]?.toString(),
            recognitionReady = m["recognition_ready"] as? Boolean ?: false,
            guidedWritingReady = m["guided_writing_ready"] as? Boolean ?: false,
            independentWritingReady = m["independent_writing_ready"] as? Boolean ?: false,
            signingReady = m["signing_ready"] as? Boolean ?: false,
        )
    }

    /** sessions 前置注入（id 按给定值，GT-016）。 */
    private fun parseSessions(v: Any?): List<Session> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            Session(
                id = (m["id"] as? Number)?.toInt() ?: return@mapNotNull null,
                date = m["date"]?.toString() ?: "",
                startedAt = m["started_at"]?.toString() ?: "",
                endedAt = m["ended_at"]?.toString(),
                status = m["status"]?.toString() ?: "active",
                highlights = m["highlights"]?.toString(),
                struggles = m["struggles"]?.toString(),
            )
        }
    }

    /** characters 前置注入（掌握等级 / streak / 提示等级等）。
     *  P1-17：streak 支持 per-dimension 键（streak_recognize_success 等）；legacy 键
     *  streak_success/streak_errors（不带维度，历史夹具）按 [streakDimFor] 推断目标维度。 */
    private fun parseCharacters(v: Any?): Map<String, CharacterRecord> {
        val chars = v as? Map<*, *> ?: return emptyMap()
        return chars.mapNotNull { (char, fields) ->
            val f = fields as? Map<*, *> ?: return@mapNotNull null
            val raw = f.entries.associate { it.key.toString() to it.value }
            // 先掌握等级（legacy streak 推断依赖 mastery 锚点，避免 YAML 键序影响）
            var rec = CharacterRecord(
                char = char.toString(),
                masteryRecognize = (raw["mastery_recognize"] as? Number)?.toInt() ?: 0,
                masteryWrite = (raw["mastery_write"] as? Number)?.toInt() ?: 0,
                masteryUnderstand = (raw["mastery_understand"] as? Number)?.toInt() ?: 0,
                masteryApply = (raw["mastery_apply"] as? Number)?.toInt() ?: 0,
            )
            // streak：per-dimension 键直读
            raw["streak_recognize_success"]?.let { rec = rec.copy(streakRecognizeSuccess = (it as Number).toInt()) }
            raw["streak_recognize_errors"]?.let { rec = rec.copy(streakRecognizeErrors = (it as Number).toInt()) }
            raw["streak_write_success"]?.let { rec = rec.copy(streakWriteSuccess = (it as Number).toInt()) }
            raw["streak_write_errors"]?.let { rec = rec.copy(streakWriteErrors = (it as Number).toInt()) }
            raw["streak_understand_success"]?.let { rec = rec.copy(streakUnderstandSuccess = (it as Number).toInt()) }
            raw["streak_understand_errors"]?.let { rec = rec.copy(streakUnderstandErrors = (it as Number).toInt()) }
            raw["streak_apply_success"]?.let { rec = rec.copy(streakApplySuccess = (it as Number).toInt()) }
            raw["streak_apply_errors"]?.let { rec = rec.copy(streakApplyErrors = (it as Number).toInt()) }
            // legacy 键：推断目标维度（历史夹具兼容，T002/T004 全部用例）。
            // 混合键语义：legacy（streak_success/streak_errors）与 per-dimension 键互斥使用——
            // 同时出现时 legacy 后处理覆盖 per-dimension（当前 53 夹具无混合；兼容约定，非组合语义）
            raw["streak_success"]?.let {
                val dim = streakDimFor(rec)
                rec = rec.withStreak(dim, (it as Number).toInt(), rec.streakErrors(dim))
            }
            raw["streak_errors"]?.let {
                val dim = streakDimFor(rec)
                rec = rec.withStreak(dim, rec.streakSuccess(dim), (it as Number).toInt())
            }
            rec = rec.copy(
                currentPromptLevel = (raw["current_prompt_level"] as? Number)?.toInt() ?: 3,
                status = raw["status"]?.toString() ?: "new",
                easeFactor = (raw["ease_factor"] as? Number)?.toDouble() ?: 2.5,
                intervalDays = (raw["interval_days"] as? Number)?.toInt() ?: 0,
                lastReview = raw["last_review"]?.toString(),
                nextReview = raw["next_review"]?.toString(),
                source = raw["source"]?.toString(),
            )
            char.toString() to rec
        }.toMap()
    }

    private fun parseLessonState(m: Map<*, *>): LessonState {
        val phase = m["phase"]?.toString()?.let { Phase.entries.firstOrNull { p -> p.display == it } }
        val actions = (m["allowed_actions"] as? List<*>)?.mapNotNull { it.toString() }?.toSet() ?: emptySet()
        val prompt = (m["prompt_level"] as? Number)?.toInt() ?: 3
        val mode = when (m["mode"]?.toString()) {
            "review" -> com.literacy.agent.model.Mode.REVIEW
            else -> com.literacy.agent.model.Mode.LEARNING
        }
        val reviewStage = m["review_stage"]?.toString()?.let { s ->
            com.literacy.agent.model.ReviewStage.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
        }
        return LessonState(
            phase = phase, allowedActions = actions, promptLevel = prompt,
            mode = mode, reviewStage = reviewStage,
        )
    }

    private fun parsePath(s: String): LearningPath = when (s) {
        "read_primary" -> LearningPath.READ_PRIMARY
        "read_only" -> LearningPath.READ_ONLY
        else -> LearningPath.WRITE_PARALLEL
    }

    /** 事件序列解析结果：事件与 LLM 输出按序交错（真实 turn 模型）。 */
    data class ParsedEvents(val timeline: List<TimelineItem> = emptyList())

    private fun parseEvents(yaml: String): ParsedEvents {
        if (yaml.isBlank()) return ParsedEvents()
        val normalized = normalizeOneLineFormat(yaml)
        val root = Yaml().load<Any>(normalized)
        val list = root as? List<*> ?: return ParsedEvents()
        val timeline = mutableListOf<TimelineItem>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            when {
                map.containsKey("event") -> toEvent(map)?.let { timeline += TimelineEvent(it) }
                map.containsKey("toolCall") -> toLlmOutput(map)?.let { timeline += TimelineOutput(it) }
                map.containsKey("toolCalls") -> toLlmOutputs(map).forEach { timeline += TimelineOutput(it) }
                map.containsKey("llm_output") -> toLlmOutputFull(map).forEach { timeline += TimelineOutput(it) }   // GT-014
                map.containsKey("provider_failure") ->
                    timeline += TimelineFailure(map["provider_failure"]?.toString() ?: "unknown")   // GT-011
            }
        }
        return ParsedEvents(timeline)
    }

    /**
     * 一行格式预处理：`- event: X, payload: {...}` / `- toolCall: { name: Y }`
     * 规范化为两行标准 YAML（宽松子集；不匹配的行原样保留）。
     */
    private fun normalizeOneLineFormat(yaml: String): String =
        yaml.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            val m = Regex("^- (event|toolCall): ([^,{]+), (payload|arguments): (.*)$").find(trimmed)
            if (m != null) {
                "- ${m.groupValues[1]}: ${m.groupValues[2]}\n  ${m.groupValues[3]}: ${m.groupValues[4]}"
            } else line
        }

    private fun toEvent(m: Map<*, *>): Event? {
        val type = m["event"].toString()
        val payload = m["payload"] as? Map<*, *> ?: emptyMap<String, Any>()
        return when (type) {
            "SessionStarted" -> com.literacy.agent.model.SessionStarted
            "VoiceInput" -> {
                val isCorrect = payload["is_correct"] as? Boolean
                val text = payload["text"]?.toString() ?: ""
                val explicitIntent = payload["intent"]?.toString()?.let(::parseIntent)
                VoiceInput(
                    text = text,
                    // 显式 intent 优先；缺省时本地理解推导（IntentResolver，真实链路 STT→意图）
                    intent = explicitIntent
                        ?: intentResolver.activeIntent(text)
                        // 选项作答判对（听音选字等）等价于认对
                        ?: if (isCorrect == true) VoiceIntent.RECOGNIZED else VoiceIntent.OTHER,
                )
            }
            "WritingEvaluated" -> WritingEvaluated(
                phase = payload["phase"]?.toString() ?: "independent_write",
                score = (payload["score"] as? Number)?.toDouble() ?: 0.0,
                ok = payload["ok"] as? Boolean ?: true,
                promptLevel = promptLevelToInt(payload["prompt_level"]),
            )
            "ButtonTapped" -> ButtonTapped(
                action = payload["action"]?.toString() ?: "",
                isCorrect = payload["is_correct"] as? Boolean,
                exerciseId = payload["exercise_id"]?.toString(),
            )
            "CharacterCompleted" -> com.literacy.agent.model.CharacterCompleted
            "EndRequested" -> com.literacy.agent.model.EndRequested
            "HelpRequested" -> com.literacy.agent.model.HelpRequested
            "SkipRequested" -> com.literacy.agent.model.SkipRequested
            "PauseRequested" -> com.literacy.agent.model.PauseRequested
            "IdleTimeout" -> com.literacy.agent.model.IdleTimeout(
                waitingFor = payload["waiting_for"]?.toString() ?: "voice",
                idleSeconds = (payload["idle_seconds"] as? Number)?.toInt() ?: 0,
            )
            "StrokeFinished" -> com.literacy.agent.model.StrokeFinished(
                stroke = (payload["stroke"] as? Number)?.toInt() ?: 1,
                path = parseStrokePath(payload["path"]),
            )
            "TtsCompleted" -> com.literacy.agent.model.TtsCompleted
            "RecognitionLowConfidence" -> com.literacy.agent.model.RecognitionLowConfidence(
                confidence = (payload["confidence"] as? Number)?.toDouble() ?: 0.0,
                partial = payload["partial"]?.toString(),
            )
            "RecognitionRepeatedFailures" -> com.literacy.agent.model.RecognitionRepeatedFailures(
                failureCount = (payload["failure_count"] as? Number)?.toInt() ?: 3,
                lastPartialText = payload["last_partial_text"]?.toString(),
            )
            "ConfusableDetected" -> com.literacy.agent.model.ConfusableDetected(
                char = payload["char"]?.toString() ?: "",
                confusedChar = payload["confused_char"]?.toString() ?: "",
                trigger = payload["trigger"]?.toString() ?: "",
            )
            else -> null
        }
    }

    /** 笔画轨迹解析：`path: [{x: 0, y: 0}, ...]` 或 `path: [[x, y], ...]`。 */
    private fun parseStrokePath(v: Any?): List<com.literacy.agent.model.StrokePoint> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> {
                    val x = (item["x"] as? Number)?.toFloat() ?: return@mapNotNull null
                    val y = (item["y"] as? Number)?.toFloat() ?: return@mapNotNull null
                    com.literacy.agent.model.StrokePoint(x, y)
                }
                is List<*> -> {
                    val x = item.getOrNull(0) as? Number ?: return@mapNotNull null
                    val y = item.getOrNull(1) as? Number ?: return@mapNotNull null
                    com.literacy.agent.model.StrokePoint(x.toFloat(), y.toFloat())
                }
                else -> null
            }
        }
    }

    private fun parseIntent(s: String): VoiceIntent = when (s) {
        "RECOGNIZED" -> VoiceIntent.RECOGNIZED
        "WRONG" -> VoiceIntent.WRONG
        "REQUEST_PINYIN" -> VoiceIntent.REQUEST_PINYIN
        "REQUEST_NEW_CHAR" -> VoiceIntent.REQUEST_NEW_CHAR
        "SWITCH_PATH" -> VoiceIntent.SWITCH_PATH
        else -> VoiceIntent.OTHER
    }

    /** prompt_level 口径统一：字符串枚举（none/hint/full_demo，§7.1）映射到降难矩阵数字（L0-L6）。 */
    private fun promptLevelToInt(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        "none" -> 0
        "hint" -> 1
        "full_demo" -> 4
        else -> 0
    }

    private fun toLlmOutput(m: Map<*, *>): LlmOutput? {
        val tc = m["toolCall"] as? Map<*, *> ?: return null
        val name = tc["name"]?.toString() ?: return null
        val args = tc["arguments"] as? Map<*, *> ?: emptyMap<String, Any>()
        @Suppress("UNCHECKED_CAST")
        return LlmOutput(tc["text"]?.toString() ?: "", listOf(ToolCall(name, args as Map<String, Any?>)))
    }

    /** llm_output 完整输出行（GT-014）：text + toolCalls 一并提供。 */
    private fun toLlmOutputFull(m: Map<*, *>): List<LlmOutput> {
        val out = m["llm_output"] as? Map<*, *> ?: return emptyList()
        val text = out["text"]?.toString() ?: ""
        val calls = (out["toolCalls"] as? List<*>)
            ?.mapNotNull { item ->
                val mm = item as? Map<*, *> ?: return@mapNotNull null
                val name = mm["name"]?.toString() ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val args = (mm["arguments"] as? Map<*, *>) as? Map<String, Any?> ?: emptyMap()
                ToolCall(name, args)
            } ?: emptyList()
        return if (text.isEmpty() && calls.isEmpty()) emptyList() else listOf(LlmOutput(text, calls))
    }

    private fun toLlmOutputs(m: Map<*, *>): List<LlmOutput> {
        // toolCalls 复数：`toolCalls: [show_character, show_pinyin, ...]`（名称列表，GT-013）
        val names = m["toolCalls"] as? List<*> ?: return emptyList()
        val calls = names.mapNotNull { name ->
            val n = name?.toString() ?: return@mapNotNull null
            ToolCall(n, emptyMap())
        }
        return if (calls.isEmpty()) emptyList() else listOf(LlmOutput("", calls))
    }

    private fun parseAssertions(yaml: String): CaseAssertions {
        if (yaml.isBlank()) return CaseAssertions()
        val root = Yaml().load<Any>(yaml) as? Map<*, *> ?: return CaseAssertions()
        var a = CaseAssertions()
        root.forEach { (k, v) ->
            when (k.toString()) {
                "text" -> {
                    val contains = (v as? Map<*, *>)?.get("contains") as? List<*> ?: emptyList<Any>()
                    val notContains = (v as? Map<*, *>)?.get("not_contains") as? List<*> ?: emptyList<Any>()
                    a = a.copy(
                        textContains = contains.mapNotNull { it.toString() },
                        textNotContains = notContains.mapNotNull { it.toString() },
                    )
                }
                "text_tts" -> {   // GT-014：过滤后实际朗读文本的断言
                    val contains = (v as? Map<*, *>)?.get("contains") as? List<*> ?: emptyList<Any>()
                    val notContains = (v as? Map<*, *>)?.get("not_contains") as? List<*> ?: emptyList<Any>()
                    a = a.copy(
                        ttsContains = contains.mapNotNull { it.toString() },
                        ttsNotContains = notContains.mapNotNull { it.toString() },
                    )
                }
                "toolCalls" -> {
                    // required/forbidden 支持字符串或 {name, arguments} Map 两种形式
                    fun extractNames(list: List<*>?): List<String> = list?.mapNotNull { item ->
                        when (item) {
                            is String -> item
                            is Map<*, *> -> item["name"]?.toString()
                            else -> null
                        }
                    } ?: emptyList()
                    val required = (v as? Map<*, *>)?.get("required") as? List<*>
                    val forbidden = (v as? Map<*, *>)?.get("forbidden") as? List<*>
                    a = a.copy(
                        expectedControl = extractNames(required).associate { it to true } +
                            extractNames(forbidden).associate { it to false },
                    )
                }
                "state" -> {
                    val sm = v as? Map<*, *>
                    a = a.copy(
                        expectedPhase = sm?.get("phase")?.toString(),
                        expectedFinalPhase = sm?.get("final_phase")?.toString()
                            ?: sm?.get("final_phase")?.toString(),
                        expectedPromptLevel = (sm?.get("prompt_level") as? Number)?.toInt(),
                        expectedMode = sm?.get("mode")?.toString(),
                        expectedChar = sm?.get("current_review_char")?.toString()
                            ?: sm?.get("current_char")?.toString(),
                        expectedReviewStage = sm?.get("stage")?.toString(),
                        expectedAllowedActions = (sm?.get("allowed_actions") as? List<*>)
                            ?.mapNotNull { it.toString() }?.toSet(),
                    )
                }
                "toolCall_args" -> {
                    val argsMap = mutableMapOf<String, Map<String, Any?>>()
                    (v as? Map<*, *>)?.forEach { (toolName, args) ->
                        val inner = (args as? Map<*, *>)?.entries
                            ?.associate { it.key.toString() to it.value } ?: emptyMap()
                        argsMap[toolName.toString()] = inner
                    }
                    a = a.copy(toolCallArgs = argsMap)
                }
                "storage" -> parseStorage(v).also { s ->
                    a = a.copy(
                        expectedMastery = a.expectedMastery + s.mastery,
                        expectedFields = a.expectedFields + s.fields,
                        expectedResultCount = s.resultCount ?: a.expectedResultCount,
                        expectedSessionStatus = s.sessionStatus ?: a.expectedSessionStatus,
                        expectedSessions = a.expectedSessions + s.sessions,
                        expectedNamePlan = a.expectedNamePlan + s.namePlan,
                    )
                }
                "input_guard" -> a = a.copy(
                    inputGuard = (v as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap(),
                )
                "local_handling" -> a = a.copy(
                    localHandling = (v as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap(),
                )
            }
        }
        return a
    }

    /** storage 块：characters 掌握等级断言 + 证据条数 + session 状态断言（宽松子集）。 */
    private fun parseStorage(v: Any?): StorageExpectations {
        val root = v as? Map<*, *> ?: return StorageExpectations()
        var mastery = emptyList<Triple<String, String, Int>>()
        var fields = emptyList<StorageFieldExpectation>()
        var namePlan = emptyMap<String, Any?>()
        var count: Int? = null
        var sessionStatus: String? = null
        var sessions = emptyList<Pair<Int, String>>()
        root.forEach { (k, value) ->
            when (k.toString()) {
                "name_plan" -> namePlan = (value as? Map<*, *>)?.entries
                    ?.associate { it.key.toString() to it.value } ?: emptyMap()
                "characters" -> {
                    val chars = value as? Map<*, *> ?: return@forEach
                    chars.forEach { (char, fieldsMap) ->
                        val f = fieldsMap as? Map<*, *> ?: return@forEach
                        f.forEach { (dim, level) ->
                            val dimName = when (dim.toString()) {
                                "mastery_recognize" -> "recognize"
                                "mastery_write" -> "write"
                                "mastery_understand" -> "understand"
                                "mastery_apply" -> "apply"
                                else -> null
                            }
                            if (dimName != null) mastery += Triple(char.toString(), dimName, (level as Number).toInt())
                            else fields += StorageFieldExpectation(char.toString(), dim.toString(), level)
                        }
                    }
                }
                "session_character_results_count" -> count = (value as Number).toInt()
                "sessions" -> {
                    // 单对象形式：sessions: { status: completed }（GT-011/017）
                    if (value is Map<*, *>) {
                        sessionStatus = value["status"]?.toString()
                    } else {
                        val list = value as? List<*> ?: return@forEach
                        sessions = list.mapNotNull { item ->
                            val m = item as? Map<*, *> ?: return@mapNotNull null
                            val id = m["id"] as? Number ?: return@mapNotNull null
                            Pair(id.toInt(), m["status"]?.toString() ?: "")
                        }
                    }
                }
            }
        }
        return StorageExpectations(mastery, fields, count, sessionStatus, sessions, namePlan)
    }

    private data class StorageExpectations(
        val mastery: List<Triple<String, String, Int>> = emptyList(),
        val fields: List<StorageFieldExpectation> = emptyList(),
        val resultCount: Int? = null,
        val sessionStatus: String? = null,
        val sessions: List<Pair<Int, String>> = emptyList(),
        val namePlan: Map<String, Any?> = emptyMap(),
    )
}
