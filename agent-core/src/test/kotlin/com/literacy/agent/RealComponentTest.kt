package com.literacy.agent

import com.literacy.agent.learning.IntentResolver
import com.literacy.agent.learning.RuleStrokeEvaluator
import com.literacy.agent.model.StrokePoint
import com.literacy.agent.model.VoiceIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 真实组件测试（阶段 B 前置：本地确定性逻辑不依赖 mock）：
 * - 手写评估规则引擎：起笔/收笔/长度/方向特征对比（RESEARCH-TECH）
 * - 意图解析：STT 文本 → 教学意图的本地理解
 */
class RealComponentTest {

    // ---- 手写评估（RuleStrokeEvaluator）----

    private val evaluator = RuleStrokeEvaluator()
    private val ref = listOf(StrokePoint(0f, 100f), StrokePoint(100f, 0f))   // 参考：左下→右上

    @Test
    fun `完美匹配笔画评分接近 1 且 ok`() {
        val input = listOf(StrokePoint(2f, 98f), StrokePoint(50f, 50f), StrokePoint(98f, 2f))
        val eval = evaluator.evaluate(input, ref)
        assertTrue(eval.ok, "偏差在阈值内")
        assertTrue(eval.score > 0.8, "评分应接近 1，实际 ${eval.score}")
        assertTrue(eval.issues.isEmpty())
    }

    @Test
    fun `起笔偏差过大评分降低并报告问题`() {
        val input = listOf(StrokePoint(80f, 80f), StrokePoint(90f, 50f), StrokePoint(98f, 2f))
        val eval = evaluator.evaluate(input, ref)
        // 起笔距离 sqrt(80^2+20^2)=82.5 / 141 ≈ 0.58 > 0.3 → 起笔偏差
        assertTrue(eval.issues.contains("起笔位置偏差"), "应报告起笔偏差，实际 ${eval.issues}")
    }

    @Test
    fun `方向相反评分低且 ok=false`() {
        val input = listOf(StrokePoint(0f, 0f), StrokePoint(50f, 50f), StrokePoint(100f, 100f))  // 左上→右下（相反）
        val eval = evaluator.evaluate(input, ref)
        assertFalse(eval.ok, "方向相反应失败")
        assertTrue(eval.issues.contains("笔画方向偏斜"), "应报告方向偏斜")
    }

    @Test
    fun `笔画长度过短评分低`() {
        val input = listOf(StrokePoint(49f, 51f), StrokePoint(51f, 49f))   // 长度仅 2.8，比例 0.02
        val eval = evaluator.evaluate(input, ref)
        assertTrue(eval.issues.contains("笔画长度异常"), "应报告长度异常")
    }

    @Test
    fun `数据不足返回失败`() {
        val eval = evaluator.evaluate(listOf(StrokePoint(0f, 0f)), ref)
        assertFalse(eval.ok)
    }

    // ---- 意图解析（IntentResolver）----

    private val resolver = IntentResolver()

    @Test
    fun `请求看拼音识别为 REQUEST_PINYIN`() {
        assertEquals(VoiceIntent.REQUEST_PINYIN, resolver.activeIntent("这个字怎么读？"))
        assertEquals(VoiceIntent.REQUEST_PINYIN, resolver.activeIntent("能给我看拼音吗"))
    }

    @Test
    fun `插单识别为 REQUEST_NEW_CHAR（学字模式）`() {
        assertEquals(VoiceIntent.REQUEST_NEW_CHAR, resolver.activeIntent("我想学'药'字"))
        assertEquals(VoiceIntent.REQUEST_NEW_CHAR, resolver.activeIntent("学电字"))
    }

    @Test
    fun `切路径识别为 SWITCH_PATH（不写字或手不方便）`() {
        assertEquals(VoiceIntent.SWITCH_PATH, resolver.activeIntent("我今天手不方便，不写字了"))
        assertEquals(VoiceIntent.SWITCH_PATH, resolver.activeIntent("不想写了"))
    }

    @Test
    fun `普通对话返回 null（交由上下文判定）`() {
        assertNull(resolver.activeIntent("就是一家人住的地方"))
        assertNull(resolver.activeIntent("家"))
    }

    @Test
    fun `认读判定：文本等于目标字为正确`() {
        assertTrue(resolver.isRecognitionCorrect("家", "家"))
        assertFalse(resolver.isRecognitionCorrect("妈", "家"))
    }
}
