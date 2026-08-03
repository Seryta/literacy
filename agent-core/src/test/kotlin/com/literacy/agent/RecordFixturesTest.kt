package com.literacy.agent

import com.literacy.agent.provider.Fixtures
import com.literacy.agent.provider.HttpLlmProvider
import com.literacy.agent.provider.OkHttpTransport
import com.literacy.agent.provider.ProviderConfigLoader
import com.literacy.agent.provider.RecorderProvider
import com.literacy.agent.replay.CaseLoader
import com.literacy.agent.replay.CaseRunner
import com.literacy.agent.replay.ReplayRunner
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真实 LLM 录制工具（golden file testing，按需运行不进 CI）。
 *
 * 前置：环境变量 DEEPSEEK_API_KEY（或按 provider-config.json 的 apiKeyEnv 配置），
 * 并把 provider-config.example.json 复制为 provider-config.json。
 *
 * 运行：docker run ... -e DEEPSEEK_API_KEY=xxx ... gradle test --tests "*RecordFixturesTest*"
 * 产物：fixtures/GT-xxx.json（真实模型输出，供 FixtureReplayTest 回放验证）
 */
class RecordFixturesTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    fun `录制真实 provider 输出为 fixture`() {
        val configFile = File("../provider-config.json")
        assertTrue(configFile.exists(), "需要 ../provider-config.json（复制 provider-config.example.json 并按需改）")
        val config = ProviderConfigLoader().load(configFile, "deepseek") ?: return
        val provider = HttpLlmProvider(OkHttpTransport(), config)

        // 可用环境变量 LITERACY_RECORD_CASES 限定用例子集（逗号分隔）；默认全量。
        // review-09 P2-10：空/空白视为 null（否则 "" split → [""]，过滤全部用例录 0 条假成功）
        val raw = System.getenv("LITERACY_RECORD_CASES")?.trim()
        val caseFilter = if (raw.isNullOrBlank()) null
        else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val cases = CaseLoader().loadFiles(File("../test-cases")).cases
            .filter { caseFilter == null || it.id in caseFilter }
        var total = 0
        for (case in cases) {
            val recorder = RecorderProvider(provider)
            val runner = ReplayRunner()
            val caseRunner = CaseRunner(runner)
            caseRunner.llmProvider = recorder
            caseRunner.enforceIdempotencyKey = true   // P0-1：录制路径签发幂等键并严格校验回传（与 App 一致）
            caseRunner.run(case)   // 本地裁决照常执行；录制 provider 输出
            if (recorder.recorded.isNotEmpty()) {
                Fixtures.save(File("../fixtures/${case.id}.json"), recorder.recorded)
                total += recorder.recorded.size
            }
            println("已录制 ${case.id}（${recorder.recorded.size} 条）")
        }
        println("录制完成：$total 条 LLM 输出 → fixtures/")
    }
}
