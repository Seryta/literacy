package com.literacy.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import com.literacy.app.ui.voice.VoiceHub
import java.util.Locale

/**
 * 轻量 TTS 封装（引导机器人/点读用）。
 * 优先级：离线中文女声（sherpa-onnx）→ 系统 TTS 兜底。
 * 关键：引擎初始化中（重开 App 加载模型需 1-5s）speak 排队补读，
 * 不立即走系统（系统 TTS 可能无声，导致"重开没声音"）。
 */
class LocalTts(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // 离线 VITS generate 是同步 CPU 重操作——LocalTts.speak 常被主线程调用
    // （Compose 点击/引导播报），离线路径必须后台生成，不在主线程做 generate。
    // 单线程串行：后进的朗读自然排在后。
    // 打断语义：speak 取播放代次（nextSpeakGeneration）——用户开口 stop() 调
    // cancelSpeak 作废代次，排队/生成中的任务播放前校验失败不播（旧朗读让位给用户）
    private val offlineExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                tts?.setSpeechRate(0.85f)
                ready = true
                pending?.let { doSpeak(it); pending = null }
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (VoiceHub.offlineTtsReady) {
            // 后台线程生成+播放（主线程不做 native generate）。
            // 播放代次：打断（stop→cancelSpeak）或新朗读会使代次过期，
            // 排队/生成中任务不播放；被取代的任务不得走系统兜底重播旧文本
            val gen = VoiceHub.offline.nextSpeakGeneration()
            offlineExecutor.execute {
                val ok = try { VoiceHub.offline.speak(text, gen) } catch (e: Exception) { false }
                // 仅真实失败（代次未变）回主线程走系统兜底；被打断/取代则不重播
                if (!ok && gen == VoiceHub.offline.currentSpeakGeneration()) {
                    mainHandler.post { doSpeak(text) }
                }
            }
            return
        }
        if (VoiceHub.initInProgress) {
            // 离线引擎加载中：排队，等就绪补读（避免走系统无声）
            pending = text
            retryWhenReady()
            return
        }
        doSpeak(text)
    }

    /** 轮询等待离线引擎就绪后补读（最多约 30s；超时或失败走系统）。 */
    private fun retryWhenReady() {
        mainHandler.postDelayed({
            if (VoiceHub.offlineTtsReady) {
                pending?.let {
                    // 补读同样走后台线程 + 播放代次（打断/新朗读后不播；被取代不系统兜底）
                    val gen = VoiceHub.offline.nextSpeakGeneration()
                    offlineExecutor.execute {
                        val ok = try { VoiceHub.offline.speak(it, gen) } catch (e: Exception) { false }
                        if (!ok && gen == VoiceHub.offline.currentSpeakGeneration()) {
                            mainHandler.post { doSpeak(it) }
                        }
                    }
                    pending = null
                }
            } else if (VoiceHub.initInProgress) {
                retryWhenReady()
            } else {
                pending?.let { doSpeak(it); pending = null }
            }
        }, 800)
    }

    private fun doSpeak(text: String) {
        if (!ready) { pending = text; return }   // 系统 TTS 初始化中，初始化完成补读
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "onboarding")
        } catch (e: Exception) {
            // 忽略：TTS 失败不阻断引导
        }
    }

    /** 停止朗读（用户开口打断时调用——机器人让位给用户说）。 */
    fun stop() {
        VoiceHub.offline.cancelSpeak()   // 作废播放代次：排队/生成中的任务播放前校验失败不播（打断回归修复）
        VoiceHub.offline.stop()          // 停止正在播放的 AudioTrack
        pending = null
        try { tts?.stop() } catch (e: Exception) {}
    }

    fun shutdown() {
        // 残余修复（验收 P1）：页面生命周期闭合——关 executor（排队任务不再执行）、
        // 取消延迟回调、作废播放代次并停离线 AudioTrack、关系统 TTS
        try { offlineExecutor.shutdownNow() } catch (e: Exception) {}
        mainHandler.removeCallbacksAndMessages(null)
        VoiceHub.offline.cancelSpeak()
        VoiceHub.offline.stop()
        pending = null
        try { tts?.shutdown() } catch (e: Exception) {}
    }
}
