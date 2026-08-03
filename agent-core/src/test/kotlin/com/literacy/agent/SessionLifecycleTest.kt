package com.literacy.agent

import com.literacy.agent.learning.SessionLifecycle
import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.NamePlan
import com.literacy.agent.store.InMemoryStore
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 启动刷新测试（SESSION-LIFECYCLE §1，GT-016/GT-001 复习队列前置）：
 * - §1.0 上次 active → aborted，新 session active，已落库数据不丢失
 * - §1.2 复习队列基于 next_review 生成
 * - §1.3 name_plan 进度派生
 * - §1.4 today_brief 派生（overdue ≥3 → 复习为主，GT-002 语义）
 */
class SessionLifecycleTest {

    @Test
    fun `GT-016 上次 active 标记 aborted，新 session active`() {
        val store = InMemoryStore()
        store.seedSessions(listOf(com.literacy.agent.model.Session(id = 5, date = "昨天", startedAt = "10:00", status = "active")))
        // 上次已落库的学习数据
        store.upsertCharacter(CharacterRecord("家", masteryWrite = 2))

        val session = SessionLifecycle(store).startSession("2026-07-31", "09:00")

        assertEquals("aborted", store.sessions.find { it.id == 5 }?.status)
        assertEquals("active", store.sessions.find { it.id == session.id }?.status)
        assertEquals("active", session.status)
        // 已落库数据不丢失
        assertEquals(2, store.getCharacter("家").masteryWrite)
    }

    @Test
    fun `上次已正常结束不标记 aborted`() {
        val store = InMemoryStore()
        store.seedSessions(listOf(com.literacy.agent.model.Session(id = 3, date = "昨天", startedAt = "10:00", status = "completed")))
        SessionLifecycle(store).startSession("2026-07-31", "09:00")
        assertEquals("completed", store.sessions.find { it.id == 3 }?.status)
    }

    @Test
    fun `§1 2 复习队列从已存 next_review 生成`() {
        val store = InMemoryStore()
        store.upsertCharacter(CharacterRecord("家", nextReview = "2026-07-29"))
        store.upsertCharacter(CharacterRecord("的", nextReview = "2026-08-02"))   // 即将到期，不进队列
        val queue = SessionLifecycle(store).buildReviewQueue(LocalDate.of(2026, 8, 1))
        assertEquals(listOf("家"), queue)
    }

    @Test
    fun `§1 3 name_plan 进度派生（已认读未写国）`() {
        val store = InMemoryStore()
        store.upsertCharacter(CharacterRecord("张", masteryRecognize = 2, masteryWrite = 2))
        store.upsertCharacter(CharacterRecord("建", masteryRecognize = 2, masteryWrite = 2))
        store.upsertCharacter(CharacterRecord("国"))
        val plan = NamePlan(fullName = "张建国", targetChars = listOf("张", "建", "国"))

        val status = SessionLifecycle(store).deriveNamePlanStatus(plan)

        assertTrue(status.achievedSummary.contains("张"), "应提及已掌握的字")
        assertTrue(status.nextMilestone.contains("国"), "下一里程碑应为国")
    }

    @Test
    fun `§1 4 today_brief overdue 少于 3 时复习与新课并重`() {
        val store = InMemoryStore()
        store.upsertCharacter(CharacterRecord("家", nextReview = "2026-07-29"))
        val brief = SessionLifecycle(store).buildTodayBrief(LocalDate.of(2026, 8, 1))
        assertTrue(brief.contains("待复习字：家"), "brief 应列出待复习字")
        assertTrue(brief.contains("今日建议重点"), "brief 应含建议重点")
    }

    @Test
    fun `§1 4 overdue ≥3 时 brief 建议以复习为主（GT-002 语义）`() {
        val store = InMemoryStore()
        store.upsertCharacter(CharacterRecord("家", nextReview = "2026-07-28"))
        store.upsertCharacter(CharacterRecord("的", nextReview = "2026-07-29"))
        store.upsertCharacter(CharacterRecord("电", nextReview = "2026-07-30"))
        val brief = SessionLifecycle(store).buildTodayBrief(LocalDate.of(2026, 8, 1))
        assertTrue(brief.contains("以复习为主"), "3 个 overdue 应以复习为主")
    }

    @Test
    fun `无历史 session 时正常创建新 session`() {
        val store = InMemoryStore()
        val session = SessionLifecycle(store).startSession("2026-07-31", "09:00")
        assertEquals(1, store.sessions.size)
        assertEquals("active", session.status)
        assertNull(store.latestSession()?.endedAt)
    }
}
