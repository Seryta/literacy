package com.literacy.agent.engine

import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.Dimension

/**
 * 掌握等级裁决（MASTERY-CRITERIA §2 升级/降级规则 + §4 单次赋值）。
 *
 * 本地规则引擎计算，Agent 不参与裁决（AGENT-PROTOCOL §6.4）。
 * 幂等由调用方保证：同一 idempotency_key 只调用一次。
 */
class MasteryAdjudicator {

    /**
     * §4 单次赋值：独立写 L0（无提示）完成 → 书写等级 2；L1-L2（有提示）完成 → 等级 1。
     * 其余维度尝试成功 → 等级 1（学习中）。
     */
    private fun singleAttemptLevel(dim: Dimension, promptLevel: Int): Int =
        if (dim == Dimension.WRITE && promptLevel <= 0) 2 else 1

    /**
     * §6.4 触发点：record_result 事务内（维度检测）或复习轮结果到达时。
     *
     * @param ok      本次尝试结果（对/错）
     * @param isReview 是否复习轮（复习轮才触发降级，§2 降级规则）
     * @param promptLevel 本次尝试使用的提示等级（L0-L6）
     */
    fun adjudicate(
        record: CharacterRecord,
        dim: Dimension,
        ok: Boolean,
        promptLevel: Int,
        isReview: Boolean = false,
    ): CharacterRecord {
        // P1-17：连续计数累计所有成功（该维度一个递增、另一个清零——不污染其他维度）。
        // review-10 P1-6：跨门槛借用由两点保证——① upgrade 判定要求「本次尝试达标」
        // （current<2 需 promptLevel<=2、current==2 需 L0、current==3 需 isReview，见 upgrade）；
        // ② P1-9 门槛升级后清零。累计阶段不过滤（L3 认对也是成功，供单次赋值后的后续升级累计）
        val withStreak = if (ok) {
            record.withStreak(dim, record.streakSuccess(dim) + 1, 0)
        } else {
            record.withStreak(dim, 0, record.streakErrors(dim) + 1)
        }

        val current = record.mastery(dim)
        val next = when {
            ok -> upgrade(withStreak, dim, current, promptLevel, isReview)
            isReview && promptLevel >= 3 -> maxOf(0, current - 1)   // 复习出错 + ≥L3 提示 → 降一级
            else -> current                                          // 非复习轮出错不降级
        }
        // P2：status 用更新后的 mastery 推导（此前用 withStreak 未更新值，状态落后一轮）
        val updated = withStreak.withMastery(dim, next)
        // review-09 P1-9：门槛升级（1→2/2→3/3→4，基于连续次数）后该维度 streak 清零——
        // 不同门槛（L1-L2/L0/间隔复习）各用自上次门槛升级起的连续计数，防"两次 L1 攒的
        // streak 被一次 L0 借用越级"。单次赋值升级（0→1 起步 / 0→2 书写 L0）不清零——
        // 它是起步不是门槛达标，清零会破坏"两次 L1 升初步掌握"的连续计数
        val singleLevel = singleAttemptLevel(dim, promptLevel)
        val final = if (next > current && next > singleLevel) updated.withStreak(dim, 0, 0) else updated
        return final.copy(status = final.deriveStatus())
    }

    private fun upgrade(
        r: CharacterRecord,
        dim: Dimension,
        current: Int,
        promptLevel: Int,
        isReview: Boolean,
    ): Int {
        // §4 单次赋值（首学达到的下限）
        var level = maxOf(current, singleAttemptLevel(dim, promptLevel))
        // §2 升级规则（连续次数达标——P1-17：只看目标维度自身的 streak）
        when {
            // 学习中 → 初步掌握：连续 2 次 L1-L2 提示成功
            current < 2 && r.streakSuccess(dim) >= 2 && promptLevel <= 2 -> level = 2
            // 初步掌握 → 稳定掌握：连续 2 次 L0 独立成功
            current == 2 && r.streakSuccess(dim) >= 2 && promptLevel <= 0 -> level = 3
            // 稳定掌握 → 熟练：间隔复习连续 3 次无障碍
            current == 3 && r.streakSuccess(dim) >= 3 && isReview -> level = 4
        }
        return level
    }
}
