package com.literacy.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color

/**
 * 语音输入：悬浮球 + 系统 SpeechRecognizer（STT）。
 * 链路：悬浮球点击 → 录音 → 系统识别 → 转写文本 → 按当前页面分发。
 * 目标用户不识字：语音是主要交互方式（DESIGN.md §交互模式：主要通过语音与 AI 交互）。
 */

/** STT 生命周期封装（系统 SpeechRecognizer，中文）。 */
class SpeechInputManager(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var onResult: ((SpeechOutcome) -> Unit)? = null
    private var onPartial: ((String) -> Unit)? = null   // 实时转写（边说边显示）
    private var autoRestart = false          // 连续监听模式（onboarding 自动听）
    private var cancelled = false

    sealed interface SpeechOutcome {
        data class Text(val text: String) : SpeechOutcome
        data class Error(val message: String) : SpeechOutcome
    }

    /**
     * 开始录音识别；返回是否成功启动（无语音服务时 false）。
     * @param autoRestart 连续监听：静默重启循环（超时/无匹配不打扰用户），
     *        适用于引导等需要"进来就自动听"的场景；硬错误（无权限/无音频）停止循环并回调 Error。
     * @param onPartial 实时转写回调（onPartialResults）：用户边说边显示；
     *        也可用于打断——检测到用户开口即回调（UI 层可停 TTS）。
     */
    fun start(
        callback: (SpeechOutcome) -> Unit,
        autoRestart: Boolean = false,
        onPartial: ((String) -> Unit)? = null,
    ): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return false
        this.autoRestart = autoRestart
        this.cancelled = false
        onResult = callback
        this.onPartial = onPartial
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                // 用户开口：实时转写开始（可用于打断 TTS）
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                when {
                    // 连续监听：用户没说话/没听清 → 静默重启继续听（不打扰）
                    autoRestart && !cancelled && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) -> {
                        android.os.Handler(appContext.mainLooper).postDelayed({ restart() }, 400)
                    }
                    // 硬错误：停止循环并上报
                    else -> {
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一遍"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有说话，请对着话筒说"
                            SpeechRecognizer.ERROR_AUDIO -> "麦克风没有声音，检查权限"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务需要网络"
                            else -> "语音识别失败，请重试"
                        }
                        onResult?.invoke(SpeechOutcome.Error(msg))
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                if (text != null) {
                    onPartial?.invoke(text)   // 最终结果也刷新字幕
                    onResult?.invoke(SpeechOutcome.Text(text))
                    // 连续监听：识别完稍停继续听（用户可能继续说）
                    if (autoRestart && !cancelled) {
                        android.os.Handler(appContext.mainLooper).postDelayed({ restart() }, 500)
                    }
                } else {
                    onError(SpeechRecognizer.ERROR_NO_MATCH)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                if (text != null) onPartial?.invoke(text)   // 边说边显示
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
        }
        // 无 RECORD_AUDIO 权限时 startListening 可能抛 SecurityException（跳引导未授权场景）——不能崩
        try {
            r.startListening(intent)
        } catch (e: SecurityException) {
            onResult?.invoke(SpeechOutcome.Error("没有麦克风权限"))
            return false
        } catch (e: Exception) {
            onResult?.invoke(SpeechOutcome.Error("语音识别启动失败，请重试"))
            return false
        }
        return true
    }

    private fun restart() {
        if (cancelled || !autoRestart) return
        try { recognizer?.startListening(buildIntent()) } catch (e: Exception) {
            onResult?.invoke(SpeechOutcome.Error("语音识别异常，请点一下宠物再说话"))
        }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
    }

    fun cancel() {
        cancelled = true
        autoRestart = false
        recognizer?.cancel()
    }

    fun destroy() {
        cancelled = true
        recognizer?.destroy()
        recognizer = null
    }
}

/** 首页语音命令解析：语音转写 → 界面导航动作（学习页内的教学意图由 agent-core IntentResolver 处理）。 */
object VoiceCommandParser {
    sealed interface Action {
        data class LearnChar(val c: String) : Action
        data object OpenSettings : Action
        data object OpenProfile : Action
        data object OpenNameLearning : Action     // 学我的名字（默认路径）
        data object OpenSearchChar : Action       // 想学一个字（搜索/输入卡片）
        data object OpenReview : Action           // 复习
        data object Unknown : Action
    }

    fun parse(text: String): Action {
        val t = text.trim()
        return when {
            t.contains("设置") -> Action.OpenSettings
            // 名字学习优先于建档（"学我的名字"不能被"名字"误判为建档）
            t.contains("学我的名字") || t.contains("学名字") || t.contains("我的名字") || t.contains("学我的字") -> Action.OpenNameLearning
            t.contains("建档") || t.contains("教我写") -> Action.OpenProfile
            t.contains("复习") || t.contains("温习") -> Action.OpenReview
            t.contains("想学一个字") || t.contains("学个字") || t.contains("要学字") || t.contains("想学字") || t.contains("学一个") -> Action.OpenSearchChar
            // 学X字 / 我想学X / 复习X —— 提取目标汉字
            else -> {
                // 否定/结束句不触发学字导航（"我不学了"取"了"字会误导航）
                if (t.contains("不") || t.contains("别") || t.contains("没")) return Action.Unknown
                val char = Regex("学['\"]?([\\u4e00-\\u9fa5])['\"]?字").find(t)?.groupValues?.get(1)
                    ?: Regex("学([\\u4e00-\\u9fa5])").find(t)?.groupValues?.get(1)
                    ?: t.filter { it.isLetter() && it.code in 0x4e00..0x9fff }.lastOrNull()?.toString()
                if (char != null) Action.LearnChar(char) else Action.Unknown
            }
        }
    }

    /** 学习页操作命令：语音 → 按钮动作（帮助/跳过/暂停/继续/结束/复习控制）。 */
    fun learnCommand(text: String): String? {
        val t = text.trim()
        return when {
            t.contains("帮助") || t.contains("提示") -> "help"
            t.contains("跳过") || t.contains("换一个") -> "skip"
            t.contains("暂停") || t.contains("歇一会") -> "pause"
            t.contains("继续") || t.contains("接着") -> "resume"
            t.contains("结束") || t.contains("退出") || t.contains("不学了") || t.contains("不练了") -> "end"
            t.contains("下一阶段") || t.contains("下一题") || t.contains("下一步") -> "review_stage"
            t.contains("下一复习") || t.contains("下一个字") || t.contains("下一字") || t.contains("换一个字") -> "next"
            else -> null
        }
    }
}

/** 悬浮语音球：右下角常驻（所有页面），点击开始/停止录音。 */
// 注：已由 MascotBall（悬浮吉祥物）替代，此文件仅保留 STT 封装与语音命令解析。
