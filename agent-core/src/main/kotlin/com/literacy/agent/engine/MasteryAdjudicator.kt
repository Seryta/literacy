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
     * review-11 P1-6 + review-09 P1-11：达标链计数（按 字+维度，随 CharacterRecord 持久化）。
     *
     * 语义：streak 字段仍累计所有成功（GT-021：L3 认对也计入 streak_success），但升级判定
     * 只统计「自上次门槛升级以来、且本次尝试达标（符合当前升级门槛）的连续成功」——
     * 防止跨门槛借用：mastery=2 时两次 L1 成功 + 一次 L0 成功，L1 不达标（门槛要求 L0），
     * 达标链只有 1，不能升 3。
     *
     * 达标定义（与 upgrade 的门槛一致）：
     * - current<2：promptLevel<=2（L1-L2 提示成功）
     * - current==2：promptLevel<=0（L0 无提示独立成功）
     * - current==3：isReview（间隔复习）
     * 非达标成功/失败都打断达标链。门槛升级后清零（自上次门槛升级以来语义）。
     *
     * review-09 P1-11：计数存在 CharacterRecord.gateStreak* 字段（随 store 落库）——
     * 跨天/重启不清零（L3→L4 需三次间隔复习，实例内 map 重启即丢）。
     */

    /** 本次尝试是否达标当前升级门槛（P1-6）。 */
    private fun qualifies(current: Int, promptLevel: Int, isReview: Boolean): Boolean = when {
        current < 2 -> promptLevel <= 2
        current == 2 -> promptLevel <= 0
        current == 3 -> isReview
        else -> false
    }

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
        today: String = "",
    ): CharacterRecord {
        val current = record.mastery(dim)
        // P1-17：连续计数累计所有成功（该维度一个递增、另一个清零——不污染其他维度）。
        // review-11 P1-6 + review-09 P1-11：达标链独立累计（存 record.gateStreak*，持久化）——
        // 达标成功 +1、非达标成功/失败归 0（跨门槛借用修复）；全部成功仍计入 streak 字段
        val withStreak = if (ok) {
            record.withStreak(dim, record.streakSuccess(dim) + 1, 0)
        } else {
            record.withStreak(dim, 0, record.streakErrors(dim) + 1)
        }
        // 残余修复（复核）：gateStreak 语义区分——
        // - 学习门槛（current<3）：连续达标成功累计（同 session 连续算，L1-L2/L2-L3 升级语义不变）
        // - L3→L4（current==3，须 isReview）：按间隔日累计——同日多次复习保留链不清零不累计
        //   （防同轮多 key 凑三次间隔）；跨日复习才 +1
        val newGate = if (ok && qualifies(current, promptLevel, isReview)) {
            if (current == 3 && record.lastReview == today) record.gateStreak(dim)   // 同日 L3 复习：保留链
            else record.gateStreak(dim) + 1
        } else 0

        val next = when {
            ok -> upgrade(withStreak, newGate, dim, current, promptLevel, isReview)
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
        val final = if (next > current && next > singleLevel) {
            updated.withGateStreak(dim, 0).withStreak(dim, 0, 0)   // 门槛升级后达标链清零
        } else {
            updated.withGateStreak(dim, newGate)   // 达标链随记录保存（持久化）
        }
        return final.copy(status = final.deriveStatus())
    }

    private fun upgrade(
        r: CharacterRecord,
        gate: Int,
        dim: Dimension,
        current: Int,
        promptLevel: Int,
        isReview: Boolean,
    ): Int {
        // §4 单次赋值（首学达到的下限）
        var level = maxOf(current, singleAttemptLevel(dim, promptLevel))
        // §2 升级规则（连续达标次数——P1-6：达标链计数，非全量成功 streak）
        when {
            // 学习中 → 初步掌握：连续 2 次 L1-L2 提示成功
            current < 2 && gate >= 2 && promptLevel <= 2 -> level = 2
            // 初步掌握 → 稳定掌握：连续 2 次 L0 独立成功
            current == 2 && gate >= 2 && promptLevel <= 0 -> level = 3
            // 稳定掌握 → 熟练：间隔复习连续 3 次无障碍
            current == 3 && gate >= 3 && isReview -> level = 4
        }
        return level
    }
}
