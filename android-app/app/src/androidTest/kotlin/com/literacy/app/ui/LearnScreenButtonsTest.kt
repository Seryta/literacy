package com.literacy.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.literacy.agent.model.LlmOutput
import com.literacy.agent.model.ToolCall
import com.literacy.agent.provider.ScriptedLlmProvider
import com.literacy.agent.store.InMemoryStore
import com.literacy.app.data.AssetHanziDataSource
import com.literacy.app.settings.AppSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose 显隐测试（轻量）：认对/认错按钮只在 recognize 阶段显示。
 *
 * UI 状态注入：LearnViewModel 接受 provider 参数，测试注入 ScriptedLlmProvider
 * （agent-core 脚本化 mock）——advance_phase 工具调用驱动阶段推进（生产真实模式推进靠模型工具，
 * autoAdvance=false）。复习模式 allowed_actions 不含 advance_phase，推进被本地拒绝 → 按钮不显示。
 *
 * 阶段序注意（review 反馈 Warning 4 修正）：真实阶段序为 INTRODUCE→RECOGNIZE→DEMONSTRATE
 * （Phase.SEQUENCE / PhaseMachine.advanceStep），4b54fde 提交说明误记为
 * INTRODUCE→DEMONSTRATE→RECOGNIZE。故「中间阶段按钮不可见」负例落在 RECOGNIZE 之后的
 * DEMONSTRATE：先确认 RECOGNIZE 显示（正向基线），认对推进到 DEMONSTRATE 后断言按钮消失。
 */
@RunWith(AndroidJUnit4::class)
class LearnScreenButtonsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 首轮 advance_phase（INTRODUCE→RECOGNIZE；RECOGNIZE 需事件判定成功条件，第 2 次会空转被拒），后续空回复。 */
    private val script = listOf(
        LlmOutput("开始学习", listOf(ToolCall("advance_phase"))),
        LlmOutput("好的", emptyList()),
    )

    /** 两轮推进：第 1 轮 advance_phase → RECOGNIZE；认对（本地判定成功）后第 2 轮 advance_phase → DEMONSTRATE。 */
    private val stepScript = listOf(
        LlmOutput("开始学习", listOf(ToolCall("advance_phase"))),
        LlmOutput("认对了，继续", listOf(ToolCall("advance_phase"))),
        LlmOutput("好的", emptyList()),
    )

    private fun newViewModel(providerScript: List<LlmOutput> = script) = LearnViewModel(
        AppSettings(context),
        AssetHanziDataSource(context),
        InMemoryStore(),
        provider = ScriptedLlmProvider(providerScript),
    )

    /** 超时分支打印 vm.ui / orchestrator.state 快照（review 反馈 Suggestion 2：不再盲猜卡在哪个阶段）。 */
    private fun waitUntil(vm: LearnViewModel, timeoutMillis: Long = 10_000, label: String, condition: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMillis = timeoutMillis) { condition() }
        } catch (e: ComposeTimeoutException) {
            println("waitUntil 超时[$label]: ui=$vm.ui orchestratorState=${vm.debugOrchestratorState}")
            throw e
        }
    }

    @Test
    fun recognitionButtons_visibleOnlyInRecognizePhase() {
        val vm = newViewModel()
        rule.setContent { LearnScreen(vm, onBack = {}) }

        // 初始（无进行中会话，phase 为空）：不显示
        rule.onNodeWithText("✓认对").assertDoesNotExist()
        rule.onNodeWithText("✗认错").assertDoesNotExist()

        // 开始学习 → advance_phase → recognize 阶段
        vm.startLearning("家")
        waitUntil(vm, label = "进入 recognize") {
            rule.onAllNodesWithText("✓认对").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("✓认对").assertIsDisplayed()
        rule.onNodeWithText("✗认错").assertIsDisplayed()

        // 认对后阶段仍为 recognize（模型未再推进）——按钮保持可见
        rule.onNodeWithText("✓认对").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("✓认对").assertIsDisplayed()
    }

    @Test
    fun recognitionButtons_hiddenInIntermediatePhase_demonstrate() {
        val vm = newViewModel(stepScript)
        rule.setContent { LearnScreen(vm, onBack = {}) }

        // 正向基线：第 1 轮推进到 RECOGNIZE，按钮显示
        vm.startLearning("家")
        waitUntil(vm, label = "进入 recognize") { vm.ui.phase == "recognize" }
        rule.onNodeWithText("✓认对").assertIsDisplayed()

        // 负例：认对（本地判定 RECOGNIZE 成功）→ 第 2 轮 advance_phase → DEMONSTRATE（中间阶段）
        vm.onSimulatedRecognition(true)
        waitUntil(vm, label = "进入 demonstrate") { vm.ui.phase == "demonstrate" }
        rule.waitForIdle()
        // 锁定中间阶段按钮不可见（Rule 6 意图：按钮只在 recognize 显示，离开即消失）
        rule.onNodeWithText("✓认对").assertDoesNotExist()
        rule.onNodeWithText("✗认错").assertDoesNotExist()
    }

    @Test
    fun recognitionButtons_absentInReviewMode() {
        val vm = newViewModel()
        rule.setContent { LearnScreen(vm, onBack = {}) }

        // 复习直达模式：advance_phase 不在复习 allowed_actions → 阶段推进被本地拒绝，
        // 无论脚本返回什么工具调用都不会到 recognize
        vm.startLearning("家:review")
        waitUntil(vm, label = "进入复习模式") { vm.ui.mode == "review" }
        rule.waitForIdle()
        rule.onNodeWithText("✓认对").assertDoesNotExist()
        rule.onNodeWithText("✗认错").assertDoesNotExist()
        rule.onNode(hasText("复习模式", substring = true)).assertExists()   // 确认确实在复习界面
    }
}
