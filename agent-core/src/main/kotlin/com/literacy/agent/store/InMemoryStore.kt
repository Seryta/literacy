package com.literacy.agent.store

import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.NamePlan
import com.literacy.agent.model.Session
import com.literacy.agent.model.SessionResult

/**
 * 内存版存储（替代 Room，供测试/回放使用）。
 * 幂等语义与协议一致（AGENT-PROTOCOL §7.1）：同一 idempotency_key 只插入一次。
 */
class InMemoryStore : LearningStore {

    override val characters: MutableMap<String, CharacterRecord> = mutableMapOf()
    override val results: MutableList<SessionResult> = mutableListOf()
    override val sessions: MutableList<Session> = mutableListOf()
    override var namePlan: NamePlan? = null
    private val seenKeys: MutableSet<String> = mutableSetOf()
    private var nextSessionId = 1

    /**
     * §7.1：幂等落库。返回 true 表示新插入；同 key 重复调用返回 false 且不重复计数。
     */
    override fun recordResult(result: SessionResult): Boolean {
        if (!seenKeys.add(result.idempotencyKey)) return false
        results.add(result)
        return true
    }

    override fun recordResultWithUpsert(
        result: SessionResult,
        character: CharacterRecord,
        recalc: (CharacterRecord) -> CharacterRecord,
    ): CharacterRecord? {
        if (!recordResult(result)) return null
        val latest = characters[result.char] ?: character
        val updated = recalc(latest)
        upsertCharacter(updated)
        return updated
    }

    override fun completeSession(
        id: Int, endedAt: String, highlights: String?, struggles: String?, namePlanProgress: String?,
    ) {
        updateSession(id) {
            it.copy(status = "completed", endedAt = endedAt, highlights = highlights, struggles = struggles, namePlanProgress = namePlanProgress)
        }
    }

    override fun getCharacter(char: String): CharacterRecord =
        characters.getOrPut(char) { CharacterRecord(char) }

    override fun upsertCharacter(record: CharacterRecord) {
        characters[record.char] = record
    }

    /** 插入新 session，自动分配递增 id。 */
    override fun insertSession(session: Session): Session {
        val saved = session.copy(id = nextSessionId++)
        sessions += saved
        return saved
    }

    /** 前置状态注入（用例 setup）：按给定 id 注入已存在的 session，调整自增 id 避免冲突。 */
    override fun seedSessions(seed: List<Session>) {
        sessions += seed
        nextSessionId = maxOf(nextSessionId, (seed.maxOfOrNull { it.id } ?: 0) + 1)
    }

    /** 按 id 更新 session（如 status → completed / aborted）。返回是否找到。 */
    override fun updateSession(id: Int, transform: (Session) -> Session): Boolean {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return false
        sessions[idx] = transform(sessions[idx])
        return true
    }

    /** 最近一次 session；无记录时返回 null。 */
    override fun latestSession(): Session? = sessions.maxByOrNull { it.id }
}
