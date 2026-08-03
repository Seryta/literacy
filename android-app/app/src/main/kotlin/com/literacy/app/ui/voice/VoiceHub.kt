package com.literacy.app.ui.voice

import android.content.Context

/**
 * App 级语音引擎管理（单例）：
 * - 离线引擎（sherpa-onnx，女声/流式）优先，模型就绪时自动启用
 * - 模型未下载/加载失败 → 各调用点回退系统自带（SpeechRecognizer / TextToSpeech）
 */
object VoiceHub {
    lateinit var modelManager: ModelManager
        private set
    lateinit var offline: OfflineVoiceEngine
        private set
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        modelManager = ModelManager(context)
        offline = OfflineVoiceEngine(modelManager)
        // 模型就绪则初始化离线引擎（后台，不阻塞启动）
        Thread {
            if (modelManager.ttsReady()) offline.initTts()
            if (modelManager.sttReady()) offline.initStt()
        }.start()
    }

    /** 离线 TTS 是否可用（模型就绪且加载成功）。 */
    val offlineTtsReady: Boolean get() = offline.ttsAvailable

    /** 离线 STT 是否可用。 */
    val offlineSttReady: Boolean get() = offline.sttAvailable
}
