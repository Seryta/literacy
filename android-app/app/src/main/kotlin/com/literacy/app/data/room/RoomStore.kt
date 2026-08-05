package com.literacy.app.data.room

import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.NamePlan
import com.literacy.agent.model.Session
import com.literacy.agent.model.SessionResult
import com.literacy.agent.store.LearningStore
import org.json.JSONArray

/**
 * Room 持久化存储（LearningStore 的 SQLite 实现）。
 *
 * 调用方（ReplayRunner / AgentOrchestrator）在 IO 线程执行——Room 同步
 * 方法非主线程调用合法（主线程查询才会抛异常）。
 * 幂等（§7.1）：recordResult 依赖 idempotency_key UNIQUE + IGNORE 冲突。
 */
class RoomStore(private val db: AppDatabase) : LearningStore {

    private val dao = db.learningDao()

    override val characters: Map<String, CharacterRecord>
        get() = dao.getAllCharacters().associate { it.char to it.toRecord() }

    override val results: List<SessionResult>
        get() = dao.getAllResults().map { it.toResult() }

    override val sessions: List<Session>
        get() = dao.getAllSessions().map { it.toSession() }

    override var namePlan: NamePlan?
        get() = dao.getNamePlan()?.toNamePlan()
        set(value) {
            if (value != null) dao.upsertNamePlan(value.toEntity())
        }

    override fun getCharacter(char: String): CharacterRecord =
        dao.getCharacter(char)?.toRecord() ?: CharacterRecord(char)

    override fun upsertCharacter(record: CharacterRecord) {
        // review-09 P2-12：REPLACE 整行会清空已有 pinyin（字库/建档注入）——从库读回补齐
        val existingPinyin = dao.getCharacter(record.char)?.pinyin ?: ""
        dao.upsertCharacter(record.toEntity(pinyin = existingPinyin))
    }

    override fun recordResult(result: SessionResult): Boolean =
        dao.insertResult(result.toEntity()) != -1L   // IGNORE 冲突返回 -1

    override fun recordResultWithUpsert(
        result: SessionResult,
        character: CharacterRecord,
        recalc: (CharacterRecord) -> CharacterRecord,
    ): CharacterRecord? {
        // review-09 P1-15：读-改-写全在事务内——两个不同 key 并发时后写者基于最新记录重算，
        // 不再覆盖前者的 streak/mastery（lost update 修复）
        var out: CharacterRecord? = null
        db.runInTransaction {
            val inserted = dao.insertResult(result.toEntity()) != -1L
            if (inserted) {
                val latest = dao.getCharacter(result.char)?.toRecord() ?: character
                out = recalc(latest)
                // review-10 P1-12：保留库中已有 pinyin（REPLACE 整行默认 pinyin="" 会清空字库拼音）
                val existingPinyin = dao.getCharacter(result.char)?.pinyin ?: ""
                dao.upsertCharacter(out.toEntity(pinyin = existingPinyin))
            }
        }
        return out
    }

    override fun insertSession(session: Session): Session {
        val id = dao.insertSession(session.toEntity()).toInt()
        return session.copy(id = id)
    }

    override fun updateSession(id: Int, transform: (Session) -> Session): Boolean {
        val existing = dao.getSession(id) ?: return false
        val updated = transform(existing.toSession())
        // 完整字段更新（review-05 P2-4）
        dao.updateSessionFull(
            id, updated.status, updated.endedAt,
            updated.highlights, updated.struggles,
            updated.charsLearned, updated.charsReviewed,
            updated.namePlanProgress, updated.durationSeconds,
        )
        return true
    }

    /** P2：结束会话原子事务（completed + 总结 + endedAt 一次写入）。 */
    override fun completeSession(
        id: Int, endedAt: String, highlights: String?, struggles: String?, namePlanProgress: String?,
    ) {
        dao.completeSession(id, endedAt, highlights, struggles, namePlanProgress)
    }

    override fun latestSession(): Session? = dao.latestSession()?.toSession()

    override fun seedSessions(seed: List<Session>) {
        seed.forEach { dao.insertSession(it.toEntity()) }
    }

    // ---- 模型转换 ----

    private fun CharacterRecord.toEntity(pinyin: String = "") = CharacterEntity(
        char = char, pinyin = pinyin, masteryRecognize = masteryRecognize,
        masteryWrite = masteryWrite, masteryUnderstand = masteryUnderstand,
        masteryApply = masteryApply, status = status,
        currentPromptLevel = currentPromptLevel,
        streakRecognizeSuccess = streakRecognizeSuccess, streakRecognizeErrors = streakRecognizeErrors,
        streakWriteSuccess = streakWriteSuccess, streakWriteErrors = streakWriteErrors,
        streakUnderstandSuccess = streakUnderstandSuccess, streakUnderstandErrors = streakUnderstandErrors,
        streakApplySuccess = streakApplySuccess, streakApplyErrors = streakApplyErrors,
        gateStreakRecognize = gateStreakRecognize, gateStreakWrite = gateStreakWrite,
        gateStreakUnderstand = gateStreakUnderstand, gateStreakApply = gateStreakApply,
        gateStreakDateRecognize = gateStreakDateRecognize, gateStreakDateWrite = gateStreakDateWrite,
        gateStreakDateUnderstand = gateStreakDateUnderstand, gateStreakDateApply = gateStreakDateApply,
        commonMistakes = JSONArray(commonMistakes).toString(),
        source = source, easeFactor = easeFactor, intervalDays = intervalDays,
        lastReview = lastReview, nextReview = nextReview,
    )

    private fun CharacterEntity.toRecord() = CharacterRecord(
        char = char, masteryRecognize = masteryRecognize, masteryWrite = masteryWrite,
        masteryUnderstand = masteryUnderstand, masteryApply = masteryApply,
        status = status, currentPromptLevel = currentPromptLevel,
        streakRecognizeSuccess = streakRecognizeSuccess, streakRecognizeErrors = streakRecognizeErrors,
        streakWriteSuccess = streakWriteSuccess, streakWriteErrors = streakWriteErrors,
        streakUnderstandSuccess = streakUnderstandSuccess, streakUnderstandErrors = streakUnderstandErrors,
        streakApplySuccess = streakApplySuccess, streakApplyErrors = streakApplyErrors,
        gateStreakRecognize = gateStreakRecognize, gateStreakWrite = gateStreakWrite,
        gateStreakUnderstand = gateStreakUnderstand, gateStreakApply = gateStreakApply,
        gateStreakDateRecognize = gateStreakDateRecognize, gateStreakDateWrite = gateStreakDateWrite,
        gateStreakDateUnderstand = gateStreakDateUnderstand, gateStreakDateApply = gateStreakDateApply,
        commonMistakes = if (commonMistakes.isBlank()) emptyList()
        else runCatching { JSONArray(commonMistakes).let { arr -> (0 until arr.length()).map { arr.getString(it) } } }
            .getOrDefault(emptyList()),
        source = source, easeFactor = easeFactor, intervalDays = intervalDays,
        lastReview = lastReview, nextReview = nextReview,
    )

    private fun SessionResult.toEntity() = SessionResultEntity(
        sessionId = sessionId, char = char, phase = phase, exerciseType = exerciseType,
        score = score, issues = JSONArray(issues).toString(),
        promptLevel = promptLevel, idempotencyKey = idempotencyKey,
    )

    private fun SessionResultEntity.toResult() = SessionResult(
        sessionId = sessionId, char = char, phase = phase, exerciseType = exerciseType,
        score = score, promptLevel = promptLevel,
        issues = if (issues.isBlank()) emptyList()
        else runCatching { JSONArray(issues).let { arr -> (0 until arr.length()).map { arr.getString(it) } } }
            .getOrDefault(emptyList()),
        idempotencyKey = idempotencyKey,
    )

    private fun Session.toEntity() = SessionEntity(
        id = id, date = date, startedAt = startedAt, endedAt = endedAt, status = status,
        charsLearned = charsLearned, charsReviewed = charsReviewed,
        namePlanProgress = namePlanProgress, highlights = highlights, struggles = struggles,
        durationSeconds = durationSeconds,
    )

    private fun SessionEntity.toSession() = Session(
        id = id, date = date, startedAt = startedAt, endedAt = endedAt, status = status,
        charsLearned = charsLearned, charsReviewed = charsReviewed,
        namePlanProgress = namePlanProgress, highlights = highlights, struggles = struggles,
        durationSeconds = durationSeconds,
    )

    private fun NamePlan.toEntity() = NamePlanEntity(
        id = 1, fullName = fullName, targetChars = JSONArray(targetChars).toString(),
        priorityMode = priorityMode, currentStage = currentStage,
        recognitionReady = recognitionReady, guidedWritingReady = guidedWritingReady,
        independentWritingReady = independentWritingReady, signingReady = signingReady,
    )

    private fun NamePlanEntity.toNamePlan() = NamePlan(
        fullName = fullName,
        targetChars = if (targetChars.isBlank()) emptyList()
        else runCatching { JSONArray(targetChars).let { arr -> (0 until arr.length()).map { arr.getString(it) } } }
            .getOrDefault(emptyList()),
        priorityMode = priorityMode, currentStage = currentStage,
        recognitionReady = recognitionReady, guidedWritingReady = guidedWritingReady,
        independentWritingReady = independentWritingReady, signingReady = signingReady,
    )
}
