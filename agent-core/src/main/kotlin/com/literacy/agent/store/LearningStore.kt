package com.literacy.agent.store

import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.NamePlan
import com.literacy.agent.model.Session
import com.literacy.agent.model.SessionResult

/**
 * 学习数据存储抽象（STORAGE-DESIGN schema）。
 *
 * - JVM 测试基线：InMemoryStore（内存实现）
 * - Android App：RoomStore（SQLite，Room）
 *
 * 幂等语义（§7.1）：同一 idempotency_key 的 recordResult 只插入一次。
 */
interface LearningStore {

    /** 全部 characters（只读视图）。 */
    val characters: Map<String, CharacterRecord>

    /** 证据记录列表。 */
    val results: List<SessionResult>

    /** 会话列表。 */
    val sessions: List<Session>

    /** name_plan（单行）。 */
    var namePlan: NamePlan?

    fun getCharacter(char: String): CharacterRecord

    fun upsertCharacter(record: CharacterRecord)

    /** 幂等落库；返回 true 表示新插入。 */
    fun recordResult(result: SessionResult): Boolean

    /**
     * §7.1 原子落库：证据插入成功后才更新 characters 聚合，同一事务（review-05 P1-4）。
     * 返回 true 表示证据新插入。
     */
    /** §7.1 原子：证据 + 聚合写入（review-09 P1-15：裁决在事务内基于最新记录重算，防并发 lost update）。
     *  @return 插入成功后的最终聚合记录；null = 幂等冲突（同 key 已存在）未插入。 */
    fun recordResultWithUpsert(
        result: SessionResult,
        character: CharacterRecord,
        recalc: (CharacterRecord) -> CharacterRecord,
    ): CharacterRecord?

    /**
     * 原子结束会话：status=completed + 总结字段 + endedAt 一次写入（P2：不再拆两次事务）。
     */
    fun completeSession(
        id: Int,
        endedAt: String,
        highlights: String?,
        struggles: String?,
        namePlanProgress: String?,
    )

    fun insertSession(session: Session): Session

    fun updateSession(id: Int, transform: (Session) -> Session): Boolean

    fun latestSession(): Session?

    /** 前置状态注入（用例 setup 用）。 */
    fun seedSessions(seed: List<Session>)
}
