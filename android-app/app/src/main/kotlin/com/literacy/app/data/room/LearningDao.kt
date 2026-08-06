package com.literacy.app.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 学习数据 DAO（同步方法——调用方在 IO 线程，见 RoomStore 说明）。 */
@Dao
interface LearningDao {

    // ---- characters ----
    @Query("SELECT * FROM characters WHERE char = :charName")
    fun getCharacter(charName: String): CharacterEntity?

    @Query("SELECT * FROM characters")
    fun getAllCharacters(): List<CharacterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCharacter(entity: CharacterEntity)

    // ---- session_character_results（幂等：idempotency_key 唯一冲突时忽略）----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertResult(entity: SessionResultEntity): Long

    /** §7.1 原子：证据插入成功才更新 characters 聚合，同一事务（review-05 P1-4）。 */
    @androidx.room.Transaction
    fun recordResultWithUpsert(result: SessionResultEntity, character: CharacterEntity): Boolean {
        val inserted = insertResult(result)
        if (inserted != -1L) upsertCharacter(character)
        return inserted != -1L
    }

    @Query("SELECT * FROM session_character_results")
    fun getAllResults(): List<SessionResultEntity>

    // ---- sessions ----
    @Insert
    fun insertSession(entity: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun getSession(id: Int): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY id DESC LIMIT 1")
    fun latestSession(): SessionEntity?

    @Query("SELECT * FROM sessions")
    fun getAllSessions(): List<SessionEntity>

    @Query("UPDATE sessions SET status = :status, endedAt = :endedAt, highlights = :highlights, struggles = :struggles WHERE id = :id")
    fun updateSessionStatus(id: Int, status: String, endedAt: String?, highlights: String?, struggles: String?)

    /** 完整更新（review-05 P2-4：charsLearned 等字段不再截断）。 */
    @Query("""UPDATE sessions SET status=:status, endedAt=:endedAt, highlights=:highlights, struggles=:struggles,
        charsLearned=:charsLearned, charsReviewed=:charsReviewed, namePlanProgress=:namePlanProgress,
        durationSeconds=:durationSeconds WHERE id=:id""")
    fun updateSessionFull(
        id: Int, status: String, endedAt: String?, highlights: String?, struggles: String?,
        charsLearned: Int, charsReviewed: Int, namePlanProgress: String?, durationSeconds: Int,
    )

    /** P1-4：统计查询（本 session 已学字 / 复习字）。 */
    @Query("""SELECT COUNT(DISTINCT char) FROM session_character_results
        WHERE sessionId = :sid AND phase NOT IN ('assess','reinforce','skip')
        AND (score IS NULL OR score >= 0.6)""")
    fun countLearnedChars(sid: Int): Int

    @Query("SELECT COUNT(DISTINCT char) FROM session_character_results WHERE sessionId = :sid AND phase IN ('assess','reinforce')")
    fun countReviewedChars(sid: Int): Int

    @Query("SELECT startedAt FROM sessions WHERE id = :id")
    fun getSessionStartedAt(id: Int): String?

    /** P1-4：结束会话原子写入（completed + 总结 + endedAt + 统计——不再恒 0）。 */
    @androidx.room.Transaction
    fun completeSession(
        id: Int, endedAt: String, highlights: String?, struggles: String?, namePlanProgress: String?,
    ) {
        val learned = countLearnedChars(id)
        val reviewed = countReviewedChars(id)
        val started = getSessionStartedAt(id)
        val duration = started?.let { s ->
            try {
                if ('T' in s && 'T' in endedAt) {
                    val start = java.time.LocalDateTime.parse(s)
                    val end = java.time.LocalDateTime.parse(endedAt)
                    java.time.Duration.between(start, end).seconds.toInt().coerceAtLeast(0)
                } else {
                    val start = java.time.LocalTime.parse(s)
                    val end = java.time.LocalTime.parse(endedAt)
                    var secs = java.time.Duration.between(start, end).seconds
                    if (secs < 0) secs += 24 * 3600L
                    secs.toInt()
                }
            } catch (e: Exception) { 0 }
        } ?: 0
        updateSessionFull(id, "completed", endedAt, highlights, struggles, learned, reviewed, namePlanProgress, duration)
    }

    // ---- name_plan ----
    @Query("SELECT * FROM name_plan WHERE id = 1")
    fun getNamePlan(): NamePlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertNamePlan(entity: NamePlanEntity)
}
