package com.literacy.agent

import com.literacy.agent.replay.CaseLoader
import com.literacy.agent.replay.CaseRunner
import com.literacy.agent.replay.ReplayRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 第五版：全量用例解析 + 批量回放。
 * - 解析：T001-T005 共 53 个用例全部可解析（规范化后）
 * - 回放：对可自动化的用例批量端到端，报告通过/失败
 */
class FullSuiteTest {

    private val dir = File("../test-cases")

    @Test
    fun `全量解析：53 个用例全部可解析`() {
        val result = CaseLoader().loadFiles(dir)
        assertEquals(53, result.cases.size)
        println("全量解析问题（不应掩盖用例）：${result.problems}")
        // 每模块数量核对（review-04 记录的分布）
        val byModule = result.cases.groupBy { it.module }.mapValues { it.value.size }
        println("模块分布：$byModule")
        assertEquals(17, result.cases.count { it.module == "agent-protocol" })
        assertEquals(15, result.cases.count { it.module == "character-closed-loop" })
        assertEquals(8, result.cases.count { it.module == "voice-interaction" })
        assertEquals(8, result.cases.count { it.module == "review-algorithm" })
        assertEquals(5, result.cases.count { it.module == "exercise-variants" })
    }

    @Test
    fun `批量回放：自动化用例全部通过`() {
        val result = CaseLoader().loadFiles(dir)
        val problems = mutableMapOf<String, List<String>>()
        for (case in result.cases) {
            // 每个用例独立 runner，避免跨用例状态污染
            val runner = ReplayRunner()
            val p = CaseRunner(runner).run(case)
            if (p.isNotEmpty()) problems[case.id] = p
        }
        println("回放失败的用例：$problems")
        assertTrue(problems.isEmpty(), "以下用例回放失败: ${problems.keys}")
    }

    @Test
    fun `GT-002 复习优先：start_review 执行进入复习模式`() {
        val result = CaseLoader().load(File("../test-cases/T001-agent-protocol.md"))
        val gt002 = result.cases.first { it.id == "GT-002" }
        val runner = ReplayRunner()
        runner.reviewQueue.addAll(listOf("家", "的", "电"))   // 前置 review_queue
        val p = CaseRunner(runner).run(gt002)
        assertEquals(emptyList(), p)
    }
}
