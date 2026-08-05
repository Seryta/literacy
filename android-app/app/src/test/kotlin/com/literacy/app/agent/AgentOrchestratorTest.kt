package com.literacy.app.agent

import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.ToolCall
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.provider.ScriptedLlmProvider
import com.literacy.agent.store.InMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AgentOrchestrator 本地选择题逻辑（JVM 单测）。
 * android.util.Log 等 android.* stub 由 build.gradle 的 testOptions.isReturnDefaultValues 兜底。
 */
class AgentOrchestratorTest {

    /** canonical show_options（无 exercise_id——模型常省略，走本地 fallback ID）。 */
    private fun showOptionsScript() = LlmOutput("请选择", listOf(
        ToolCall("show_options", mapOf("exercise_id" to null)),
    ))

    /** 验收 P1-2：缺 exercise_id 时 fallback ID 稳定（不随洗牌漂移），作答后一次性消费（旧题不复活）。 */
    @Test
    fun `缺 exercise_id 的题目 fallback ID 稳定且作答后不复活`() {
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(listOf(
                showOptionsScript(),   // 第 1 轮出题（无 exId）
                showOptionsScript(),   // 第 2 轮出题（未作答——同题应得相同 fallback ID）
                showOptionsScript(),   // 第 3 轮出题（已作答——不得复活）
            )),
            store = InMemoryStore(),
        )
        orchestrator.startSession("家", greet = false)
        // 第 1 轮：模型出题（无 exercise_id）→ 本地 fallback ID + 选项
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)
        val firstId = orchestrator.lastExerciseId
        assertNotNull("fallback 题必须有稳定 ID", firstId)
        val options = orchestrator.currentExercise?.options
        assertNotNull("fallback 题必须生成本地选项", options)
        // 第 2 轮：同字同选项再出题（未作答）——fallback ID 必须稳定（不随洗牌漂移）
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)
        assertEquals("同题 fallback ID 必须稳定（不随洗牌漂移）", firstId, orchestrator.lastExerciseId)
        // 作答：点击选项 → 一次性消费（effectiveId 进 consumedExerciseIds）
        orchestrator.button(options!!.first())
        // 第 3 轮：模型又出同一题 → 已消费，不得复活
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)
        assertNull("已作答的 fallback 题不得复活", orchestrator.currentExercise)
    }

    /** 验收 P1-2 边界：换字后新题 fallback ID 变化（消费记录不误伤新字题目）。 */
    @Test
    fun `换字后新题 fallback ID 变化，消费记录不误伤`() {
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(listOf(
                showOptionsScript(),   // 旧字出题
                showOptionsScript(),   // 新字出题
            )),
            store = InMemoryStore(),
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)
        val oldId = orchestrator.lastExerciseId
        // 换字：REQUEST_NEW_CHAR（换字后旧题状态清理；本轮 LLM 即按新字重新出题）
        orchestrator.userSpoke("我想学'国'字", forcedIntent = VoiceIntent.REQUEST_NEW_CHAR)
        val newId = orchestrator.lastExerciseId
        assertNotNull("换字后仍能出题", newId)
        // 旧字 ID（含旧字+旧选项）与新字 ID 必须不同——消费记录不误伤新题
        org.junit.Assert.assertNotEquals("换字后 fallback ID 必须变化（不误伤新题）", oldId, newId)
    }
}
