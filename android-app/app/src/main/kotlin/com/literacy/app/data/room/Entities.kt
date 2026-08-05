package com.literacy.app.data.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/** characters 表（STORAGE-DESIGN canonical schema）。 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val char: String,
    val pinyin: String = "",
    val masteryRecognize: Int = 0,
    val masteryWrite: Int = 0,
    val masteryUnderstand: Int = 0,
    val masteryApply: Int = 0,
    val status: String = "new",
    val currentPromptLevel: Int = 3,
    // P1-17：streak 按维度独立（MASTERY-CRITERIA §2）
    val streakRecognizeSuccess: Int = 0,
    val streakRecognizeErrors: Int = 0,
    val streakWriteSuccess: Int = 0,
    val streakWriteErrors: Int = 0,
    val streakUnderstandSuccess: Int = 0,
    val streakUnderstandErrors: Int = 0,
    val streakApplySuccess: Int = 0,
    val streakApplyErrors: Int = 0,
    // review-09 P1-11：达标链计数（MasteryAdjudicator 升级判定用，随记录持久化——跨天/重启不丢）
    val gateStreakRecognize: Int = 0,
    val gateStreakWrite: Int = 0,
    val gateStreakUnderstand: Int = 0,
    val gateStreakApply: Int = 0,
    // 各维度上次间隔累计日期（随 gateStreak 持久化——lastReview 整字共享会
    // 跨维度误伤：同日先 RECOGNIZE 后 WRITE 复习被当重复不累计；按维度记录后各自独立）
    val gateStreakDateRecognize: String? = null,
    val gateStreakDateWrite: String? = null,
    val gateStreakDateUnderstand: String? = null,
    val gateStreakDateApply: String? = null,
    val commonMistakes: String = "",   // JSON 数组
    val source: String? = null,
    val easeFactor: Double = 2.5,
    val intervalDays: Int = 0,
    val lastReview: String? = null,
    val nextReview: String? = null,
)

/** sessions 表。 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String = "",
    val startedAt: String = "",
    val endedAt: String? = null,
    val status: String = "active",
    val charsLearned: Int = 0,
    val charsReviewed: Int = 0,
    val namePlanProgress: String? = null,
    val highlights: String? = null,
    val struggles: String? = null,
    val durationSeconds: Int = 0,
)

/** session_character_results 表（证据记录，幂等唯一约束——P1-1：IGNORE 依赖真实冲突）。
 *  review-09 P1-10：唯一索引为「idempotencyKey 全局唯一」——App 签发 key 全局去重
 *  （同 key 换 phase/session/char 不得重复计分；Room 与核心 Store 语义一致）。 */
@Entity(
    tableName = "session_character_results",
    indices = [androidx.room.Index(value = ["idempotencyKey"], unique = true)],
)
data class SessionResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: Int = 1,
    val char: String = "",
    val phase: String = "",
    val exerciseType: String? = null,
    val score: Double? = null,
    val issues: String = "",          // JSON 数组
    val promptLevel: String? = null,
    val idempotencyKey: String = "",
)

/** name_plan 表（单行 id=1）。 */
@Entity(tableName = "name_plan")
data class NamePlanEntity(
    @PrimaryKey val id: Int = 1,
    val fullName: String = "",
    val targetChars: String = "",     // JSON 数组
    val priorityMode: String = "soft",
    val currentStage: String? = null,
    val recognitionReady: Boolean = false,
    val guidedWritingReady: Boolean = false,
    val independentWritingReady: Boolean = false,
    val signingReady: Boolean = false,
)
