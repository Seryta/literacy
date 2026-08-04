package com.literacy.app.ui.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * sherpa-onnx 离线语音封装：TTS（中文女声 VITS）+ STT（流式 zipformer）。
 * 模型文件由 [ModelManager] 下载到 App 私有目录，就绪后创建引擎。
 */
class OfflineVoiceEngine(
    private val modelManager: ModelManager,
) {
    private val tag = "OfflineVoice"
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── TTS（VITS 中文女声）────────────────────────────────────────
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null

    fun initTts() {
        val dir = modelManager.ttsDir
        if (!modelManager.ttsReady()) return
        try {
            val modelFile = File(dir, "model.onnx")
            val tokensFile = File(dir, "tokens.txt")
            val vits = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                lexicon = File(dir, "lexicon.txt").takeIf { it.isFile }?.absolutePath ?: "",
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vits,
                numThreads = 2,
            )
            tts = OfflineTts(config = OfflineTtsConfig(model = modelConfig))
            Log.i(tag, "离线 TTS 就绪（中文女声）")
        } catch (e: Exception) {
            Log.w(tag, "离线 TTS 初始化失败，回退系统", e)
            tts = null
        }
    }

    val ttsAvailable: Boolean get() = tts != null

    /** 朗读文本（女声）；返回是否成功。 */
    fun speak(text: String): Boolean {
        val engine = tts ?: return false
        if (text.isBlank()) return false
        return try {
            stop()
            val audio = engine.generate(text)
            val samples = audio.samples
            if (samples.isEmpty()) return false
            play(samples, audio.sampleRate)
            true
        } catch (e: Exception) {
            Log.w(tag, "离线 TTS 朗读失败", e)
            false
        }
    }

    fun stop() {
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val short = ShortArray(samples.size)
        for (i in samples.indices) {
            val s = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            short[i] = s.toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(short.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(short, 0, short.size)
        track.play()
        audioTrack = track
    }

    // ── STT（流式 zipformer 中文识别）─────────────────────────────
    private var recognizer: OnlineRecognizer? = null
    private var record: AudioRecord? = null
    private var listening = false
    private var cancelled = false
    private var autoRestart = false
    private var onResult: ((String) -> Unit)? = null
    @Volatile private var listenThread: Thread? = null   // 采集线程（start 前 join 旧线程，避免并发操作 recognizer 崩溃）

    fun initStt() {
        val dir = modelManager.sttDir
        if (!modelManager.sttReady()) return
        try {
            val encoder = File(dir, "encoder-epoch-99-avg-1.onnx").absolutePath
            val decoder = File(dir, "decoder-epoch-99-avg-1.onnx").absolutePath
            val joiner = File(dir, "joiner-epoch-99-avg-1.onnx").absolutePath
            val tokensFile = File(dir, "tokens.txt").absolutePath
            val transducer = OnlineTransducerModelConfig(
                encoder = encoder,
                decoder = decoder,
                joiner = joiner,
            )
            // 关键：tokens.txt 必须传给 OnlineModelConfig.tokens（vocab）——
            // 缺失会导致 createStream 在 native 层崩溃（确定性 bug）
            val modelConfig = OnlineModelConfig(
                transducer = transducer,
                tokens = tokensFile,
                numThreads = 2,
            )
            // OnlineRecognizer：标准流式（isReady 控制 decode 节奏，特征维度 80）
            recognizer = OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(featureDim = 80),
                    modelConfig = modelConfig,
                    enableEndpoint = true,
                ),
            )
            Log.i(tag, "离线 STT 就绪（流式中文，OnlineRecognizer）")
        } catch (e: Exception) {
            Log.w(tag, "离线 STT 初始化失败，回退系统", e)
            recognizer = null
        }
    }

    val sttAvailable: Boolean get() = recognizer != null

    /** 开始持续监听（语音段检测：静音 0.8s = 一句话完 → 回调结果；autoRestart 则继续听）。 */
    fun startListening(
        onResultText: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        autoRestart: Boolean = false,
    ): Boolean {
        val engine = recognizer ?: return false
        if (listening) return true
        // 串行化：等待旧采集线程完全退出（后台 cancel 后立即 restart 的竞态——
        // 旧线程并发操作 recognizer 会导致 sherpa native createStream 崩溃）
        listenThread?.let { old ->
            try { old.join(2000) } catch (e: InterruptedException) {}
            if (old.isAlive) return false   // 旧线程未退出：降级，不启动（避免崩溃）
        }
        cancelled = false
        this.onResult = onResultText
        this.autoRestart = autoRestart
        listening = true

        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(sampleRate),
            )
        } catch (e: Exception) {
            Log.w(tag, "离线 STT AudioRecord 构造失败（无麦克风？）", e)
            listening = false
            return false
        }
        // 无音频输入设备（模拟器/特殊环境）：优雅降级，不启动监听（避免 native 崩溃）
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(tag, "离线 STT AudioRecord 未初始化（无输入设备）state=${recorder.state}")
            recorder.release()
            listening = false
            return false
        }
        Log.d(tag, "AudioRecord 就绪，开始 createStream + 采集循环（虚拟音频验证）")
        record = recorder

        val thread = Thread {
            try {
                recorder.startRecording()
                // 启动失败（无输入流）：降级，不进入采集循环（避免 native decode 崩溃）
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@Thread
                while (listening && !cancelled) {
                    val stream: OnlineStream = engine.createStream()
                    Log.d(tag, "createStream 成功，开始采集")
                    var fullText = ""
                    var silentMs = 0L
                    var lastPartial = ""
                    val shortBuf = ShortArray(minBuf / 2)
                    // 累积音频到流式 chunk 再解码（zipformer 需约 39 特征帧≈390ms；
                    // 每 read 小段就 decode 会报输入帧数不足）
                    val chunkSamples = 8000   // 0.5s @16kHz
                    val accum = java.util.ArrayList<Short>(chunkSamples)
                    // 一句话的识别循环：采集 → 累积 → 解码 → 静音检测结束
                    while (listening && !cancelled) {
                        val n = recorder.read(shortBuf, 0, shortBuf.size)
                        if (n <= 0) continue
                        // 静音检测（RMS）：阈值调低（真机轻声也能触发）；有识别文本即视为正在说话
                        var rms = 0f
                        for (i in 0 until n) rms += shortBuf[i] * shortBuf[i]
                        rms = kotlin.math.sqrt(rms / n)
                        if (rms < 80f) {   // 静音阈值（低：避免轻声说话被当静音）
                            silentMs += n * 1000L / sampleRate
                            if (silentMs > 800L) break   // 静音 0.8s → 一句话结束
                        } else {
                            silentMs = 0L
                        }
                        // 累积到 chunk（约 0.5s），标准流式：acceptWaveform 后 isReady 则 decode
                        for (i in 0 until n) accum.add(shortBuf[i])
                        if (accum.size >= chunkSamples) {
                            val floatBuf = FloatArray(accum.size)
                            for (i in accum.indices) floatBuf[i] = accum[i] / 32767f
                            stream.acceptWaveform(floatBuf, sampleRate)
                            accum.clear()
                            while (engine.isReady(stream)) engine.decode(stream)
                            val text = engine.getResult(stream).text
                            if (text.isNotBlank()) {
                                silentMs = 0L   // 有识别文本视为正在说话（停顿不截断）
                                if (text != lastPartial) {
                                    lastPartial = text
                                    fullText = text
                                    val t = text
                                    Log.d(tag, "实时转写: $t")
                                    mainHandler.post { onPartial(t) }   // 实时字幕
                                }
                            }
                        }
                    }
                    // 一句话结束：回调结果
                    if (fullText.isNotBlank()) {
                        val t = fullText
                        Log.d(tag, "识别结果: $t")
                        mainHandler.post { onResult?.invoke(t) }
                    } else {
                        Log.d(tag, "识别超时/无结果（静音）")
                    }
                    if (!autoRestart) break   // 不自动重听则结束
                }
            } catch (e: Exception) {
                Log.w(tag, "离线 STT 监听异常", e)
            } finally {
                try { recorder.stop(); recorder.release() } catch (e: Exception) {}
                listening = false
            }
        }
        listenThread = thread
        thread.start()
        return true
    }

    fun cancelListening() {
        cancelled = true
        listening = false
        try { record?.stop() } catch (e: Exception) {}
    }

    fun destroy() {
        cancelListening()
        stop()
        // 引擎实例不可复用后释放（sherpa-onnx 无显式 close 的 Java API 版本）
    }
}
