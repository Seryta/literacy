package com.literacy.app.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** App 学习数据库（characters/sessions/session_character_results/name_plan）。 */
@Database(
    entities = [CharacterEntity::class, SessionEntity::class, SessionResultEntity::class, NamePlanEntity::class],
    version = 6,
    exportSchema = true,   // androidTest 迁移测试需要 schema JSON（app/schemas）
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun learningDao(): LearningDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v1→v2：idempotencyKey 唯一索引（幂等依赖真实冲突，P1-5 真实 migration 不清数据）。
         *  建索引前先清洗：P0-1 修复前模型自造 key 时代可能已存在重复 idempotencyKey（retry 无约束
         *  拦截成独立行），直接 CREATE UNIQUE INDEX 会抛 SQLiteConstraintException 崩迁移。
         *  决策（review 反馈 Warning 2）：迁移前去重，每 key 保留最早（id 最小）一行——幂等语义下
         *  重复行本就是同事件 retry 漏网，去重不丢独立证据、不破坏 P1-5「不清数据」。
         *  internal：androidTest 的 MigrationTestHelper 用真实 migration 对象跑迁移。 */
        internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // review-10 P0：v1→v2 是历史迁移，v2 schema 不可变（全局唯一索引 idempotencyKey）——
                // 恢复原始全局去重 + 全局索引。复合索引（保留不同 phase 独立证据）是 v4 演进，
                // 由 MIGRATION_3_4 在升级时替换（v2 全局索引语义下 v1 脏数据只能按 key 去重）
                db.execSQL(
                    "DELETE FROM session_character_results WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM session_character_results GROUP BY idempotencyKey)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_session_character_results_idempotencyKey " +
                    "ON session_character_results (idempotencyKey)")
            }
        }

        /** v2→v3（P1-17）：streak 单对（streakSuccess/streakErrors）演进为 per-dimension 8 列。
         *  旧列 SQLite 无便捷 DROP COLUMN（且 Room 迁移校验要求最终 schema 与实体精确一致），
         *  用标准建新表-拷贝-重建流程。旧值迁移语义（MASTERY-CRITERIA §2）：旧单对 streak 的
         *  维度归属不可知，按「掌握等级严格最高的维度」承接；平局/无掌握 → 8 列全 0（不硬猜维度）。
         *  注意（双轨差异）：此处平局/无锚点清零，与 fixture 侧 legacy 推断
         *  （agent-core CaseLoader.streakDimFor：平局/无锚点 → WRITE）方向不同——
         *  迁移没有后续写流上下文，清零最保守；fixture 侧有 GT-028 独立写用例锚点，
         *  WRITE 承接独立写流语义。三条规则（本 SQL / CaseLoader / Assertions）各按锚点语境，勿混读。
         *  sessions 等表不受影响。 */
        /** v3→v4（review-10 P0）：v3 历史 schema 已发布（全局唯一索引 idempotencyKey，hash ee8a5e）。
         *  复合唯一索引（sessionId+char+phase+key）是 v3 之后的演进（review-09 P1-16）——
         *  必须升 v4 做索引迁移，不能原地改 v3（旧库升级 Room 完整性校验崩溃）。 */
        internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_session_character_results_idempotencyKey`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_session_character_results_sessionId_char_phase_idempotencyKey` " +
                    "ON session_character_results (sessionId, char, phase, idempotencyKey)")
            }
        }

        /** v4→v5（review-09 P1-10 + P1-11）：
         *  1) 幂等语义统一——复合唯一索引（sessionId+char+phase+key）替换为 idempotencyKey 全局唯一：
         *     App 签发 key 全局去重（同 key 换 phase 不得重复计分，Room 与核心 Store 语义一致）。
         *     迁移前按 key 去重保留最早一行（防 CREATE UNIQUE INDEX 抛约束异常；幂等语义下重复行
         *     本就是同事件 retry 漏网，去重不丢独立证据）。
         *  2) characters 增加 gateStreak 四列（达标链持久化——L3→L4 三次间隔复习跨天/重启不丢）。 */
        internal val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM session_character_results WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM session_character_results GROUP BY idempotencyKey) " +
                        "AND idempotencyKey IS NOT NULL",   // review-09 S1：NULL 折叠风险——IS NOT NULL 限缩去重范围
                )
                db.execSQL("DROP INDEX IF EXISTS `index_session_character_results_sessionId_char_phase_idempotencyKey`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_session_character_results_idempotencyKey` " +
                    "ON session_character_results (idempotencyKey)")
                db.execSQL("ALTER TABLE characters ADD COLUMN gateStreakRecognize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE characters ADD COLUMN gateStreakWrite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE characters ADD COLUMN gateStreakUnderstand INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE characters ADD COLUMN gateStreakApply INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5→v6：characters 增加 gateStreakDate 四列（各维度上次间隔累计日期——
         *  lastReview 整字共享会跨维度误伤：同日先 RECOGNIZE 后 WRITE 复习被当重复不累计；
         *  按维度记录后 L3→L4 间隔日判定只认本维度上次达标日）。
         *  旧行默认为 NULL（无间隔基准）——首次/跨日复习正常累计，不受影响。 */
        internal val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN gateStreakDateRecognize TEXT")
                db.execSQL("ALTER TABLE characters ADD COLUMN gateStreakDateWrite TEXT")
                db.execSQL("ALTER TABLE characters ADD COLUMN gateStreakDateUnderstand TEXT")
                db.execSQL("ALTER TABLE characters ADD COLUMN gateStreakDateApply TEXT")
            }
        }

        internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `characters_new` (
                        `char` TEXT NOT NULL, `pinyin` TEXT NOT NULL,
                        `masteryRecognize` INTEGER NOT NULL, `masteryWrite` INTEGER NOT NULL,
                        `masteryUnderstand` INTEGER NOT NULL, `masteryApply` INTEGER NOT NULL,
                        `status` TEXT NOT NULL, `currentPromptLevel` INTEGER NOT NULL,
                        `streakRecognizeSuccess` INTEGER NOT NULL, `streakRecognizeErrors` INTEGER NOT NULL,
                        `streakWriteSuccess` INTEGER NOT NULL, `streakWriteErrors` INTEGER NOT NULL,
                        `streakUnderstandSuccess` INTEGER NOT NULL, `streakUnderstandErrors` INTEGER NOT NULL,
                        `streakApplySuccess` INTEGER NOT NULL, `streakApplyErrors` INTEGER NOT NULL,
                        `commonMistakes` TEXT NOT NULL, `source` TEXT, `easeFactor` REAL NOT NULL,
                        `intervalDays` INTEGER NOT NULL, `lastReview` TEXT, `nextReview` TEXT,
                        PRIMARY KEY(`char`))
                    """,
                )
                // 旧全局 streak → 掌握等级严格最高的维度（平局/无掌握 → 0）；characters 无索引，无需重建其他对象
                db.execSQL(
                    """INSERT INTO characters_new SELECT
                        char, pinyin, masteryRecognize, masteryWrite, masteryUnderstand, masteryApply,
                        status, currentPromptLevel,
                        CASE WHEN streakSuccess > 0 AND masteryRecognize > masteryWrite AND masteryRecognize > masteryUnderstand AND masteryRecognize > masteryApply THEN streakSuccess ELSE 0 END,
                        CASE WHEN streakErrors > 0 AND masteryRecognize > masteryWrite AND masteryRecognize > masteryUnderstand AND masteryRecognize > masteryApply THEN streakErrors ELSE 0 END,
                        CASE WHEN streakSuccess > 0 AND masteryWrite > masteryRecognize AND masteryWrite > masteryUnderstand AND masteryWrite > masteryApply THEN streakSuccess ELSE 0 END,
                        CASE WHEN streakErrors > 0 AND masteryWrite > masteryRecognize AND masteryWrite > masteryUnderstand AND masteryWrite > masteryApply THEN streakErrors ELSE 0 END,
                        CASE WHEN streakSuccess > 0 AND masteryUnderstand > masteryRecognize AND masteryUnderstand > masteryWrite AND masteryUnderstand > masteryApply THEN streakSuccess ELSE 0 END,
                        CASE WHEN streakErrors > 0 AND masteryUnderstand > masteryRecognize AND masteryUnderstand > masteryWrite AND masteryUnderstand > masteryApply THEN streakErrors ELSE 0 END,
                        CASE WHEN streakSuccess > 0 AND masteryApply > masteryRecognize AND masteryApply > masteryWrite AND masteryApply > masteryUnderstand THEN streakSuccess ELSE 0 END,
                        CASE WHEN streakErrors > 0 AND masteryApply > masteryRecognize AND masteryApply > masteryWrite AND masteryApply > masteryUnderstand THEN streakErrors ELSE 0 END,
                        commonMistakes, source, easeFactor, intervalDays, lastReview, nextReview
                        FROM characters
                    """,
                )
                db.execSQL("DROP TABLE characters")
                db.execSQL("ALTER TABLE characters_new RENAME TO characters")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "literacy.db",
                )
                    // P1-5：v1→v2 加 idempotency_key 唯一索引——真实 migration（不清数据）
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }
    }
}
