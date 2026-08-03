package com.literacy.app.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 轻量 TTS 封装（onboarding 引导机器人说话用，独立于 LearnViewModel 内的教学 TTS）。
 * 适老：语速放慢 0.85。TTS 不可用时不阻断（气泡文字始终可见）。
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
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "onboarding")
        } catch (e: Exception) {
            // 忽略：TTS 失败不阻断引导
        }
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (e: Exception) {}
    }
}
