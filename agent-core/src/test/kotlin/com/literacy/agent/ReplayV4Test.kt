package com.literacy.agent

import com.literacy.agent.model.Phase
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.replay.CaseLoader
import com.literacy.agent.replay.CaseRunner
import com.literacy.agent.replay.ReplayRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第四版：暂停恢复驱动 + 更多用例自动解析回放。
 * 对应 golden turn：GT-003（认对推进）、GT-004（认错不推进）、GT-010（幂等）、GT-044（暂停恢复）。
 */
class ReplayV4Test {

    private val t001 = File("../test-cases/T001-agent-protocol.md")

    @Test
    fun `GT-044 暂停恢复：从暂停时阶段继续，不重走 introduce`() {
        val runner = ReplayRunner().startSession("家")
        runner.advance()                                       // introduce → recognize
        assertEquals(Phase.RECOGNIZE, runner.state.phase)
        runner.pause()
        assertTrue(runner.paused)
        // 恢复：resume 从原阶段继续
        assertTrue(runner.resume())
        assertEquals(Phase.RECOGNIZE, runner.state.phase)      // 不重走 introduce
        assertFalse(runner.paused)
        // 未暂停时 resume 返回 false
        assertFalse(runner.resume())
    }

    @Test
    fun `GT-003 自动回放：recognize 认对推进到 demonstrate`() {
        val result = CaseLoader().load(t001)
        val gt003 = result.cases.first { it.id == "GT-003" }
        val runner = ReplayRunner()
        val problems = CaseRunner(runner).run(gt003)
        assertEquals(emptyList(), problems)
        assertEquals(Phase.DEMONSTRATE, runner.state.phase)
    }

    @Test
    fun `GT-004 自动回放：认错不推进不落库`() {
        val result = CaseLoader().load(t001)
        val gt004 = result.cases.first { it.id == "GT-004" }
        val runner = ReplayRunner()
        val problems = CaseRunner(runner).run(gt004)
        assertEquals(emptyList(), problems)
        assertEquals(Phase.RECOGNIZE, runner.state.phase)      // 保持 recognize
        assertEquals(0, runner.store.results.size)             // 不落库
    }

    @Test
    fun `GT-010 自动回放：幂等仍通过（注释修正后）`() {
        val result = CaseLoader().load(t001)
        val gt010 = result.cases.first { it.id == "GT-010" }
        val runner = ReplayRunner()
        val problems = CaseRunner(runner).run(gt010)
        assertEquals(emptyList(), problems)
        assertEquals(1, runner.store.results.size)
        assertEquals(2, runner.store.getCharacter("家").masteryWrite)
    }

    @Test
    fun `T001 全部用例可解析且解析问题收敛`() {
        val result = CaseLoader().load(t001)
        assertEquals(17, result.cases.size)
        // 记录剩余解析问题（不应掩盖可用用例）
        println("T001 剩余解析问题：${result.problems}")
        assertTrue(result.cases.isNotEmpty())
    }

    @Test
    fun `暂停后恢复不重新走 introduce（驱动版 GT-044 完整序列）`() {
        val runner = ReplayRunner().startSession("国")
        runner.advance()                                       // introduce → recognize
        runner.pause()                                         // PauseRequested
        runner.resume()                                        // ButtonTapped(action=resume)
        // 恢复 turn 注入暂停前 lesson_state，从原阶段继续
        assertEquals(Phase.RECOGNIZE, runner.state.phase)
        assertTrue(runner.voice(VoiceIntent.RECOGNIZED))       // 继续教学正常推进
        assertEquals(Phase.DEMONSTRATE, runner.state.phase)
    }
}
