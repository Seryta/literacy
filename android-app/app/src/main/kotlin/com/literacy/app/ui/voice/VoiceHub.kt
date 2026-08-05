package com.literacy.app.ui.voice

import android.content.Context

/**
 * App 级语音引擎管理（单例）：
 * - 离线引擎（sherpa-onnx，女声/流式）优先，模型就绪时自动启用
 * - 模型未下载/加载失败 → 各调用点回退系统自带（SpeechRecognizer / TextToSpeech）
 * - [initInProgress] 供调用方等待：重开 App 引擎加载需 1-5s，
 *   说话/监听前应等就绪（避免走系统兜底导致无声/无反应）
 */
object VoiceHub {
    lateinit var modelManager: ModelManager
        private set
    lateinit var offline: OfflineVoiceEngine
        private set
    private var initialized = false

    /** 引擎初始化中（模型就绪时后台加载 onnx，可能 1-5s）；false 表示已结束（成功或失败）。 */
    @Volatile
    var initInProgress = false
        private set

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        modelManager = ModelManager(context)
        offline = OfflineVoiceEngine(modelManager)
        // 模型就绪则初始化离线引擎（后台，不阻塞启动）
        initInProgress = true
        Thread {
            try {
                if (modelManager.ttsReady()) offline.initTts()
                if (modelManager.sttReady()) offline.initStt()
            } catch (e: Exception) {
                // review-09 P1-06：初始化异常必须收敛在线程内——文件 IO/校验失败
                // 不得逃逸为未捕获异常杀进程（重操作已在后台线程执行）
                android.util.Log.w("VoiceHub", "离线引擎初始化失败", e)
            } finally {
                initInProgress = false
            }
        }.start()
    }

    /** 离线 TTS 是否可用（模型就绪且加载成功）。 */
    val offlineTtsReady: Boolean get() = offline.ttsAvailable

    /** 离线 STT 是否可用。 */
    val offlineSttReady: Boolean get() = offline.sttAvailable
}
