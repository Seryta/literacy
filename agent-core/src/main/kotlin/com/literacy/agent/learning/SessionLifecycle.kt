package com.literacy.agent.learning

import com.literacy.agent.model.NamePlan
import com.literacy.agent.model.NamePlanStatus
import com.literacy.agent.model.Session
import com.literacy.agent.store.LearningStore
import java.time.LocalDate

/**
 * Session 启动刷新（SESSION-LIFECYCLE §1，全部本地确定性计算，不触发 LLM）。
 *
 * - §1.0 上次异常退出：检测上次 active → 标记 aborted，已落库数据不丢失（GT-016）
 * - §1.2 复习队列：基于已存 next_review 生成当天队列（GT-056/057）
 * - §1.3 name_plan 进度派生：achieved_summary / next_milestone 由状态派生，不单独存库
 * - §1.4 today_brief 派生
 */
class SessionLifecycle(private val store: LearningStore) {

    private val spacedRepetition = SpacedRepetition()

    /** §1.0：上次 active → aborted，写入新 active session。返回新 session（GT-016）。 */
    fun startSession(date: String, startedAt: String): Session {
        store.latestSession()?.let { last ->
            if (last.status == "active") store.updateSession(last.id) { it.copy(status = "aborted") }
        }
        val actualDate = if ('T' in startedAt) startedAt.take(10) else date
        return store.insertSession(Session(date = actualDate, startedAt = startedAt, status = "active"))
    }

    /** §1.2：当天复习队列（排序：出错 > 过期 > 层内最弱维度）。 */
    fun buildReviewQueue(today: LocalDate): List<String> =
        spacedRepetition.buildReviewQueue(store.characters.values, today)

    /** §1.3：name_plan 进度派生。recognize/write ≥2 视为"已能认出/写出"。 */
    fun deriveNamePlanStatus(plan: NamePlan): NamePlanStatus {
        val known = plan.targetChars.filter { c ->
            val r = store.getCharacter(c)
            r.masteryRecognize >= 2 && r.masteryWrite >= 2
        }
        val next = plan.targetChars.firstOrNull { it !in known }
        val achieved = when {
            known.isEmpty() -> "尚未掌握姓名目标字"
            known.size == plan.targetChars.size -> "已能认出并写出全部目标字"
            else -> "已能认出并写出" + known.joinToString("") + "，未学" + (next ?: "")
        }
        val milestone = when {
            plan.signingReady -> "姓名目标已完成，可按需巩固或转入生活字包"
            plan.independentWritingReady -> "接近完成，优先安排签字场景巩固"
            next != null -> "学会认读并书写「$next」"
            else -> "巩固已掌握的目标字"
        }
        return NamePlanStatus(achieved, milestone)
    }

    /**
     * §1.4：today_brief 派生。含日期 / 待复习字 / 姓名目标进度 / 今日建议重点。
     * 建议重点：overdue ≥3 以复习为主（GT-002），否则复习 + 新课并重。
     */
    fun buildTodayBrief(date: LocalDate, reviewQueue: List<String> = buildReviewQueue(date), namePlan: NamePlan? = store.namePlan): String {
        val sb = StringBuilder()
        sb.appendLine("今日日期：$date")
        if (reviewQueue.isNotEmpty()) sb.appendLine("待复习字：${reviewQueue.joinToString("、")}")
        namePlan?.let { sb.appendLine("姓名目标进度：${deriveNamePlanStatus(it).achievedSummary}") }
        sb.append(if (reviewQueue.size >= 3) "今日建议重点：以复习为主（${reviewQueue.size} 个待复习字）"
        else if (reviewQueue.isNotEmpty()) "今日建议重点：先复习「${reviewQueue.first()}」，再进入新课"
        else "今日建议重点：进入新课")
        return sb.toString()
    }
}
