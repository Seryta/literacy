package com.literacy.agent

import com.literacy.agent.model.LearningPath
import com.literacy.agent.model.Phase
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.replay.CaseLoader
import com.literacy.agent.replay.CaseRunner
import com.literacy.agent.replay.ReplayRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CaseLoader 第三版：从 test-cases/ 目录的 markdown 文件自动解析用例并回放。
 * 端到端验证 GT-009（非法阶段迁移）与 GT-010（幂等）。
 */
class CaseLoaderTest {

    private val t001 = File("../test-cases/T001-agent-protocol.md")

    @Test
    fun `解析 T001 全部用例并报告解析问题`() {
        val result = CaseLoader().load(t001)
        // T001 现有 17 个用例（GT-001~017）
        assertEquals(17, result.cases.size)
        // 解析问题不应掩盖可用用例（允许存在不规范块，记录为 problem）
        println("解析问题：${result.problems}")
        assertTrue(result.cases.isNotEmpty())
    }

    @Test
    fun `GT-009 解析结构正确`() {
        val result = CaseLoader().load(t001)
        val gt009 = result.cases.first { it.id == "GT-009" }

        assertEquals("agent-protocol", gt009.module)
        // 前置状态：recognize 阶段，allowed_actions 不含 complete_character
        assertEquals(Phase.RECOGNIZE, gt009.setup.lessonState.phase)
        assertTrue("complete_character" !in gt009.setup.lessonState.allowedActions)
        // 输入事件：VoiceInput
        assertEquals(1, gt009.inputEvents.size)
        // mock 脚本：complete_character toolCall
        assertEquals(1, gt009.llmScript.size)
        assertEquals("complete_character", gt009.llmScript[0].toolCalls[0].name)
        // 断言：phase 保持 recognize；拒绝行为在 local_handling（reject: true）
        assertEquals("recognize", gt009.assertions.expectedPhase)
        assertEquals(true, gt009.assertions.localHandling["reject"])
    }

    @Test
    fun `GT-009 端到端回放：非法迁移被静默拒绝`() {
        val result = CaseLoader().load(t001)
        val gt009 = result.cases.first { it.id == "GT-009" }

        val runner = ReplayRunner()
        val problems = CaseRunner(runner).run(gt009)
        assertEquals(emptyList(), problems)   // 全部断言通过
        assertEquals(Phase.RECOGNIZE, runner.state.phase)   // 保持 recognize
    }

    @Test
    fun `GT-010 端到端回放：幂等只插一条`() {
        val result = CaseLoader().load(t001)
        val gt010 = result.cases.first { it.id == "GT-010" }

        val runner = ReplayRunner()
        val problems = CaseRunner(runner).run(gt010)
        assertEquals(emptyList(), problems)
        assertEquals(1, runner.store.results.size)
    }

    @Test
    fun `GT-003 解析：recognize 认对驱动`() {
        val result = CaseLoader().load(t001)
        val gt003 = result.cases.first { it.id == "GT-003" }
        // 输入事件是 VoiceInput（intent 未在用例中显式给出 → OTHER；回放时由脚本驱动）
        assertEquals(1, gt003.inputEvents.size)
        assertTrue(gt003.inputEvents[0] is com.literacy.agent.model.VoiceInput)
    }

    @Test
    fun `学习路径解析`() {
        val result = CaseLoader().load(File("../test-cases/T002-character-closed-loop.md"))
        val gt031 = result.cases.first { it.id == "GT-031" }
        assertEquals(LearningPath.READ_PRIMARY, gt031.setup.lessonState.learningPath)
    }
}
