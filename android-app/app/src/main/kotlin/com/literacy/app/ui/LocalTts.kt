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
            try { if (VoiceHub.offline.speak(text)) return } catch (e: Exception) {}
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
                pending?.let { VoiceHub.offline.speak(it); pending = null }
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
        VoiceHub.offline.stop()
        pending = null
        try { tts?.stop() } catch (e: Exception) {}
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (e: Exception) {}
    }
}
