package com.literacy.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import com.literacy.app.ui.voice.VoiceHub
import java.util.Locale

/**
 * 轻量 TTS 封装（引导机器人/点读用）。
 * 优先离线中文女声（sherpa-onnx，模型就绪时）；否则回退系统 TTS（语速 0.85 适老）。
 */
class LocalTts(context: Context) {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                tts?.setSpeechRate(0.85f)
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        // 优先离线女声
        if (VoiceHub.offlineTtsReady) {
            try {
                if (VoiceHub.offline.speak(text)) return
            } catch (e: Exception) {
                // 回退系统
            }
        }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "onboarding")
        } catch (e: Exception) {
            // 忽略：TTS 失败不阻断引导
        }
    }

    /** 停止朗读（用户开口打断时调用——机器人让位给用户说）。 */
    fun stop() {
        VoiceHub.offline.stop()
        try { tts?.stop() } catch (e: Exception) {}
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (e: Exception) {}
    }
}
