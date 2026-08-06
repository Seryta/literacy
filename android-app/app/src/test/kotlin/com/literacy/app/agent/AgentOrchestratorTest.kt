package com.literacy.app.agent

import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.ToolCall
import com.literacy.agent.model.VoiceIntent
import com.literacy.agent.provider.ScriptedLlmProvider
import com.literacy.agent.store.InMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** 验收 P1-2 + W2：缺 exercise_id 时 fallback ID 稳定（不随洗牌/对象身份漂移），作答后一次性消费（旧题不复活）。 */
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
        // 第 2 轮：同字同选项再出题（未作答）——fallback ID 必须稳定（不随洗牌漂移；
        // 模型重发同一题是新 ToolCall 对象，不得按对象序列号换新 ID）
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)
        assertEquals("同题 fallback ID 必须稳定（不随洗牌/对象身份漂移）", firstId, orchestrator.lastExerciseId)
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

    @Test
    fun `复习 ASSESS 书写绑定 assess phase`() {
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(List(3) { LlmOutput("继续", emptyList()) }),
            store = InMemoryStore(),
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.jumpToReview()
        orchestrator.advanceReview() // recall -> assess

        orchestrator.strokeFinished(
            1,
            listOf(
                com.literacy.agent.model.StrokePoint(0f, 0f),
                com.literacy.agent.model.StrokePoint(1f, 1f),
            ),
        )

        assertEquals("assess", orchestrator.state.attempt?.phase)
    }

    // ---- 最终验收 批次A：复习书写真实链路（P1-1）----

    /** 假字库：2 画参考骨架（JVM 可测整字评估；真字库是 android asset，JVM 单测不可用）。 */
    private val fakeHanzi = object : com.literacy.agent.data.HanziDataSource {
        override fun find(char: String): com.literacy.agent.data.HanziInfo? = null
        override fun strokeCount(char: String): Int = 2
        override fun referenceStrokes(char: String): List<List<com.literacy.agent.model.StrokePoint>>? = listOf(
            listOf(com.literacy.agent.model.StrokePoint(0f, 100f), com.literacy.agent.model.StrokePoint(100f, 0f)),
            listOf(com.literacy.agent.model.StrokePoint(0f, 0f), com.literacy.agent.model.StrokePoint(100f, 100f)),
        )
    }

    @Test
    fun `复习 ASSESS 听写整字提交绑定 assess phase`() {
        // ViewModel.onCompleteWriting → orchestrator.completeIndependentWrite 的真实链路
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(List(3) { LlmOutput("继续", emptyList()) }),
            store = InMemoryStore(),
            hanzi = fakeHanzi,
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.jumpToReview()
        orchestrator.advanceReview() // recall -> assess
        orchestrator.completeIndependentWrite(listOf(
            listOf(com.literacy.agent.model.StrokePoint(0f, 100f), com.literacy.agent.model.StrokePoint(100f, 0f)),
            listOf(com.literacy.agent.model.StrokePoint(0f, 0f), com.literacy.agent.model.StrokePoint(100f, 100f)),
        ))
        assertEquals("assess", orchestrator.state.attempt?.phase)
        assertEquals(com.literacy.agent.model.Dimension.WRITE, orchestrator.state.attempt?.dimension)
    }

    @Test
    fun `复习 REINFORCE 逐笔绑定 reinforce phase`() {
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(List(5) { LlmOutput("继续", emptyList()) }),
            store = InMemoryStore(),
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.jumpToReview()
        orchestrator.advanceReview()   // recall -> assess
        orchestrator.button("家")       // ASSESS 判题（本地证据，放行 REINFORCE 门禁）
        orchestrator.advanceReview()   // assess -> reinforce
        orchestrator.strokeFinished(
            1,
            listOf(
                com.literacy.agent.model.StrokePoint(0f, 0f),
                com.literacy.agent.model.StrokePoint(1f, 1f),
            ),
        )
        assertEquals("reinforce", orchestrator.state.attempt?.phase)
    }

    // ---- 最终验收 批次A：两字复习旅程（目标字/promptLevel/题目作废）----

    @Test
    fun `两字复习旅程 目标字 promptLevel 换字作废`() {
        val store = InMemoryStore()
        store.upsertCharacter(com.literacy.agent.model.CharacterRecord("家", currentPromptLevel = 4))
        store.upsertCharacter(com.literacy.agent.model.CharacterRecord("的", currentPromptLevel = 1))
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(List(10) { LlmOutput("继续", emptyList()) }),
            store = store,
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.jumpToReview()
        assertEquals("家", orchestrator.state.char)
        assertEquals("复习字必须读自己的 currentPromptLevel（不沿用学习轮等级）", 4, orchestrator.state.promptLevel)
        // RECALL → ASSESS → 判题（本地证据）→ REINFORCE → NEXT
        orchestrator.advanceReview()   // recall -> assess
        assertEquals("assess", orchestrator.state.reviewStage?.name?.lowercase())
        orchestrator.button("家")       // 判题正确（本地证据）
        assertFalse("判题后题目已一次性消费", orchestrator.currentExercise != null)
        orchestrator.advanceReview()   // assess -> reinforce
        orchestrator.advanceReview()   // reinforce -> next
        // NEXT → 下一复习字
        orchestrator.button("next")
        assertEquals("换复习字目标字正确", "的", orchestrator.state.char)
        assertEquals("第二字读自己的等级（不沿用家的 L4）", 1, orchestrator.state.promptLevel)
    }

    // ---- 最终验收 批次B：题目状态覆盖逻辑新轮次（P2-7）----

    @Test
    fun `同字 complete_character 新轮次清题（char 不变也作废旧题）`() {
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(listOf(
                showOptionsScript(),   // 第 1 轮出题（旧轮）
                LlmOutput("完成", listOf(ToolCall("complete_character"))),   // 同字完成（无下一字→重复当前字）
            )),
            store = InMemoryStore(),
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)   // 第 1 轮出题
        assertNotNull("第 1 轮必须出题", orchestrator.currentExercise)
        // 同字 complete_character（char 不变）：逻辑新轮次，旧题必须作废
        orchestrator.jumpTo(com.literacy.agent.model.Phase.DECIDE)
        orchestrator.characterCompleted()
        org.junit.Assert.assertNull("同字新轮次旧题必须作废（char 不变也要清）", orchestrator.currentExercise)
        org.junit.Assert.assertNull(orchestrator.lastExerciseId)
        assertEquals("无下一字时重复当前字", "家", orchestrator.state.char)
    }

    @Test
    fun `本轮无新 show_options 保留题目快照不重新洗牌`() {
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(listOf(
                showOptionsScript(),   // 第 1 轮出题
                LlmOutput("继续", emptyList()),   // 第 2 轮无新出题
            )),
            store = InMemoryStore(),
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)   // 第 1 轮出题
        val first = orchestrator.currentExercise
        assertNotNull(first)
        val order = first!!.options
        // 第 2 轮：模型没出新题——旧题快照必须保留（不每轮重扫历史重新洗牌，按钮不跳位）
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)
        assertEquals("无新 show_options 时题目快照保留", order, orchestrator.currentExercise?.options)
        assertEquals(first.exerciseId, orchestrator.currentExercise?.exerciseId)
    }

    @Test
    fun `同轮 complete_character 与下一字 show_options 不丢新题（W3）`() {
        // 模型在同一响应里 complete_character（清 recentUiTools）+ 下一字 show_options：
        // 按旧尺寸 drop 会把同轮新题误丢（永不渲染）；快照 + 按身份 dropWhile 必须保留新题
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(listOf(
                showOptionsScript(),   // 第 1 轮旧字出题（旧题在 recentUiTools）
                LlmOutput("完成并出题", listOf(
                    ToolCall("complete_character"),
                    ToolCall("show_options", mapOf("exercise_id" to null)),
                )),   // 第 2 轮：同响应完成 + 下一字新题
                LlmOutput("继续", emptyList()),   // complete_character 后的递归决策轮
            )),
            store = InMemoryStore(),
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)   // 第 1 轮出题（旧题）
        assertNotNull("第 1 轮必须出题", orchestrator.currentExercise)
        orchestrator.jumpTo(com.literacy.agent.model.Phase.DECIDE)
        orchestrator.characterCompleted()   // 第 2 轮：complete_character + 新字 show_options
        assertNotNull("同轮 complete_character 后新字 show_options 必须渲染（不得被尺寸 drop 误丢）", orchestrator.currentExercise)
        assertTrue("新题 ID 必须是 fallback（无 exercise_id）", orchestrator.lastExerciseId?.startsWith("fallback-") == true)
    }

    @Test
    fun `空白 exercise_id 按缺失处理走 fallback`() {
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(listOf(
                LlmOutput("出题", listOf(ToolCall("show_options", mapOf("exercise_id" to "")))),
                LlmOutput("出题2", listOf(ToolCall("show_options", mapOf("exercise_id" to "")))),
                LlmOutput("出题3", listOf(ToolCall("show_options", mapOf("exercise_id" to "")))),
            )),
            store = InMemoryStore(),
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)
        val id1 = orchestrator.lastExerciseId
        assertNotNull("空白 ID 必须有 fallback 题", id1)
        assertTrue("空白 exercise_id 必须按缺失走 fallback（不以空串当合法 ID）", id1!!.startsWith("fallback-"))
        assertNotNull(orchestrator.currentExercise)
        // 同字同选项未作答的空 ID 题：fallback ID 稳定（同题同 ID，不随对象身份漂移）且不被屏蔽
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)
        assertEquals("空 ID 同题未作答时 ID 稳定（不随 ToolCall 对象身份漂移）", id1, orchestrator.lastExerciseId)
        assertNotNull("空 ID 同题未作答不得被屏蔽（不以空串永久屏蔽）", orchestrator.currentExercise)
        // 作答后：同题（同字同选项）不得复活——空 ID 题同样一次性消费
        orchestrator.button(orchestrator.currentExercise!!.options.first())
        orchestrator.userSpoke("嗯", forcedIntent = VoiceIntent.OTHER)
        assertNull("空 ID 题作答后不得复活", orchestrator.currentExercise)
    }

    @Test
    fun `复习阶段推进到 NEXT 作废 REINFORCE 题目`() {
        val orchestrator = AgentOrchestrator(
            provider = ScriptedLlmProvider(listOf(
                LlmOutput("进入复习", emptyList()),   // 1: jumpToReview（RECALL，show_options 被本地拒）
                showOptionsScript(),                  // 2: advanceReview → ASSESS 出题
                LlmOutput("判题", emptyList()),       // 3: button 判题（消费 ASSESS 题）
                showOptionsScript(),                  // 4: advanceReview → REINFORCE 出题（允许 show_options）
                LlmOutput("继续", emptyList()),       // 5: advanceReview → NEXT（阶段边界清题）
            )),
            store = InMemoryStore(),
        )
        orchestrator.startSession("家", greet = false)
        orchestrator.jumpToReview()
        orchestrator.advanceReview()   // recall -> assess
        assertNotNull("ASSESS 出题", orchestrator.currentExercise)
        orchestrator.button(orchestrator.currentExercise!!.options.first())
        assertNull("判题后题一次性消费", orchestrator.currentExercise)
        orchestrator.advanceReview()   // assess -> reinforce
        assertNotNull("REINFORCE 允许 show_options 再出题", orchestrator.currentExercise)
        orchestrator.advanceReview()   // reinforce -> next
        assertNull("复习阶段边界旧题必须作废（不跨阶段残留）", orchestrator.currentExercise)
        org.junit.Assert.assertNull(orchestrator.lastExerciseId)
    }
}
