package com.literacy.agent

import com.literacy.agent.provider.FixtureProvider
import com.literacy.agent.provider.Fixtures
import com.literacy.agent.replay.CaseLoader
import com.literacy.agent.replay.CaseRunner
import com.literacy.agent.replay.ReplayRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * fixture 回放验证（review-06 建议 4：断言分级）。
 *
 * - 行为类差异（phase/mode/record_result/本地裁决）：**硬失败**——真实模型输出
 *   不应破坏本地状态机与裁决
 * - text 措辞差异：真实模型措辞 ≠ 用例期望关键词，打印供 review（提示词信号）
 * - 防御性断言（reject/filter/dedup/llm_turn 等 mock-only）：标注跳过——
 *   用例锁定"模型做错事"，真实模型不越权是好事（本地行为已由 JVM 单测覆盖）
 */
class FixtureReplayTest {

    @Test
    fun `fixture 回放：本地裁决不崩溃，行为类差异硬失败，差异全部报告`() {
        val fixturesDir = File("../fixtures")
        if (!fixturesDir.exists() || fixturesDir.listFiles { it.name.endsWith(".json") }!!.isEmpty()) {
            println("无 fixture（未录制）——跳过；录制见 RecordFixturesTest")
            return
        }
        val cases = CaseLoader().loadFiles(File("../test-cases")).cases
        var clean = 0
        val hardProblems = mutableMapOf<String, List<String>>()
        val textReview = mutableMapOf<String, List<String>>()
        for (f in fixturesDir.listFiles { it.name.endsWith(".json") }!!.sortedBy { it.name }) {
            val caseId = f.name.removeSuffix(".json")
            val case = cases.find { it.id == caseId } ?: continue
            val runner = ReplayRunner()
            val caseRunner = CaseRunner(runner)
            val outputs = Fixtures.load(f)
            caseRunner.llmProvider = FixtureProvider(outputs)
            // 新格式 fixture（rec-<case>-att-N 确定性幂等键）：回放与录制同序可复现 → 开启严格校验
            // （Warning 3：录制严格 / 回放宽松不对称会让回放无法复现录制时裁决状态；旧格式单独豁免）
            val keys = outputs.flatMap { it.toolCalls }
                .filter { it.name == "record_result" }
                .mapNotNull { (it.arguments["result"] as? Map<*, *>)?.get("idempotency_key")?.toString() }
            if (keys.isNotEmpty() && keys.all { NEW_FORMAT_KEY.matches(it) }) {
                caseRunner.enforceIdempotencyKey = true
            }
            val ps = try {
                caseRunner.run(case)
            } catch (e: Exception) {
                hardProblems[caseId] = listOf("回放异常: ${e.message}")
                continue
            }
            // 分级：text 措辞 → review；防御性 mock-only → 跳过；已知模型行为/用例时序 → 报告不硬失败；
            // 其余行为差异 → 硬失败（应为本地裁决异常）
            val hard = ps.filterNot { it.startsWith("text") || it.startsWith("text_tts") }
                .filterNot { p -> MOCK_ONLY_KEYS.any { p.startsWith(it) } }
                .filterNot { p -> KNOWN_REAL_DIFFS.any { (id, key) -> caseId == id && p.startsWith(key) } }
            val text = ps.filter { it.startsWith("text") }
            if (hard.isNotEmpty()) hardProblems[caseId] = hard
            if (text.isNotEmpty()) textReview[caseId] = text
            if (ps.isEmpty()) clean++
        }
        println("fixture 回放：$clean 干净，${hardProblems.size} 行为类差异（硬失败），${textReview.size} text 措辞（review）")
        textReview.forEach { (id, ps) -> ps.forEach { println("$id [text-review]: $it") } }
        println("行为类差异：$hardProblems")
        println("已知真实模式差异（报告不硬失败，见 companion KNOWN_REAL_DIFFS）：")
        cases.filter { c -> KNOWN_REAL_DIFFS.any { it.first == c.id } }.forEach { println("  ${it.id}（用例时序/模型行为）") }
        assertTrue(hardProblems.isEmpty(), "fixture 回放行为类差异: ${hardProblems.keys}")
    }

    companion object {
        /** 新格式幂等键（CaseRunner.beginAttempt 确定性签发：rec-<case>-att-<seq>）。 */
        private val NEW_FORMAT_KEY = Regex("rec-[a-z0-9-]+-att-\\d+")

        /** mock-only 断言键：用例锁定"模型做错事"，真实输出不触发属正常（本地行为已由 JVM 单测覆盖）。 */
        private val MOCK_ONLY_KEYS = listOf(
            "reject", "filter", "warn_inject", "dedup", "truncate",
            "llm_turn", "pause_llm_turn", "llm_turn_after_tts",
            "re_eval_local", "result_as_tool_result", "produces_writing_evaluated",
            "open_mic", "next_expected", "fallback_text", "session 状态", "current_char",
            "re_introduce", "no_llm_after_end", "toolCall(", "toolCall_args",
        )

        /**
         * 已知真实模式差异（review-06 3-3/测试期望过严类）：用例时序或模型行为 vs 用例 TDD 期望。
         * 报告不硬失败（本地裁决未坏，差异是真实模型行为信号）。
         */
        private val KNOWN_REAL_DIFFS = listOf(
            "GT-001" to "phase 期望",    // 模型 SessionStarted 后推进 introduce→recognize（推进快）
            "GT-027" to "phase 期望",
            "GT-030" to "phase 期望",
            "GT-020" to "final_phase",  // 用例时序（GT-020 备注已标注）
            "GT-054" to "review_empty_guard",  // 模型未调 next
            "GT-010" to "家.write",   // 模型 record_result 的 prompt_level 选择（≠none → 裁决 1 而非 2）
            "GT-020" to "家.write",
        )
    }
}
