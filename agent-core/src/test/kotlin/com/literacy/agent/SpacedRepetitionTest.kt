package com.literacy.agent

import com.literacy.agent.learning.SpacedRepetition
import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.Dimension
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 间隔重复算法测试（MASTERY-CRITERIA §2/§6 + SESSION-LIFECYCLE §1.2）：
 * - GT-056 最弱非零维度决定排期
 * - GT-057 名字字间隔 ×0.7
 * - SM-2 简化版：等级 → 间隔（当天/1-3 天/7-14-30 递增/90 天）；失败重置
 * - 复习队列排序：出错 > 过期 > 层内最弱维度
 */
class SpacedRepetitionTest {

    private val sr = SpacedRepetition()

    @Test
    fun `GT-056 最弱非零维度为书写`() {
        val rec = CharacterRecord("家",
            masteryRecognize = 3, masteryWrite = 1, masteryUnderstand = 2, masteryApply = 1)
        assertEquals(Dimension.WRITE, sr.weakestDimension(rec))
    }

    @Test
    fun `最弱维度取最低非零等级（apply 与 write 同级时取维度顺序第一个）`() {
        val rec = CharacterRecord("家",
            masteryRecognize = 3, masteryWrite = 2, masteryUnderstand = 2, masteryApply = 1)
        assertEquals(Dimension.APPLY, sr.weakestDimension(rec))
    }

    @Test
    fun `GT-057 名字字间隔短 30%（同等级 4 维全 3）`() {
        val nameChar = CharacterRecord("张", source = "name_plan",
            masteryRecognize = 3, masteryWrite = 3, masteryUnderstand = 3, masteryApply = 3)
        val normalChar = CharacterRecord("家", source = "life_pack",
            masteryRecognize = 3, masteryWrite = 3, masteryUnderstand = 3, masteryApply = 3)

        // review-10 P2-15：intervalDays 存未折扣档位（否则 7×0.7→4 后卡档无法推进）；
        // 名字字折扣（×0.7）在 scheduleNextReview 的日期计算时应用
        val nameInterval = sr.nextSchedule(nameChar, ok = true).intervalDays
        val normalInterval = sr.nextSchedule(normalChar, ok = true).intervalDays

        assertEquals(7, normalInterval)                          // 稳定掌握基础档位 7 天
        assertEquals(7, nameInterval, "档位未折扣（避免卡档）")
        // 日期层面名字字更短：7×0.7→4 天（至少 1 天）
        val today = java.time.LocalDate.of(2026, 8, 3)
        val nameNext = sr.scheduleNextReview(nameChar.copy(intervalDays = 7), today).nextReview
        val normalNext = sr.scheduleNextReview(normalChar.copy(intervalDays = 7), today).nextReview
        assertEquals(today.plusDays(7).toString(), normalNext)
        assertEquals(today.plusDays(4).toString(), nameNext, "名字字复习日期 = 7×0.7 = 4 天")
        assertTrue(nameNext!! < normalNext!!, "名字字间隔应更短")
    }

    @Test
    fun `稳定掌握成功递增 7→14→30`() {
        val base = CharacterRecord("家",
            masteryRecognize = 3, masteryWrite = 3, masteryUnderstand = 3, masteryApply = 3)
        assertEquals(7, sr.nextSchedule(base, ok = true).intervalDays)

        val after7 = base.copy(intervalDays = 7, easeFactor = 2.6)
        assertEquals(14, sr.nextSchedule(after7, ok = true).intervalDays)

        val after14 = base.copy(intervalDays = 14, easeFactor = 2.7)
        assertEquals(30, sr.nextSchedule(after14, ok = true).intervalDays)   // review-09 P2-13：7→14→30 封顶（文档对齐，不再 28）
    }

    @Test
    fun `学习中成功当天复习（interval 0）`() {
        val rec = CharacterRecord("家", masteryRecognize = 1, masteryWrite = 1)
        assertEquals(0, sr.nextSchedule(rec, ok = true).intervalDays)
    }

    @Test
    fun `review-11 P2-1 interval 0 保持当天复习（不强制变明天）`() {
        val today = java.time.LocalDate.of(2026, 8, 3)
        // 学习中成功 → interval 0（当天复习档位）——scheduleNextReview 不得 coerceAtLeast(1) 变明天
        val rec = CharacterRecord("家", masteryRecognize = 1, masteryWrite = 1, intervalDays = 0)
        assertEquals(today.toString(), sr.scheduleNextReview(rec, today).nextReview)
        // 名字字 ×0.7 折扣只影响正数档位（1 天 → 0.7 → 至少 1 天）
        val nameRec = CharacterRecord("张", source = "name_plan", intervalDays = 1)
        assertEquals(today.plusDays(1).toString(), sr.scheduleNextReview(nameRec, today).nextReview)
    }

    @Test
    fun `初步掌握成功 1-3 天`() {
        val rec = CharacterRecord("家", masteryRecognize = 2, masteryWrite = 2)
        assertEquals(1, sr.nextSchedule(rec, ok = true).intervalDays)
    }

    @Test
    fun `熟练 90 天`() {
        val rec = CharacterRecord("家", masteryRecognize = 4, masteryWrite = 4,
            masteryUnderstand = 4, masteryApply = 4)
        assertEquals(90, sr.nextSchedule(rec, ok = true).intervalDays)
    }

    @Test
    fun `失败重置间隔并降低 ease_factor（SM-2 错题回炉）`() {
        val rec = CharacterRecord("家", easeFactor = 2.5, intervalDays = 14,
            masteryRecognize = 3, masteryWrite = 3, masteryUnderstand = 3, masteryApply = 3)
        val result = sr.nextSchedule(rec, ok = false)
        assertEquals(1, result.intervalDays)
        assertEquals(2.3, result.easeFactor)
    }

    @Test
    fun `复习队列排序：出错优先于过期，层内最弱维度优先（SESSION-LIFECYCLE §1 2）`() {
        val today = LocalDate.of(2026, 8, 1)
        val chars = listOf(
            CharacterRecord("电", nextReview = "2026-07-30", masteryRecognize = 1),   // 过期 + 最弱
            CharacterRecord("家", nextReview = "2026-07-29", streakWriteErrors = 1),   // 出错（最高优先级）
            CharacterRecord("的", nextReview = "2026-08-02"),                          // 即将到期（不进队列）
            CharacterRecord("国", nextReview = "2026-07-31", masteryRecognize = 3),   // 过期但掌握较好
        )
        assertEquals(listOf("家", "电", "国"), sr.buildReviewQueue(chars, today))
    }

    private fun assertTrue(condition: Boolean, message: String = "") {
        if (!condition) throw AssertionError(message)
    }
}
