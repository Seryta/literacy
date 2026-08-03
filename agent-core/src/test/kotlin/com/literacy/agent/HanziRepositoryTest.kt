package com.literacy.agent

import com.literacy.agent.data.SqliteHanziRepository
import com.literacy.agent.data.SvgPathParser
import com.literacy.agent.learning.RuleStrokeEvaluator
import com.literacy.agent.model.StrokePoint
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 字库集成测试（真实数据 data/hanzi.db，makemeahanzi 管线产物）：
 * - 字段完整性：拼音 / 结构拆解 / 部首 / 笔画数
 * - SVG 路径解析 → 坐标点（StrokeEvaluator 输入）
 * - 真实参考笔画评估（手写评估从几何占位 → 真实字库）
 */
class HanziRepositoryTest {

    private val db = File("../data/hanzi.db")
    private val repo = SqliteHanziRepository(db)

    @Test
    fun `字库文件存在且可打开`() {
        assertTrue(db.exists(), "data/hanzi.db 应存在（管线产物）")
        assertNotNull(repo.find("家"), "应能查询到'家'")
    }

    @Test
    fun `家 的完整字段（拼音 结构 部首 笔画数）`() {
        val info = repo.find("家")!!
        assertEquals("jiā", info.pinyin)
        assertEquals("⿱宀豕", info.decomposition)   // 上下结构：宀 + 豕（难字拆分数据）
        assertEquals("宀", info.radical)
        assertEquals(10, info.strokeCount)
        assertEquals(10, info.strokes.size, "10 笔 SVG 路径")
    }

    @Test
    fun `赢 17 笔（难字拆解场景 GT-064）`() {
        val info = repo.find("赢")!!
        assertEquals(17, info.strokeCount)
        assertTrue(info.decomposition.startsWith("⿱"), "赢 应为上下结构拆解")
    }

    @Test
    fun `未收录字返回 null`() {
        assertNull(repo.find("\uFFFF"))   // 非汉字
    }

    @Test
    fun `SVG 路径解析为坐标序列（StrokeEvaluator 输入）`() {
        val info = repo.find("家")!!
        val stroke = info.strokes[0]
        val points = SvgPathParser.parse(stroke)
        assertTrue(points.size >= 2, "笔画路径应解析出至少 2 个坐标点，实际 ${points.size}")
        assertTrue(points.all { it.x.isFinite() && it.y.isFinite() }, "坐标应为有限值")
    }

    @Test
    fun `参考笔画可用作 StrokeEvaluator 输入`() {
        val refs = repo.referenceStrokes("家")!!
        assertEquals(10, refs.size, "10 笔参考笔画（骨架线）")
        val evaluator = RuleStrokeEvaluator()
        // 输入 = 参考骨架自身 → 完美匹配
        val eval = evaluator.evaluate(refs[0], refs[0])
        assertTrue(eval.ok, "完美跟写（输入=参考骨架）应通过，score=${eval.score}")
        assertTrue(eval.score > 0.9)
    }

    @Test
    fun `真实字库驱动的 guided_write 评估（替代几何占位）`() {
        // 用户书写轨迹（与参考骨架近似）：取参考骨架点 + 轻微偏移
        val refs = repo.referenceStrokes("家")!!
        val evaluator = RuleStrokeEvaluator()
        val input = refs[0].map { StrokePoint(it.x + 3f, it.y - 2f) }   // 轻微手抖
        val eval = evaluator.evaluate(input, refs[0])
        assertTrue(eval.ok, "轻微偏差的跟写应通过，score=${eval.score}")
        assertTrue(eval.issues.isEmpty(), "轻微偏差不应报问题，实际 ${eval.issues}")
    }

    @Test
    fun `真实字库驱动：方向相反的书写失败`() {
        val refs = repo.referenceStrokes("家")!!
        val evaluator = RuleStrokeEvaluator()
        // 反向书写：起收笔互换 + 中段反向
        val reversed = refs[0].reversed().map { StrokePoint(1000f - it.x, it.y) }
        val eval = evaluator.evaluate(reversed, refs[0])
        assertTrue(!eval.ok || eval.score < 0.6, "方向相反的书写不应通过，score=${eval.score}")
    }
}
