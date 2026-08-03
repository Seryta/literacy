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
}
