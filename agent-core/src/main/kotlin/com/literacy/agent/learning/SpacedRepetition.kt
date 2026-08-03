package com.literacy.agent.learning

import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.Dimension
import java.time.LocalDate

/**
 * 间隔重复算法（SM-2 简化版，MASTERY-CRITERIA §2/§6 + SESSION-LIFECYCLE §1.2）。
 *
 * - 复习排期取 4 个维度中最弱等级（GT-056）
 * - 名字字间隔比普通字短 30%（source == name_plan，GT-057）
 * - 复习队列构建：出错优先 → 已过期 → 层内最弱维度（SESSION-LIFECYCLE §1.2）
 */
class SpacedRepetition {

    /** 最弱非零维度（GT-056）。全部为 0（未学）时返回 null。 */
    fun weakestDimension(record: CharacterRecord): Dimension? =
        Dimension.entries
            .map { it to record.mastery(it) }
            .filter { (_, level) -> level > 0 }
            .minByOrNull { (_, level) -> level }
            ?.first

    /** 名字字判定（GT-057）：source == name_plan → 间隔 × 0.7。 */
    fun nameCharFactor(record: CharacterRecord): Double =
        if (record.source == "name_plan") 0.7 else 1.0

    /**
     * 一次复习结果后的排期更新。
     *
     * 成功（按最弱维度等级，MASTERY-CRITERIA §2 间隔表）：
     * - 学习中 (1)：当天复习（interval 0）
     * - 初步掌握 (2)：1-3 天
     * - 稳定掌握 (3)：7-14-30 天递增
     * - 熟练 (4)：90 天
     * 失败：间隔重置为 1 天，ease_factor 降低（SM-2：错题回炉）。
     */
    fun nextSchedule(record: CharacterRecord, ok: Boolean): CharacterRecord {
        val dim = weakestDimension(record) ?: return record
        val weakest = record.mastery(dim)
        var ef = record.easeFactor
        var interval: Int
        if (ok) {
            ef = (ef + 0.1).coerceAtMost(3.0)
            interval = when {
                weakest <= 1 -> 0
                weakest == 2 -> maxOf(1, record.intervalDays)
                weakest == 3 -> if (record.intervalDays < 7) 7
                else if (record.intervalDays < 14) 14
                else 30   // review-09 P2-13：7 → 14 → 30 封顶（文档 MASTERY-CRITERIA §2，不再 28）
                else -> 90
            }
        } else {
            ef = (ef - 0.2).coerceAtLeast(1.3)
            interval = 1   // review-09 P2-13：失败回炉至少 1 天（不再 0——当天重复复习与文档不一致）
        }
        // review-10 P2-15：存未折扣档位（7/14/30）——折扣后的值再作输入会卡档
        // （7×0.7→4 后 intervalDays<7 永远回到 7）；折扣在 scheduleNextReview 的日期计算时应用
        return record.copy(easeFactor = ef, intervalDays = interval)
    }

    /** 入队层级（SESSION-LIFECYCLE §1.2 排序）：0=出错，1=已过期，2=不排队。
     *  P1-17：任一维度连续失败即入出错层（书写失败不被识读成功清零）。 */
    private fun tier(record: CharacterRecord, today: LocalDate): Int = when {
        record.commonMistakes.isNotEmpty() || record.anyErrorStreak() -> 0
        record.nextReview != null && !today.isBefore(LocalDate.parse(record.nextReview)) -> 1
        else -> 2
    }

    /** 层内最弱等级（越小越优先复习）。 */
    private fun weakestLevel(record: CharacterRecord): Int =
        Dimension.entries.map { record.mastery(it) }.filter { it > 0 }.minOrNull() ?: 0

    /**
     * 构建当天复习队列（SESSION-LIFECYCLE §1.2）：
     * 最近出错 > 已过期（next_review <= today）> 层内最弱维度等级低者优先。
     * 仅"即将到期"（+1 天内）的字不进队列（只用于 today_brief 提示）。
     */
    fun buildReviewQueue(characters: Collection<CharacterRecord>, today: LocalDate): List<String> =
        characters
            .filter { tier(it, today) <= 1 }
            .sortedWith(compareBy({ tier(it, today) }, { weakestLevel(it) }))
            .map { it.char }

    /** 将 next_review 推进到 today + intervalDays（复习完成后调用）。
     *  review-10 P2-15：名字字折扣（×0.7）在此应用，至少 1 天（失败/折扣不落到 0 天）。 */
    fun scheduleNextReview(record: CharacterRecord, today: LocalDate): CharacterRecord =
        record.copy(
            lastReview = today.toString(),
            nextReview = today.plusDays(
                (record.intervalDays * nameCharFactor(record)).toLong().coerceAtLeast(1),
            ).toString(),
        )
}
