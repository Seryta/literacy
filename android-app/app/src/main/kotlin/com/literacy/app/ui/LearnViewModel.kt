package com.literacy.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.literacy.agent.data.HanziDataSource
import com.literacy.agent.model.Phase
import com.literacy.agent.provider.HttpLlmProvider
import com.literacy.agent.provider.OkHttpTransport
import com.literacy.app.agent.AgentOrchestrator
import com.literacy.app.settings.AppSettings
import java.util.Locale

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/** 学习界面状态（Compose 可观察）。 */
data class LearnUiState(
    val phase: String = "",
    val char: String = "",
    val pinyin: String = "",
    val text: String = "",
    val decomposition: String = "",
    val strokeCount: Int = 0,
    val promptLevel: Int = 3,
    val listening: Boolean = false,     // 等待语音输入（listen 预约）
    val sessionEnded: Boolean = false,
    val providerFailed: Boolean = false, // LLM 不可用（key 未配/失败）
    val loading: Boolean = false,        // LLM 思考中（网络调用进行时）
    val paused: Boolean = false,         // 暂停中（本地，不调 LLM）
    val mode: String = "learning",       // learning / review
    val reviewStage: String? = null,      // 复习阶段 recall/assess/reinforce/next
    val uiTools: List<com.literacy.agent.model.ToolCall> = emptyList(),   // P1-5：模型声明的 UI 工具（含参数）
    val exercise: com.literacy.app.agent.AgentOrchestrator.LocalExercise? = null,   // review-11 P1-1.4：本地选择题真值（渲染源）
)

/** 学习会话 ViewModel：驱动 AgentOrchestrator，暴露 UI 状态。
 *  网络调用（LLM）在 IO 线程执行，UI 状态在主线程刷新——避免主线程阻塞。 */
class LearnViewModel(
    settings: AppSettings,
    hanzi: HanziDataSource,
    store: com.literacy.agent.store.LearningStore,
    provider: com.literacy.agent.provider.LlmProvider? = null,   // 测试注入（Compose UI 测试用 ScriptedLlmProvider 驱动阶段）
) : ViewModel() {

    // review-09 P1-13：持有传输层引用——离页取消在途 OkHttp Call（Job.cancel 不够，同步 execute 需 cancel Call）
    private val httpTransport = OkHttpTransport()
    private val orchestrator = AgentOrchestrator(
        provider = provider ?: HttpLlmProvider(
            httpTransport,
            HttpLlmProvider.ProviderConfig(
                baseUrl = settings.baseUrl,
                apiKey = settings.apiKey,
                model = settings.model,
            ),
        ),
        hanzi = hanzi,
        store = store,
        displayName = settings.displayName,   // P1-2：称呼注入
    )

    var ui by mutableStateOf(LearnUiState())
        private set

    /** 调试/诊断快照（androidTest waitUntil 超时定位用，只读；生产 UI 不依赖）。 */
    val debugOrchestratorState: com.literacy.agent.model.LessonState
        get() = orchestrator.state

    /** P1-14：LLM 在途标志——loading 时拒绝新操作（防并发协程乱序/跨阶段串写）。 */
    private var busy = false

    /** 统一提交：busy 时拒绝；IO 执行后复位并刷新。 */
    private var lastUiToolsSize: Int = 0
    private var currentJob: kotlinx.coroutines.Job? = null   // review-10 P1-11：离页取消在途请求

    private fun submit(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        ui = ui.copy(loading = true)
        currentJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                busy = false
                refreshUi()
            }
        }
    }

    override fun onCleared() {
        // review-09 P1-13：VM 销毁同样取消在途请求（Activity 级 VM 可能不随 composable 销毁）
        orchestrator.cancelInFlight()
        httpTransport.cancelActive()
        speakJob?.cancel()   // 残余修复：离页取消离线 TTS 协程（不重播旧教学语/不并发）
        releaseTts()
        super.onCleared()
    }

    /** 权威跟写完成笔数（P2-2：揭示用本地裁决值，不用 UI 侧计数——UI 画失败笔也 +1 会超前）。 */
    val completedStrokes: Int get() = orchestrator.completedStrokes

    /** clear_grid 触发信号（review-09 P1-5 收尾：模型声明清空笔画时递增，米字格据此重置轨迹）。 */
    var clearGridSignal: Int = 0
        private set

    /** 当前字库 SVG 笔画路径（米字格渲染）。 */
    val orchestratorStrokes: List<String>
        get() = orchestrator.currentCharStrokes

    private var tts: TextToSpeech? = null
    private var speakJob: kotlinx.coroutines.Job? = null   // 离线 TTS 协程（串行 + 离页取消）
    private var ttsReady = false

    /** 绑定 TTS（Activity 创建时）。引擎异步就绪后补播当前教学语。 */
    fun bindTts(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                ttsReady = true
                // P2：TTS 播放完成 → runner onTtsCompleted（listen 预约开麦时机，§5）
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "teaching") orchestrator.onTtsCompleted()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
                speakCurrent()
            }
        }
    }

    /** review-10 P1-11：离页取消在途请求（网络调用最长 120s，不残留后台执行副作用）。
     *  review-09 P1-13：同时取消底层 OkHttp Call（Job.cancel 不中断同步 execute）。 */
    fun cancelInFlight() {
        orchestrator.cancelInFlight()
        httpTransport.cancelActive()
        speakJob?.cancel()   // 残余修复：离页取消在途离线 TTS
        speakJob = null
        currentJob?.cancel()
        currentJob = null
    }

    fun releaseTts() {
        com.literacy.app.ui.voice.VoiceHub.offline.stop()   // 残余修复：停离线 AudioTrack（释放资源）
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /** 停止当前朗读（用户开口打断教学语——实时对话）。 */
    fun stopTts() {
        try { tts?.stop() } catch (e: Exception) {}
    }

    /** 开始学习一个字（支持 "字:stage" 直达格式，开发模式用）。 */
    fun startLearning(char: String) {
        val parts = char.split(":")
        val c = parts[0]
        val stage = parts.getOrNull(1)
        submit {
            if (stage == "review") {
                orchestrator.startSession(c, greet = false)   // 复习：先进入复习模式再教学
                orchestrator.jumpToReview()
            } else {
                orchestrator.startSession(c)
                when (stage) {
                    "guided_write" -> orchestrator.jumpTo(Phase.GUIDED_WRITE)
                    "independent_write" -> orchestrator.jumpTo(Phase.INDEPENDENT_WRITE)
                    else -> {}
                }
            }
        }
    }

    /** 用户文字输入（第一版以文本框模拟语音；STT 接入后替换）。 */
    fun onUserInput(text: String) {
        if (ui.sessionEnded) return   // review-09 P1-12：session 结束后禁止再触发
        userInteracted()
        submit { orchestrator.userSpoke(text) }
    }

    /** 开发模式模拟认读（绕过中文输入限制；仅 debug 构建 UI 显示）。 */
    fun onSimulatedRecognition(correct: Boolean) {
        if (ui.sessionEnded) return   // review-09 P1-12
        userInteracted()
        val char = orchestrator.state.char ?: return
        val intent = if (correct) com.literacy.agent.model.VoiceIntent.RECOGNIZED else com.literacy.agent.model.VoiceIntent.WRONG
        submit { orchestrator.userSpoke(char, intent) }
    }

    /** 书写完成（米字格手势轨迹）。stroke 为当前笔序号（1-based）。 */
    fun onStrokeDrawn(path: List<Pair<Float, Float>>) {
        userInteracted()
        val pts = path.map { com.literacy.agent.model.StrokePoint(it.first, it.second) }
        if (orchestrator.state.phase == Phase.GUIDED_WRITE) {
            // P1-1：笔序 = 已完成笔画数 + 1（此前误传 promptLevel）
            val strokeIdx = orchestrator.completedStrokes + 1
            submit { orchestrator.strokeFinished(strokeIdx, pts) }
        }
        // 独立写等阶段：笔画由 UI 收集，点"完成书写"后统一评估（onCompleteWriting）
    }

    /** 独立写完成：提交全部笔画轨迹做综合评估（MASTERY-CRITERIA §4 掌握检测点）。
     *  P1-7：缺笔（strokeCount < 参考笔画数）由 completeIndependentWrite 判失败。 */
    fun onCompleteWriting(paths: List<List<Pair<Float, Float>>>) {
        if (paths.isEmpty()) return
        userInteracted()
        submit {
            val pts = paths.map { s -> s.map { com.literacy.agent.model.StrokePoint(it.first, it.second) } }
            orchestrator.completeIndependentWrite(pts)
        }
    }

    fun onButton(action: String) {
        userInteracted()
        if (action == "pause") {
            // review-10 P1-11：暂停本地立即执行（无网络调用）——在途回包副作用由
            // ReplayRunner 的 paused 检查拦截（P1-12 已实现）
            orchestrator.button(action)
            refreshUi()
            return
        }
        if (action == "end") {
            // review-11 P1-2：结束含同步网络（llmTurn → provider OkHttp execute）——
            // 不再主线程同步执行，走 submit 串行（IO 线程）；busy 时排队等待在途请求
            // 完成（不丢结束、不与在途并发改 runner，迟到回包由 ReplayRunner sessionEnded 检查兜底）
            submitEnd()
            return
        }
        submit {
            if (action == "review_stage") orchestrator.advanceReview()
            else orchestrator.button(action)
        }
    }

    /** review-11 P1-2：结束串行提交（IO 线程）。在途请求进行中时排队，完成后执行。 */
    private fun submitEnd() {
        val inflight = currentJob
        if (busy && inflight != null && inflight.isActive) {
            inflight.invokeOnCompletion { submitEnd() }   // 在途 finally 复位 busy 后再提交
            return
        }
        submit { orchestrator.button("end") }
    }

    private fun refreshUi() {
        val s = orchestrator.state
        val info = s.char?.let { orchestrator.hanziInfo(it) }
        ui = LearnUiState(
            phase = s.phase?.display ?: "",
            char = s.char ?: "",
            pinyin = info?.pinyin ?: "",
            text = orchestrator.displayText,   // review-10 P1-9：UI 用过滤文本（不再显示原文）
            decomposition = info?.decomposition ?: "",
            strokeCount = info?.strokeCount ?: 0,
            promptLevel = s.promptLevel,
            listening = orchestrator.micRequested || s.phase == Phase.RECOGNIZE,
            sessionEnded = orchestrator.sessionEnded,
            providerFailed = orchestrator.providerFailed,
            loading = false,
            paused = orchestrator.isPaused,
            mode = s.mode.name.lowercase(),
            reviewStage = s.reviewStage?.name?.lowercase(),
            uiTools = orchestrator.recentUiTools,   // P1-5：UI 工具渲染源
            exercise = orchestrator.currentExercise,   // review-11 P1-1.4：本地选择题真值（渲染源）
        )
        // review-09 P1-5 + review-10 P1-10：clear_grid 按「本次新增工具」消费（不再只看最后一个——
        // clear_grid → show_character 组合此前不触发）；新出现的 clear_grid 都触发清空
        val tools = orchestrator.recentUiTools
        if (tools.drop(lastUiToolsSize).any { it.name == "clear_grid" }) clearGridSignal++
        lastUiToolsSize = tools.size
        speakCurrent()
    }

    private fun speakCurrent() {
        // P1-13：TTS 用过滤后的文本（越界内容过滤后再朗读，见 AgentOrchestrator.ttsText）
        // review-09 P2-5：sessionEnded 也朗读（结束话术是最后教学输出——此前被 !sessionEnded 永远跳过）
        val text = orchestrator.ttsText ?: ui.text
        if (text.isNotBlank()) {
            // review-09 P2-4：教学 TTS 优先离线女声（与首页/引导一致），系统 TTS 兜底
            if (com.literacy.app.ui.voice.VoiceHub.offlineTtsReady) {
                // review-09 W3：VITS generate 是同步 CPU 重操作（长文本可卡主线程 1s+）→
                // 后台线程生成+播放；完成后切回主线程回调（onTtsCompleted 改 runner 状态）
                // 残余修复：串行化 + 页面生命周期——新 speak 取消旧任务；离页（onCleared）取消
                speakJob?.cancel()
                speakJob = viewModelScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try { com.literacy.app.ui.voice.VoiceHub.offline.speak(text) }
                        catch (e: Exception) { false }
                    }
                    if (ok) {
                        // 离线播放即视为教学语已播（App 端持续自动听，开麦时机近似即可）
                        orchestrator.onTtsCompleted()
                    } else {
                        speakSystem(text)   // 离线生成/播放失败 → 系统 TTS 兜底
                    }
                }
                return
            }
            speakSystem(text)
        }
    }

    /** 系统 TTS 兜底（离线不可用/生成失败时）。 */
    private fun speakSystem(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "teaching")
        }
    }

    /** review-09 P2-3：用户交互（按钮/手写/输入）重置全局沉默提示计时（VoiceFlowCoordinator）。 */
    var onUserInteraction: (() -> Unit)? = null

    private fun userInteracted() {
        onUserInteraction?.invoke()
    }
}
