package com.literacy.agent

import com.literacy.agent.engine.MasteryAdjudicator
import com.literacy.agent.engine.PhaseMachine
import com.literacy.agent.model.LearningPath
import com.literacy.agent.model.Phase
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.replay.ReplayRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回放驱动测试：用 ReplayRunner 跑完整事件序列。
 * 对应 golden turn：
 * - GT-010 幂等落库
 * - GT-020 完整 9 阶段闭环（驱动版）
 * - GT-031/034 学习路径分支
 */
class ReplayRunnerTest {

    @Test
    fun `GT-010 同一 idempotency_key 重复 record_result 只插一条`() {
        val runner = ReplayRunner().startSession("家")
        val key = "550e8400-e29b-41d4-a716-446655440000"
        assertTrue(runner.recordResult("家", "independent_write", 0.85, "none", key))
        assertFalse(runner.recordResult("家", "independent_write", 0.85, "none", key))
        assertEquals(1, runner.store.results.size)
    }

    @Test
    fun `GT-020 完整 9 阶段闭环（驱动版）`() {
        val runner = ReplayRunner().startSession("家")
        assertEquals(Phase.INTRODUCE, runner.state.phase)

        assertTrue(runner.advance())                     // introduce → recognize（自动通过）
        assertTrue(runner.voice(VoiceIntent.RECOGNIZED)) // recognize → demonstrate
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "recognize", "score" to 1.0, "prompt_level" to "none", "idempotency_key" to "gt020-drive-recognize"),
        )))))   // P1-7：认对落库
        assertTrue(runner.advance())                     // demonstrate → guided_write（自动通过）
        assertTrue(runner.writing("guided_write", ok = true, promptLevel = 3))
        assertEquals(Phase.INDEPENDENT_WRITE, runner.state.phase)

        assertTrue(runner.writing("independent_write", ok = true, promptLevel = 0))
        // P1-7：裁决统一到 record_result——经 llmTurn 落库触发掌握升级
        runner.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "independent_write", "score" to 0.9, "prompt_level" to "none", "idempotency_key" to "gt020-drive-write"),
        )))))
        assertEquals(Phase.EXPLAIN, runner.state.phase)
        // GT-023 断言：L0 成功 → 书写等级 2
        assertEquals(2, runner.store.getCharacter("家").masteryWrite)

        assertTrue(runner.voice(VoiceIntent.OTHER))      // explain → sentence（尝试即可）
        assertTrue(runner.voice(VoiceIntent.OTHER))      // sentence → record
        assertTrue(runner.advance())                     // record → decide（自动通过）
        assertEquals(Phase.DECIDE, runner.state.phase)
    }

    @Test
    fun `GT-031 识主写辅路径 independent_write 改听音选字`() {
        val runner = ReplayRunner().startSession("家", LearningPath.READ_PRIMARY)
        runner.advance()
        runner.voice(VoiceIntent.RECOGNIZED)
        runner.advance()
        runner.writing("guided_write", ok = true, promptLevel = 3)
        assertEquals(Phase.INDEPENDENT_WRITE, runner.state.phase)

        // 书写通道不满足（识主写辅）
        assertFalse(runner.writing("independent_write", ok = true, promptLevel = 0))
        assertEquals(Phase.INDEPENDENT_WRITE, runner.state.phase)
        // 听音选字判题正确满足
        assertTrue(runner.tapped("select", correct = true, exerciseId = "e1"))
        assertEquals(Phase.EXPLAIN, runner.state.phase)
    }

    @Test
    fun `GT-034 识读优先路径 independent_write 改选字填空`() {
        val runner = ReplayRunner().startSession("家", LearningPath.READ_ONLY)
        runner.advance()
        runner.voice(VoiceIntent.RECOGNIZED)
        runner.advance()
        runner.writing("guided_write", ok = true, promptLevel = 3)
        assertFalse(runner.writing("independent_write", ok = true, promptLevel = 0))
        assertTrue(runner.tapped("fill_blank", correct = true, exerciseId = "e2"))
        assertEquals(Phase.EXPLAIN, runner.state.phase)
    }

    @Test
    fun `GT-034 识读路径 record_result 不虚增 WRITE（fill_blank与audio_choice映射 RECOGNIZE）`() {
        // 识读优先：选字填空判对 → phase=independent_write + exercise_type=fill_blank → RECOGNIZE，WRITE 不增长
        val ro = ReplayRunner().startSession("家", LearningPath.READ_ONLY)
        ro.advance(); ro.voice(VoiceIntent.RECOGNIZED); ro.advance()
        ro.writing("guided_write", ok = true, promptLevel = 3)
        ro.tapped("fill_blank", correct = true, exerciseId = "e2")
        ro.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "independent_write", "score" to 1.0, "prompt_level" to "none",
                "exercise_type" to "fill_blank", "idempotency_key" to "gt034-ro-fill-blank"),
        )))))
        assertEquals(0, ro.store.getCharacter("家").masteryWrite, "read_only 判对不练书写：WRITE 不得虚增")
        assertEquals(1, ro.store.getCharacter("家").masteryRecognize, "选字填空练识读：RECOGNIZE 增长")

        // 识主写辅：听音选字判对 → audio_choice 同样映射 RECOGNIZE
        val rp = ReplayRunner().startSession("家", LearningPath.READ_PRIMARY)
        rp.advance(); rp.voice(VoiceIntent.RECOGNIZED); rp.advance()
        rp.writing("guided_write", ok = true, promptLevel = 3)
        rp.tapped("select", correct = true, exerciseId = "e1")
        rp.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "independent_write", "score" to 1.0, "prompt_level" to "none",
                "exercise_type" to "audio_choice", "idempotency_key" to "gt034-rp-audio-choice"),
        )))))
        assertEquals(0, rp.store.getCharacter("家").masteryWrite)
        assertEquals(1, rp.store.getCharacter("家").masteryRecognize)

        // 对照组：书写通道独立写判对 → 仍映射 WRITE（P1-7 §4：L0 无提示成功 → 书写等级 2）
        val wp = ReplayRunner().startSession("家", LearningPath.WRITE_PARALLEL)
        wp.advance(); wp.voice(VoiceIntent.RECOGNIZED); wp.advance()
        wp.writing("guided_write", ok = true, promptLevel = 3)
        wp.llmTurn(com.literacy.agent.model.LlmOutput("", listOf(com.literacy.agent.model.ToolCall("record_result", mapOf(
            "char" to "家",
            "result" to mapOf("phase" to "independent_write", "score" to 0.9, "prompt_level" to "none",
                "idempotency_key" to "gt020-drive-write"),
        )))))
        assertEquals(2, wp.store.getCharacter("家").masteryWrite)
        assertEquals(0, wp.store.getCharacter("家").masteryRecognize)
    }

    @Test
    fun `GT-009 非法控制动作被拒绝`() {
        val runner = ReplayRunner().startSession("家")
        // introduce 阶段（非 decide）：complete_character 不在 allowed_actions → 拒绝（§6.2）
        assertFalse(runner.control("complete_character"))
        // 非法动作名（协议外）始终拒绝
        assertFalse(runner.control("drop_database"))
    }
}
