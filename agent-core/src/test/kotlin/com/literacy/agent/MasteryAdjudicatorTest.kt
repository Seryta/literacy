package com.literacy.agent

import com.literacy.agent.engine.MasteryAdjudicator
import com.literacy.agent.model.CharacterRecord
import com.literacy.agent.model.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 掌握等级裁决测试。对应 golden turn：
 * - GT-023 L0 独立成功 → 书写等级 2
 * - GT-024 L1 提示完成 → 书写等级 1（单次赋值，锁 review-03 P1-2 语义）
 * - GT-053 复习出错 + ≥L3 提示 → 降一级
 * - GT-029 连续成功升级
 */
class MasteryAdjudicatorTest {

    private val adj = MasteryAdjudicator()

    @Test
    fun `GT-023 L0 独立成功单次赋值书写等级 2`() {
        val rec = CharacterRecord("家")
        val result = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)
        assertEquals(2, result.masteryWrite)
        assertEquals(1, result.streakSuccess(Dimension.WRITE))
    }

    @Test
    fun `GT-024 L1 提示完成单次赋值书写等级 1（非 2）`() {
        val rec = CharacterRecord("家")
        val result = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 1)
        assertEquals(1, result.masteryWrite)
    }

    @Test
    fun `升级规则 连续 2 次 L1-L2 成功从学习中升初步掌握`() {
        var rec = CharacterRecord("家")
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 1)   // 第 1 次 → 1
        assertEquals(1, rec.masteryWrite)
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 1)   // 第 2 次 → 2
        assertEquals(2, rec.masteryWrite)
        // review-09 P1-9：门槛升级（1→2）后 streak 清零——L0 门槛从零重新累计，防借用
        assertEquals(0, rec.streakSuccess(Dimension.WRITE))
    }

    @Test
    fun `GT-053 复习出错且需 L3 以上提示降一级`() {
        var rec = CharacterRecord("家", masteryRecognize = 2, streakRecognizeSuccess = 2)
        val result = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = false, promptLevel = 4, isReview = true)
        assertEquals(1, result.masteryRecognize)
        assertEquals(1, result.streakErrors(Dimension.RECOGNIZE))
        assertEquals(0, result.streakSuccess(Dimension.RECOGNIZE))
    }

    @Test
    fun `非复习轮出错不降级`() {
        var rec = CharacterRecord("家", masteryWrite = 2, streakWriteSuccess = 2)
        val result = adj.adjudicate(rec, Dimension.WRITE, ok = false, promptLevel = 3, isReview = false)
        assertEquals(2, result.masteryWrite)   // 保持
    }

    @Test
    fun `连续成功 streak 递增另一个清零`() {
        var rec = CharacterRecord("家")
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = false, promptLevel = 3)
        assertEquals(1, rec.streakErrors(Dimension.WRITE))
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)
        assertEquals(1, rec.streakSuccess(Dimension.WRITE))
        assertEquals(0, rec.streakErrors(Dimension.WRITE))
    }

    // ---- P1-17：per-dimension streak 独立性 ----

    @Test
    fun `某维度连续失败不污染其他维度`() {
        // 识别连续 2 次失败（复习轮 + L3 提示 → 识别降一级）
        var rec = CharacterRecord("家", masteryRecognize = 2, masteryWrite = 1, streakRecognizeSuccess = 2, streakWriteSuccess = 1)
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = false, promptLevel = 4, isReview = true)
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = false, promptLevel = 4, isReview = true)
        assertEquals(2, rec.streakErrors(Dimension.RECOGNIZE))
        // 书写维度的成功计数不受识别失败影响：仍是首次尝试（1），且书写等级不受识别降级影响
        assertEquals(0, rec.streakErrors(Dimension.WRITE))
        assertEquals(1, rec.streakWriteSuccess)
        assertEquals(1, rec.masteryWrite)
        assertEquals(0, rec.masteryRecognize)   // 识别两次降级后归 0
    }

    @Test
    fun `识别连续成功不能被书写首次尝试借用升级`() {
        // 识别已连续 2 次成功（若走旧全局 streak，书写首次尝试会被误判达标）
        val rec = CharacterRecord("家", masteryRecognize = 1, streakRecognizeSuccess = 2)
        val result = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 1)
        // 书写首次 L1 成功：单次赋值 1（学习中），不能因识别 streak 直接升到初步掌握 2
        assertEquals(1, result.masteryWrite)
        assertEquals(1, result.streakSuccess(Dimension.WRITE))
        // 识别维度 streak 原样保留
        assertEquals(2, result.streakSuccess(Dimension.RECOGNIZE))
    }

    @Test
    fun `其他维度成功不打断目标维度连续失败计数`() {
        // 书写已连续失败 1 次
        var rec = CharacterRecord("家", streakWriteErrors = 1)
        // 识别成功（不重置书写失败计数）
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0)
        assertEquals(1, rec.streakErrors(Dimension.WRITE))
        assertEquals(0, rec.streakSuccess(Dimension.WRITE))
        // 书写再失败 → 连续 2 次（降难/降级判定依据）
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = false, promptLevel = 3, isReview = true)
        assertEquals(2, rec.streakErrors(Dimension.WRITE))
    }

    // ---- review-11 P1-6：达标链（跨门槛借用修复）----

    @Test
    fun `GT-021 L3 认对仍累计 streak（非达标成功计入展示）`() {
        // 学习中（current<2）门槛要求 L1-L2（promptLevel<=2）；L3 认对不达标但仍是成功
        var rec = CharacterRecord("家")
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 3)
        assertEquals(1, rec.streakSuccess(Dimension.RECOGNIZE), "L3 认对 streak 期望 1（GT-021）")
        assertEquals(1, rec.masteryRecognize, "单次赋值 1（不因 L3 达标）")
    }

    @Test
    fun `两次 L1 加一次 L0 不升 3（跨门槛借用修复）`() {
        // 初步掌握（current==2）门槛要求 L0；两次 L1 成功不得被一次 L0 借用越级
        var rec = CharacterRecord("家", masteryWrite = 2)
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 1)   // L1：不达标，打断链
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 1)   // L1：不达标
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)   // L0：达标链从 1 开始
        assertEquals(2, rec.masteryWrite, "L1+L1+L0 不得升 3（达标链只有 1）")
        // 展示 streak 仍累计全部成功（3 次）
        assertEquals(3, rec.streakSuccess(Dimension.WRITE))
    }

    @Test
    fun `连续两次 L0 从初步掌握升稳定掌握`() {
        var rec = CharacterRecord("家", masteryWrite = 2)
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)
        assertEquals(3, rec.masteryWrite)
        assertEquals(0, rec.streakSuccess(Dimension.WRITE), "门槛升级后达标链与 streak 清零")
    }

    @Test
    fun `非达标成功打断达标链（L1-L0-L1-L0 不升 3）`() {
        var rec = CharacterRecord("家", masteryWrite = 2)
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 1)   // 打断
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)   // 链 1
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 1)   // 打断
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)   // 链 1
        assertEquals(2, rec.masteryWrite, "L0 之间插入非达标成功则链重新计数")
    }

    @Test
    fun `失败打断达标链（复习轮 L0 成功两次后失败需重新累计）`() {
        var rec = CharacterRecord("家", masteryWrite = 2)
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)    // 链 1
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)    // 链 2 → 升 3
        assertEquals(3, rec.masteryWrite)
        // 升 3 后：复习轮达标（isReview）——失败打断链
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = false, promptLevel = 4, isReview = true)   // 降级
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0, isReview = false)
        assertEquals(2, rec.masteryWrite)
        assertEquals(1, rec.streakSuccess(Dimension.WRITE), "升 3 后首次成功从链 1 开始（不借用升级前成功）")
    }

    // ---- review-09 W7：P1-11 gateStreak 持久化（随 CharacterRecord 落库，新实例不丢）----

    @Test
    fun `gateStreak 随 record 持久化——新 adjudicator 实例跨调用不丢（P1-11）`() {
        // 模拟跨天/重启：record 已从 store 加载（携带 gateStreak=2），新实例继续累计
        val restart = MasteryAdjudicator()
        var rec = CharacterRecord("家", masteryRecognize = 3, gateStreakRecognize = 2)   // L3 已两次间隔复习
        rec = restart.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = true)   // 第 3 次达标 → 升 4
        assertEquals(4, rec.masteryRecognize, "重启后 gateStreak 从 record 读取，第 3 次间隔复习仍能升 L4")
        assertEquals(0, rec.gateStreakRecognize, "门槛升级后达标链清零")
    }

    @Test
    fun `gateStreak 门槛升级后清零并写入 record（P1-11）`() {
        var rec = CharacterRecord("家", masteryWrite = 2)
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)   // 链 1
        assertEquals(1, rec.gateStreakWrite, "未升级时达标链保存在 record")
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0)   // 链 2 → 升 3
        assertEquals(3, rec.masteryWrite)
        assertEquals(0, rec.gateStreakWrite, "门槛升级后达标链清零（自上次门槛升级以来语义）")
        // 升级后复习轮达标：链从 0 重新累计（不借用升级前成功；L3 非复习成功不累计）
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0, isReview = true)
        assertEquals(1, rec.gateStreakWrite)
    }

    @Test
    fun `非达标成功与失败归零 gateStreak（P1-11）`() {
        // L3 需要 isReview 达标：非复习成功不累计
        var rec = CharacterRecord("家", masteryRecognize = 3, gateStreakRecognize = 1)
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = false)
        assertEquals(0, rec.gateStreakRecognize, "L3 非复习成功不达标 → 链归零")
        // 失败归零
        rec = CharacterRecord("家", masteryRecognize = 3, gateStreakRecognize = 2)
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = false, promptLevel = 4, isReview = true)
        assertEquals(0, rec.gateStreakRecognize, "失败打断达标链")
    }

    // ---- review-09 W7：P1-14 deriveStatus 门槛（MASTERY-CRITERIA §3）----

    @Test
    fun `deriveStatus reviewing 需识别+书写都 ≥2（P1-14）`() {
        // recognize=2 + write=1：书写不足 → learning（修复前被误判 reviewing）
        assertEquals("learning", CharacterRecord("家", masteryRecognize = 2, masteryWrite = 1).deriveStatus())
        assertEquals("reviewing", CharacterRecord("家", masteryRecognize = 2, masteryWrite = 2).deriveStatus())
    }

    @Test
    fun `deriveStatus mastered 需识别+书写都 ≥3 且理解 ≥2（P1-14）`() {
        // recognize=3 + write=2：书写不足 → reviewing（修复前被误判 mastered）
        assertEquals("reviewing", CharacterRecord("家", masteryRecognize = 3, masteryWrite = 2, masteryUnderstand = 2).deriveStatus())
        assertEquals("mastered", CharacterRecord("家", masteryRecognize = 3, masteryWrite = 3, masteryUnderstand = 2).deriveStatus())
    }
    // ---- 残余修复复核：gateStreak 间隔日语义（同日不累计但保留链、跨日累计、学习路径不受影响）----

    @Test
    fun `gateStreak 学习门槛同日连续仍累计（L1-L2 升级语义不变）`() {
        val adj = MasteryAdjudicator()
        var rec = CharacterRecord("家", masteryRecognize = 1)   // 学习中（current<2）
        val today = "2026-08-05"
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 1, today = today)
        assertEquals(1, rec.gateStreakRecognize, "学习门槛首次达标 +1")
        // 同日第二次达标（学习路径"连续 2 次"不应被 day-gate 阻断）
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 1, today = today)
        assertEquals(2, rec.masteryRecognize, "同日连续 2 次 L1-L2 成功升初步掌握（学习路径不被 day-gate 阻断）")
        assertEquals(0, rec.gateStreakRecognize, "门槛升级后达标链清零（既有规则）")
    }

    @Test
    fun `gateStreak L3 复习跨日累计`() {
        val adj = MasteryAdjudicator()
        var rec = CharacterRecord("家", masteryRecognize = 3)
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = true, today = "2026-08-01")
        assertEquals(1, rec.gateStreakRecognize)
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = true, today = "2026-08-02")
        assertEquals(2, rec.gateStreakRecognize, "跨日复习累计")
    }

    @Test
    fun `gateStreak L3 复习同日不累计但保留链（防多 key 凑间隔）`() {
        val adj = MasteryAdjudicator()
        // 跨日累计 1 次后，同日再答（assess+reinforce 两条记录场景）不累计也不清零
        var rec = CharacterRecord("家", masteryRecognize = 3, lastReview = "2026-08-04")
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = true, today = "2026-08-05")
        assertEquals(1, rec.gateStreakRecognize)
        // 同日第二次（间隔基准已置 today 的 reinforce 路径）：保留链不清零
        rec = rec.copy(lastReview = "2026-08-05")
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = true, today = "2026-08-05")
        assertEquals(1, rec.gateStreakRecognize, "同日复习保留链（不累计不清零）")
        assertEquals(3, rec.masteryRecognize, "同日重复作答不能把 L3 抬到 L4")
    }

    // ---- 间隔日判定按维度（lastReview 整字共享不再误伤跨维度）----

    @Test
    fun `同日先 RECOGNIZE 后 WRITE 复习各自独立累计（跨维度不误伤）`() {
        val adj = MasteryAdjudicator()
        val today = "2026-08-05"
        var rec = CharacterRecord("家", masteryRecognize = 3, masteryWrite = 3)   // 两维度都 L3（稳定掌握）
        // 同日先 RECOGNIZE 复习：累计 1，间隔基准记今天
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = true, today = today)
        assertEquals(1, rec.gateStreakRecognize)
        // 同日 WRITE 复习：本维度首次（间隔基准为空），不得被 RECOGNIZE 的日期误当重复（旧 lastReview 误伤）
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0, isReview = true, today = today)
        assertEquals(1, rec.gateStreakWrite, "WRITE 首次间隔复习必须累计（不被 RECOGNIZE 同日复习误伤）")
        assertEquals(1, rec.gateStreakRecognize, "RECOGNIZE 链不受 WRITE 影响")
        // 次日再复习两维度：各自 +1（三次间隔各自独立累计）
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = true, today = "2026-08-06")
        rec = adj.adjudicate(rec, Dimension.WRITE, ok = true, promptLevel = 0, isReview = true, today = "2026-08-06")
        assertEquals(2, rec.gateStreakRecognize)
        assertEquals(2, rec.gateStreakWrite)
    }

    @Test
    fun `同日失败重置后再次成功正常累计（间隔基准随链归零清空）`() {
        val adj = MasteryAdjudicator()
        val today = "2026-08-05"
        var rec = CharacterRecord("家", masteryRecognize = 3)
        // 首次间隔复习成功：累计 1，基准今天
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = true, today = today)
        assertEquals(1, rec.gateStreakRecognize)
        assertEquals(today, rec.gateStreakDateRecognize)
        // 同日失败：链归零 + 基准清空（残留日期不得把后续成功误当同日重复）
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = false, promptLevel = 4, isReview = true, today = today)
        assertEquals(0, rec.gateStreakRecognize)
        assertEquals(null, rec.gateStreakDateRecognize, "链归零时间隔基准必须清空")
        // 同日再次成功：从 1 重新起算（不是被残留日期挡住）
        rec = adj.adjudicate(rec, Dimension.RECOGNIZE, ok = true, promptLevel = 0, isReview = true, today = today)
        assertEquals(1, rec.gateStreakRecognize, "重置后同日成功正常累计")
    }
}
