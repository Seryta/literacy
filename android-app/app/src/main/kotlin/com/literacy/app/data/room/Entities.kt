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
 *  review-09 P1-16：唯一索引为「同 session+同字+同 phase+同 key」复合——同一尝试的
 *  retry 重发才冲突；不同 phase/session/char 的同 key 是独立证据（v1 模型自造 key
 *  时代，全局 key 唯一索引会误删不同 phase 的独立证据）。App 签名 UUID 下仍全局唯一。 */
@Entity(
    tableName = "session_character_results",
    indices = [androidx.room.Index(value = ["sessionId", "char", "phase", "idempotencyKey"], unique = true)],
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
