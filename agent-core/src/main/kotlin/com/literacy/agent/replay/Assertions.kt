package com.literacy.agent.replay

import com.literacy.agent.model.LessonState
import com.literacy.agent.store.LearningStore

/**
 * 断言器：对回放结果执行 golden turn 期望断言。
 *
 * 断言粒度约定（test-cases/README.md）：
 * - text：宽松语义断言（contains / not_contains 关键词）
 * - state / storage：精确断言
 * - local_handling：精确断言
 */
class Assertions {

    /** text 语义断言：所有 contains 关键词都出现，且没有 not_contains 关键词。 */
    fun text(lastText: String?, contains: List<String>, notContains: List<String>): List<String> {
        val text = lastText ?: return listOf("LLM 未输出 text")
        val misses = contains.filter { it !in text }
        val forbiddenHits = notContains.filter { it in text }
        val problems = mutableListOf<String>()
        if (misses.isNotEmpty()) problems += "text 缺少关键词: $misses"
        if (forbiddenHits.isNotEmpty()) problems += "text 出现禁止关键词: $forbiddenHits"
        return problems
    }

    /** state 断言：期望的 phase 与当前一致。 */
    fun phase(state: LessonState, expected: String?): List<String> {
        val actual = state.phase?.display
        return if (actual == expected) emptyList()
        else listOf("phase 期望 $expected 实际 $actual")
    }

    /** storage 断言：characters 掌握等级。 */
    fun mastery(store: LearningStore, char: String, dimension: String, expected: Int): List<String> {
        val record = store.getCharacter(char)
        val actual = when (dimension) {
            "recognize" -> record.masteryRecognize
            "write" -> record.masteryWrite
            "understand" -> record.masteryUnderstand
            "apply" -> record.masteryApply
            else -> return listOf("未知维度 $dimension")
        }
        return if (actual == expected) emptyList()
        else listOf("$char.$dimension 期望 $expected 实际 $actual")
    }

    /** storage 断言：证据记录条数（幂等验证）。 */
    fun resultCount(store: LearningStore, expected: Int): List<String> =
        if (store.results.size == expected) emptyList()
        else listOf("session_character_results 期望 $expected 条 实际 ${store.results.size} 条")

    /** storage 断言：characters 非维度字段（streak / prompt_level / interval 等）。
     *  P1-17：streak 支持 per-dimension 键（streak_write_errors 等）；legacy 键
     *  streak_success/streak_errors（不带维度）按共享的 [streakDimFor] 推断目标维度（与 CaseLoader 同规则）。 */
    fun field(store: LearningStore, char: String, field: String, expected: Any?): List<String> {
        val rec = store.getCharacter(char)
        val actual: Any? = when (field) {
            "streak_success" -> rec.streakSuccess(streakDimFor(rec))
            "streak_errors" -> rec.streakErrors(streakDimFor(rec))
            "streak_recognize_success" -> rec.streakRecognizeSuccess
            "streak_recognize_errors" -> rec.streakRecognizeErrors
            "streak_write_success" -> rec.streakWriteSuccess
            "streak_write_errors" -> rec.streakWriteErrors
            "streak_understand_success" -> rec.streakUnderstandSuccess
            "streak_understand_errors" -> rec.streakUnderstandErrors
            "streak_apply_success" -> rec.streakApplySuccess
            "streak_apply_errors" -> rec.streakApplyErrors
            "current_prompt_level" -> rec.currentPromptLevel
            "interval_days" -> rec.intervalDays
            "ease_factor" -> rec.easeFactor
            "status" -> rec.status
            "source" -> rec.source
            else -> return listOf("未知字段 $field")
        }
        val norm: Any? = when (field) {
            "ease_factor" -> (expected as? Number)?.toDouble()
            else -> (expected as? Number)?.toInt() ?: expected
        }
        return if (actual == norm) emptyList()
        else listOf("$char.$field 期望 $norm 实际 $actual")
    }

    /** 组合断言：返回所有问题，空 = 全部通过。 */
    fun run(assertions: Map<String, () -> List<String>>): List<String> =
        assertions.flatMap { (name, fn) -> fn().map { "$name: $it" } }
}
