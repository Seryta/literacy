package com.literacy.app.data.room

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v1→v2 真实 migration 测试（App 层零测试空白第一块）。
 *
 * 流程：建 v1 库（schema 由导出的 app/schemas/.../1.json 生成，不含唯一索引）
 * → 灌 v1 数据 → 用生产同款 MIGRATION_1_2 迁移 + 校验 → 断言数据保留，
 * 且 idempotencyKey 唯一索引是 migration 真实建出来的（P1-5：不清数据、幂等靠真实冲突）。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /** 对齐 LearningDaoTest.tearDown：失败残留的 migration-test.db 会让下次 createDatabase
     *  撞已存在库（且有 open 连接时 delete 失败）→ 假失败。@After 兜底清理（review 反馈 Warning 3）。 */
    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate1To2_preservesData_andCreatesIdempotencyUniqueIndex() {
        // 1) 建 v1 库 + 灌数据（v1 无 idempotencyKey 唯一索引）
        //    灌数据注释（review 反馈 Suggestion 6）：INSERT 命中四表全部 NOT NULL 列——若 1.json
        //    手工推导结构有误（列缺失/类型不符）INSERT 会抛错，间接验证 1.json 结构与实体一致
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO characters (char, pinyin, masteryRecognize, masteryWrite, " +
                    "masteryUnderstand, masteryApply, status, currentPromptLevel, streakSuccess, " +
                    "streakErrors, commonMistakes, easeFactor, intervalDays) " +
                    "VALUES ('家', 'jiā', 1, 0, 0, 0, 'learning', 3, 1, 0, '[]', 2.5, 0)",
            )
            execSQL(
                "INSERT INTO sessions (date, startedAt, endedAt, status, charsLearned, " +
                    "charsReviewed, durationSeconds) " +
                    "VALUES ('2026-08-02', '10:00:00', '10:05:00', 'active', 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO session_character_results (sessionId, char, phase, exerciseType, " +
                    "score, issues, promptLevel, idempotencyKey) " +
                    "VALUES (1, '家', 'recognize', NULL, 1.0, '[]', 'L3', 'key-1')",
            )
            close()
        }

        // 2) 真实 migration 1→2（唯一索引由 migration 创建；validateDroppedTables=true 校验 schema）
        helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2).close()

        // 3) 迁移后开新版本库：数据全部保留（当前 Room 版本 6，需注册 2→3/3→4/4→5/5→6）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()
        val dao = db.learningDao()
        assertEquals(1, dao.getAllResults().size)
        assertEquals("key-1", dao.getAllResults().single().idempotencyKey)
        assertNotNull(dao.getCharacter("家"))
        assertEquals(1, dao.getCharacter("家")?.masteryRecognize)
        assertEquals(1, dao.getSession(1)?.id)

        // 4) 全局唯一索引真实生效（review-09 P1-10）：同 key 二次插入（含换 phase）都被 IGNORE（-1L）——
        //    App 签发 key 全局去重，换 phase 不得重复计分
        assertEquals("同尝试 retry 重发应 IGNORE", -1L, dao.insertResult(
            SessionResultEntity(sessionId = 1, char = "家", phase = "recognize", score = 0.8, idempotencyKey = "key-1"),
        ))
        assertEquals(1, dao.getAllResults().size)
        assertEquals("同 key 换 phase 应 IGNORE（全局去重，P1-10）", -1L,
            dao.insertResult(
                SessionResultEntity(sessionId = 1, char = "家", phase = "assess", score = 0.8, idempotencyKey = "key-1"),
            ),
        )
        assertEquals(1, dao.getAllResults().size)
        db.close()
    }

    /**
     * 脏数据迁移（review 反馈 Warning 2 + Suggestion 1）：
     * P0-1 修复前模型自造 idempotencyKey 时代，v1 库可能已存在同 key 多行（retry 无约束拦截成独立行）
     * ——直接建唯一索引会抛异常。决策：迁移前清洗（每 key 保留最早一行）→ 建索引。
     * 本测试锁定该行为。
     */
    @Test
    fun migrate1To2_legacyDuplicateKeys_deduplicated_beforeIndex() {
        // 1) v1 库插入 3 条同 key（v1 无唯一约束，都成功）——review-10 P0：v2 是全局索引历史
        //    schema（不可变），v1 脏数据只能按 key 全局去重（v2 语义）；复合索引是 v4 演进
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO session_character_results (sessionId, char, phase, exerciseType, score, " +
                    "issues, promptLevel, idempotencyKey) " +
                    "VALUES (1, '家', 'recognize', NULL, 1.0, '[]', 'L3', 'dup-1')",
            )
            execSQL(
                "INSERT INTO session_character_results (sessionId, char, phase, exerciseType, score, " +
                    "issues, promptLevel, idempotencyKey) " +
                    "VALUES (1, '家', 'recognize', NULL, 0.8, '[]', 'L3', 'dup-1')",
            )
            execSQL(
                "INSERT INTO session_character_results (sessionId, char, phase, exerciseType, score, " +
                    "issues, promptLevel, idempotencyKey) " +
                    "VALUES (1, '家', 'assess', NULL, 0.5, '[]', 'L3', 'dup-1')",
            )
            query("SELECT COUNT(*) FROM session_character_results").use { c ->
                c.moveToFirst()
                assertTrue("v1 三条同 key 应都插入成功（无唯一约束）", c.getInt(0) == 3)
            }
            close()
        }

        // 2) 迁移：按 key 全局去重（v2 全局索引语义，历史不可变）
        helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2).close()

        // 3) 去重结果：同 key 只留最早一行（全局索引约束）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()
        val rows = db.learningDao().getAllResults()
        assertEquals(1L, rows.size.toLong())
        assertEquals("dup-1", rows.single().idempotencyKey)
        assertEquals("最早一行保留", "recognize", rows.single().phase)

        // 4) 最终库 v5（全局唯一索引，review-09 P1-10）：同 key 任意 phase 重发都被 IGNORE
        assertEquals(-1L, db.learningDao().insertResult(
            SessionResultEntity(sessionId = 1, char = "家", phase = "recognize", score = 0.5, idempotencyKey = "dup-1"),
        ))
        assertEquals("同 key 换 phase 也 IGNORE（全局去重）", -1L,
            db.learningDao().insertResult(
                SessionResultEntity(sessionId = 1, char = "家", phase = "assess", score = 0.5, idempotencyKey = "dup-1"),
            ),
        )
        assertEquals(1, db.learningDao().getAllResults().size)
        db.close()
    }

    /**
     * v2→v3（P1-17）：streak 单对 → per-dimension 8 列。
     * 迁移语义（MASTERY-CRITERIA §2）：旧全局 streak 按「掌握等级严格最高的维度」承接；
     * 无掌握锚点/平局 → 重新初始化为 0（维度归属不可知，不硬猜）。旧列随建新表流程移除。
     */
    @Test
    fun migrate2To3_preservesData_andMapsLegacyStreakToStrongestDimension() {
        // 1) 建 v2 库 + 灌三字（覆盖：严格最高维度 / 无掌握 / 平局）
        helper.createDatabase(TEST_DB, 2).apply {
            // 家：掌握最高 = write（严格）→ 旧 streak 迁移到 write 维度
            execSQL(
                "INSERT INTO characters (char, pinyin, masteryRecognize, masteryWrite, " +
                    "masteryUnderstand, masteryApply, status, currentPromptLevel, streakSuccess, " +
                    "streakErrors, commonMistakes, easeFactor, intervalDays) " +
                    "VALUES ('家', 'jiā', 1, 2, 0, 0, 'reviewing', 3, 3, 1, '[]', 2.5, 0)",
            )
            // 人：全维度 0（无掌握锚点）→ streak 重新初始化为 0
            execSQL(
                "INSERT INTO characters (char, pinyin, masteryRecognize, masteryWrite, " +
                    "masteryUnderstand, masteryApply, status, currentPromptLevel, streakSuccess, " +
                    "streakErrors, commonMistakes, easeFactor, intervalDays) " +
                    "VALUES ('人', 'rén', 0, 0, 0, 0, 'new', 3, 2, 0, '[]', 2.5, 0)",
            )
            // 的：recognize 与 write 平局（2=2）→ 不硬猜，重置 0
            execSQL(
                "INSERT INTO characters (char, pinyin, masteryRecognize, masteryWrite, " +
                    "masteryUnderstand, masteryApply, status, currentPromptLevel, streakSuccess, " +
                    "streakErrors, commonMistakes, easeFactor, intervalDays) " +
                    "VALUES ('的', 'de', 2, 2, 1, 0, 'mastered', 3, 5, 0, '[]', 2.5, 0)",
            )
            close()
        }

        // 2) 真实 migration 2→3（validateDroppedTables=true：建新表流程后 schema 与实体精确一致）
        // review-10 P0：Room 当前版本 6——校验目标 6，迁移链 2→3→4→5→6（3_4 索引替换、4_5 全局索引+新列、5_6 间隔日期列）
        helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).close()

        // 3) 迁移后：数据保留 + 旧 streak 迁移语义
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()
        val dao = db.learningDao()
        assertEquals(3, dao.getAllCharacters().size)   // 三字全部保留
        val jia = dao.getCharacter("家")!!
        assertEquals("jiā", jia.pinyin)
        assertEquals("reviewing", jia.status)
        assertEquals(2, jia.masteryWrite)
        // 严格最高 = write → streakWrite 承接，其余维度 0
        assertEquals(3, jia.streakWriteSuccess)
        assertEquals(1, jia.streakWriteErrors)
        assertEquals(0, jia.streakRecognizeSuccess)
        assertEquals(0, jia.streakRecognizeErrors)
        assertEquals(0, jia.streakApplySuccess)
        // 无掌握锚点 → 重新初始化
        assertEquals(0, dao.getCharacter("人")!!.streakWriteSuccess)
        assertEquals(0, dao.getCharacter("人")!!.streakRecognizeErrors)
        // 平局 → 不硬猜，重置 0
        assertEquals(0, dao.getCharacter("的")!!.streakRecognizeSuccess)
        assertEquals(0, dao.getCharacter("的")!!.streakWriteSuccess)
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
    /**
     * review-10 P0：v3 历史 schema 不可变（全局唯一索引 idempotencyKey，hash ee8a5e），
     * 复合索引是 v4 演进——3→4 迁移做索引替换，旧 v3 库升级不崩溃。
     */
    @Test
    fun migrate3To4_swapsUniqueIndex_preservesData() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO session_character_results (sessionId, char, phase, exerciseType, " +
                    "score, issues, promptLevel, idempotencyKey) " +
                    "VALUES (1, '家', 'recognize', NULL, 1.0, '[]', 'L3', 'v3-key-1')",
            )
            execSQL(
                "INSERT INTO session_character_results (sessionId, char, phase, exerciseType, " +
                    "score, issues, promptLevel, idempotencyKey) " +
                    "VALUES (1, '的', 'recognize', NULL, 0.5, '[]', 'L3', 'v3-key-2')",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()
        val rows = db.learningDao().getAllResults()
        assertEquals("v3 数据迁移保留", 2L, rows.size.toLong())
        // 全局唯一索引生效（review-09 P1-10）：同 key 任意 phase 重发都 IGNORE
        assertEquals("同尝试 retry 重发应 IGNORE", -1L,
            db.learningDao().insertResult(
                SessionResultEntity(sessionId = 1, char = "家", phase = "recognize", score = 0.8, idempotencyKey = "v3-key-1"),
            ),
        )
        assertEquals("同 key 换 phase 也 IGNORE（全局去重）", -1L,
            db.learningDao().insertResult(
                SessionResultEntity(sessionId = 1, char = "家", phase = "assess", score = 0.8, idempotencyKey = "v3-key-1"),
            ),
        )
        assertEquals(2, db.learningDao().getAllResults().size)
        db.close()
    }

    /**
     * v4→v5（review-09 P1-10 + P1-11）：复合索引（sessionId+char+phase+key）→ 全局唯一索引
     * （App 签发 key 全局去重，换 phase 不得重复计分）+ characters 新增 gateStreak 四列（达标链持久化）。
     */
    @Test
    fun migrate4To5_globalKeyDedup_andGateStreakColumns() {
        // 1) 建 v4 库 + 灌数据（v4 复合索引允许同 key 换 phase 双计）
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO session_character_results (sessionId, char, phase, exerciseType, score, " +
                    "issues, promptLevel, idempotencyKey) " +
                    "VALUES (1, '家', 'recognize', NULL, 1.0, '[]', 'L3', 'v4-key-1')",
            )
            execSQL(
                "INSERT INTO session_character_results (sessionId, char, phase, exerciseType, score, " +
                    "issues, promptLevel, idempotencyKey) " +
                    "VALUES (1, '家', 'assess', NULL, 0.8, '[]', 'L3', 'v4-key-1')",
            )
            execSQL(
                "INSERT INTO characters (char, pinyin, masteryRecognize, masteryWrite, masteryUnderstand, " +
                    "masteryApply, status, currentPromptLevel, streakRecognizeSuccess, streakRecognizeErrors, " +
                    "streakWriteSuccess, streakWriteErrors, streakUnderstandSuccess, streakUnderstandErrors, " +
                    "streakApplySuccess, streakApplyErrors, commonMistakes, easeFactor, intervalDays) " +
                    "VALUES ('家', 'jiā', 1, 0, 0, 0, 'learning', 3, 1, 0, 0, 0, 0, 0, 0, 0, '[]', 2.5, 0)",
            )
            close()
        }

        // 2) 真实 migration 4→5
        helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()
        val dao = db.learningDao()
        // 数据保留；同 key 去重（v4 时代同 key 换 phase 的双计行保留最早一条）
        val rows = dao.getAllResults()
        assertEquals(1L, rows.size.toLong())
        assertEquals("recognize", rows.single().phase)
        // 全局唯一索引生效：同 key 任意 phase 重发 IGNORE
        assertEquals(-1L, dao.insertResult(
            SessionResultEntity(sessionId = 1, char = "家", phase = "assess", score = 0.8, idempotencyKey = "v4-key-1"),
        ))
        // gateStreak 四列存在且默认 0（迁移 ADD COLUMN）
        val rec = dao.getCharacter("家")!!
        assertEquals(0, rec.gateStreakRecognize)
        assertEquals(0, rec.gateStreakWrite)
        assertEquals(0, rec.gateStreakUnderstand)
        assertEquals(0, rec.gateStreakApply)
        // gateStreak 写入后可读回（持久化生效）
        dao.upsertCharacter(rec.copy(gateStreakRecognize = 2, gateStreakWrite = 1))
        val saved = dao.getCharacter("家")!!
        assertEquals(2, saved.gateStreakRecognize)
        assertEquals(1, saved.gateStreakWrite)
        db.close()
    }

    /**
     * v5→v6：characters 增加 gateStreakDate 四列（各维度上次间隔累计日期）。
     * lastReview 整字共享会跨维度误伤（同日先 RECOGNIZE 后 WRITE 复习被当重复不累计）——
     * 按维度记录后 L3→L4 间隔日判定只认本维度上次达标日。旧行 NULL（无间隔基准）正常起算。
     */
    @Test
    fun migrate5To6_addsGateStreakDateColumns_preservesData() {
        // 1) 建 v5 库 + 灌数据（v5 无 gateStreakDate 列）
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                "INSERT INTO characters (char, pinyin, masteryRecognize, masteryWrite, masteryUnderstand, " +
                    "masteryApply, status, currentPromptLevel, streakRecognizeSuccess, streakRecognizeErrors, " +
                    "streakWriteSuccess, streakWriteErrors, streakUnderstandSuccess, streakUnderstandErrors, " +
                    "streakApplySuccess, streakApplyErrors, gateStreakRecognize, gateStreakWrite, " +
                    "gateStreakUnderstand, gateStreakApply, commonMistakes, easeFactor, intervalDays) " +
                    "VALUES ('家', 'jiā', 3, 3, 1, 0, 'mastered', 3, 2, 0, 1, 0, 0, 0, 0, 0, 2, 1, 0, 0, '[]', 2.5, 7)",
            )
            close()
        }

        // 2) 真实 migration 5→6（目标 6：Room 当前版本）
        helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6).close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .build()
        val dao = db.learningDao()
        // 数据保留；新列默认 NULL（旧行无间隔基准，首次/跨日复习正常累计）
        val rec = dao.getCharacter("家")!!
        assertEquals(3, rec.masteryRecognize)
        assertEquals("既有达标链保留", 2, rec.gateStreakRecognize)
        assertEquals(1, rec.gateStreakWrite)
        assertNull(rec.gateStreakDateRecognize)
        assertNull(rec.gateStreakDateWrite)
        // 间隔日期写入后可读回（持久化生效）
        dao.upsertCharacter(rec.copy(gateStreakDateRecognize = "2026-08-05", gateStreakDateWrite = "2026-08-05"))
        val saved = dao.getCharacter("家")!!
        assertEquals("2026-08-05", saved.gateStreakDateRecognize)
        assertEquals("2026-08-05", saved.gateStreakDateWrite)
        db.close()
    }
}
