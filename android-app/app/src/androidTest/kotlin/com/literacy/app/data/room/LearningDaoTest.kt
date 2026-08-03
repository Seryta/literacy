package com.literacy.app.data.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LearningDao 行为测试（内存库）——锁定 review-08 修复口径：
 * - recordResultWithUpsert 幂等（同 idempotencyKey 二次插入不重复、聚合不二次更新）
 * - completeSession 统计（P2-A：复习字不计 charsLearned、计入 charsReviewed）
 * - completeSession 跨天 duration 补一天（P2-B）
 * - insertSession / upsertCharacter 基础
 */
@RunWith(AndroidJUnit4::class)
class LearningDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LearningDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.learningDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newSession(startedAt: String = "10:00:00"): Int =
        dao.insertSession(
            SessionEntity(date = "2026-08-02", startedAt = startedAt, status = "active"),
        ).toInt()

    // ---- recordResultWithUpsert 幂等（review-08 §7.1 真实冲突）----

    @Test
    fun recordResultWithUpsert_sameKeyTwice_noDuplicate_noDoubleUpsert() {
        val sid = newSession()
        val result = SessionResultEntity(
            sessionId = sid, char = "家", phase = "recognize", score = 1.0, idempotencyKey = "k1",
        )
        val character = CharacterEntity(char = "家")

        assertTrue(dao.recordResultWithUpsert(result, character))
        // 同 key 二次：证据被 IGNORE（-1L），聚合不二次更新
        assertFalse(dao.recordResultWithUpsert(result.copy(score = 0.5), character.copy(masteryRecognize = 5)))
        assertEquals(1, dao.getAllResults().size)
        assertEquals(0, dao.getCharacter("家")?.masteryRecognize)   // 第二次 upsert 未生效

        // 不同 key → 正常插入并更新聚合
        assertTrue(dao.recordResultWithUpsert(result.copy(idempotencyKey = "k2"), character.copy(masteryRecognize = 2)))
        assertEquals(2, dao.getAllResults().size)
        assertEquals(2, dao.getCharacter("家")?.masteryRecognize)
    }

    // ---- completeSession 统计口径（review-08 P2-A）----

    @Test
    fun completeSession_learnedExcludesReview_charsReviewedCountsDistinct() {
        val sid = newSession()
        // 新学：家、人（recognize）；复习：的（assess + reinforce 同字两次）
        dao.insertResult(SessionResultEntity(sessionId = sid, char = "家", phase = "recognize", idempotencyKey = "a"))
        dao.insertResult(SessionResultEntity(sessionId = sid, char = "的", phase = "assess", idempotencyKey = "b"))
        dao.insertResult(SessionResultEntity(sessionId = sid, char = "的", phase = "reinforce", idempotencyKey = "c"))
        dao.insertResult(SessionResultEntity(sessionId = sid, char = "人", phase = "recognize", idempotencyKey = "d"))

        dao.completeSession(sid, "10:05:00", "高亮", "struggles", "namePlan")

        val s = dao.getSession(sid)
        assertNotNull(s)
        assertEquals(2, s?.charsLearned)     // 家、人（复习字「的」不计入）
        assertEquals(1, s?.charsReviewed)    // 的（assess/reinforce 去重）
        assertEquals(300, s?.durationSeconds)
        assertEquals("completed", s?.status)
        assertEquals("高亮", s?.highlights)
        // review 反馈 Suggestion 5：总结字段全部落库（不只 highlights）
        assertEquals("struggles", s?.struggles)
        assertEquals("namePlan", s?.namePlanProgress)
    }

    // ---- completeSession 跨天 duration（review-08 P2-B）----

    @Test
    fun completeSession_crossDay_durationAddsOneDay() {
        val sid = newSession(startedAt = "23:00:00")
        dao.completeSession(sid, "00:30:00", null, null, null)
        assertEquals(5400, dao.getSession(sid)?.durationSeconds)   // 90 分钟，不再被 coerce 成 0
    }

    // ---- 基础：insertSession / upsertCharacter ----

    @Test
    fun insertSession_assignsId_andPersists() {
        val id = dao.insertSession(
            SessionEntity(date = "2026-08-02", startedAt = "09:00:00", status = "active"),
        )
        assertTrue(id > 0)
        val s = dao.getSession(id.toInt())
        assertEquals("active", s?.status)
        assertEquals("09:00:00", s?.startedAt)
        assertEquals(1, dao.getAllSessions().size)
        assertEquals(id.toInt(), dao.latestSession()?.id)
    }

    @Test
    fun upsertCharacter_replacesRow_inPlace() {
        dao.upsertCharacter(CharacterEntity(char = "家", masteryRecognize = 1))
        dao.upsertCharacter(CharacterEntity(char = "家", masteryRecognize = 2, pinyin = "jiā"))
        assertEquals(1, dao.getAllCharacters().size)             // REPLACE 同主键，不新增行
        assertEquals(2, dao.getCharacter("家")?.masteryRecognize)
        assertEquals("jiā", dao.getCharacter("家")?.pinyin)
    }

    // ---- P1-17：per-dimension streak 新列读写 ----

    @Test
    fun upsertCharacter_persistsPerDimensionStreak() {
        dao.upsertCharacter(
            CharacterEntity(
                char = "家",
                streakRecognizeSuccess = 2, streakRecognizeErrors = 0,
                streakWriteSuccess = 0, streakWriteErrors = 1,
                streakUnderstandSuccess = 0, streakUnderstandErrors = 0,
                streakApplySuccess = 3, streakApplyErrors = 0,
            ),
        )
        val c = dao.getCharacter("家")!!
        assertEquals(2, c.streakRecognizeSuccess)
        assertEquals(0, c.streakRecognizeErrors)
        assertEquals(0, c.streakWriteSuccess)
        assertEquals(1, c.streakWriteErrors)
        assertEquals(3, c.streakApplySuccess)
        assertEquals(0, c.streakApplyErrors)
    }
}
