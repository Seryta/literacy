package com.literacy.agent

import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.Mode
import com.literacy.agent.model.ReviewStage
import com.literacy.agent.model.ToolCall
import com.literacy.agent.replay.Assertions
import com.literacy.agent.replay.ReplayRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第二版：复习模式驱动 + LLM 工具执行链路 + text 断言。
 * 对应 golden turn：
 * - GT-050 复习模式进入（队列非空校验 + allowed_actions 切换）
 * - GT-051 recall 不展示答案（复习阶段由 Agent 行为保证，驱动层验证状态切换）
 * - GT-054 next 队列清空本地拒绝
 * - GT-010 幂等：llmTurn 执行 record_result 重复 key 只插一条
 * - GT-013 工具超限截断（只执行前 3 个）
 */
class ReplayV2Test {

    @Test
    fun `GT-050 复习模式进入：队列非空才允许，allowed_actions 切换`() {
        val runner = ReplayRunner().startSession("家")
        // 队列空 → 拒绝
        assertFalse(runner.startReview())
        // 队列非空 → 进入，allowed_actions 切换为复习集
        runner.reviewQueue.addAll(listOf("家", "的"))
        assertTrue(runner.startReview())
        assertEquals(Mode.REVIEW, runner.state.mode)
        assertEquals(ReviewStage.RECALL, runner.state.reviewStage)
        assertEquals(setOf("next", "start_review", "end_session"), runner.state.allowedActions)
        // §6.5：advance_phase / complete_character 不适用
        assertFalse(runner.control("advance_phase"))
        assertFalse(runner.control("complete_character"))
        assertTrue(runner.control("next"))
    }

    @Test
    fun `GT-054 next 推进复习字，队列清空本地拒绝`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.addAll(listOf("家", "的"))
        runner.startReview()

        // P1-9：next 仅在 NEXT 阶段允许——先推进复习内部阶段到 NEXT
        runner.advanceReview()   // recall → assess
        // review-09 P1-4：ASSESS 无判题证据不能推进（门禁）
        assertEquals(ReviewStage.ASSESS, runner.advanceReview())
        runner.tapped("fill_blank", correct = true, exerciseId = "e1")   // 判题证据
        runner.advanceReview()   // assess → reinforce
        runner.advanceReview()   // reinforce → next
        assertTrue(runner.nextReviewChar())
        assertEquals("的", runner.state.char)          // 第一个复习字（startReview 已消费"家"）

        // 队列已空 → next 被本地拒绝，保持当前字
        assertFalse(runner.nextReviewChar())
        assertEquals("的", runner.state.char)
    }

    @Test
    fun `GT-051 复习阶段序列推进`() {
        val runner = ReplayRunner().startSession("家")
        runner.reviewQueue.add("家")
        runner.startReview()
        assertEquals(ReviewStage.ASSESS, runner.advanceReview())
        // review-09 P1-4：无判题证据时推进被拒（停留 ASSESS）
        assertEquals(ReviewStage.ASSESS, runner.advanceReview())
        runner.tapped("fill_blank", correct = true, exerciseId = "e1")
        assertEquals(ReviewStage.REINFORCE, runner.advanceReview())
        assertEquals(ReviewStage.NEXT, runner.advanceReview())
        assertEquals(null, runner.advanceReview())     // NEXT 之后由 nextReviewChar 处理
    }

    @Test
    fun `GT-010 幂等：llmTurn 重复 record_result 只插一条`() {
        val runner = ReplayRunner().startSession("家")
        val key = "550e8400-e29b-41d4-a716-446655440000"
        val output = LlmOutput(
            text = "写得很不错",
            toolCalls = listOf(
                ToolCall("record_result", mapOf(
                    "char" to "家",
                    "result" to mapOf(
                        "phase" to "independent_write", "score" to 0.85,
                        "prompt_level" to "none", "idempotency_key" to key,
                    ),
                )),
                ToolCall("record_result", mapOf(   // 同 key 重复
                    "char" to "家",
                    "result" to mapOf(
                        "phase" to "independent_write", "score" to 0.85,
                        "prompt_level" to "none", "idempotency_key" to key,
                    ),
                )),
            ),
        )
        runner.llmTurn(output)
        assertEquals(1, runner.store.results.size)
        // 断言器验证
        assertEquals(emptyList(), Assertions().resultCount(runner.store, 1))
    }

    @Test
    fun `GT-013 工具超限截断：只执行前 3 个`() {
        val runner = ReplayRunner().startSession("家")
        val toolCalls = (1..5).map { ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf(
                "phase" to "independent_write", "score" to 0.9,
                "prompt_level" to "none", "idempotency_key" to "key-$it",
            ),
        )) }
        runner.llmTurn(LlmOutput("好的", toolCalls))
        assertEquals(3, runner.store.results.size)   // 只执行前 3 个
    }

    @Test
    fun `text 语义断言：contains 与 not_contains`() {
        val runner = ReplayRunner().startSession("家")
        runner.llmTurn(LlmOutput("对，这就是'家'字。不过我们不着急，慢慢来。"))
        val problems = Assertions().text(
            runner.lastText,
            contains = listOf("家"),
            notContains = listOf("太棒了", "真聪明"),
        )
        assertEquals(emptyList(), problems)

        val problems2 = Assertions().text(
            runner.lastText,
            contains = listOf("太棒了"),
            notContains = emptyList(),
        )
        assertTrue(problems2.isNotEmpty())
    }

    @Test
    fun `state 断言：phase 与 storage 掌握等级`() {
        val runner = ReplayRunner().startSession("家")
        runner.advance()
        runner.voice(com.literacy.agent.model.VoiceIntent.RECOGNIZED)
        val phaseProblems = Assertions().phase(runner.state, "demonstrate")
        assertEquals(emptyList(), phaseProblems)
        assertTrue(Assertions().phase(runner.state, "decide").isNotEmpty())
    }

    @Test
    fun `review-09 P1-1 跟写成功笔数累计（onStrokeFinished 生产路径）`() {
        val runner = ReplayRunner().startSession("家")
        runner.advance(); runner.advance()   // introduce/demonstrate 穿过 → guided_write 前
        // 直接触发生产路径事件（onStrokeFinished）：成功笔 +1，失败笔不计
        runner.onStrokeFinished(1, listOf(
            com.literacy.agent.model.StrokePoint(0f, 100f),
            com.literacy.agent.model.StrokePoint(100f, 0f),
        ))   // 匹配几何占位参考（对角线）
        assertEquals(1, runner.completedStrokes, "跟写成功笔数应累计（此前恒 0——全部笔画门禁永不满足）")
    }
}
