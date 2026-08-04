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
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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
            val modelFile = modelManager.findFile(dir, "model.onnx") ?: return
            val tokensFile = modelManager.findFile(dir, "tokens.txt") ?: return
            val vits = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                lexicon = modelManager.findFile(dir, "lexicon.txt")?.absolutePath ?: "",
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
    private var recognizer: OfflineRecognizer? = null
    private var record: AudioRecord? = null
    private var listening = false
    private var cancelled = false
    private var autoRestart = false
    private var onResult: ((String) -> Unit)? = null

    fun initStt() {
        val dir = modelManager.sttDir
        if (!modelManager.sttReady()) return
        try {
            val encoder = modelManager.findFile(dir, "encoder-epoch-99-avg-1.onnx")?.absolutePath ?: return
            val decoder = modelManager.findFile(dir, "decoder-epoch-99-avg-1.onnx")?.absolutePath ?: return
            val joiner = modelManager.findFile(dir, "joiner-epoch-99-avg-1.onnx")?.absolutePath ?: return
            val transducer = OfflineTransducerModelConfig(
                encoder = encoder,
                decoder = decoder,
                joiner = joiner,
            )
            val modelConfig = OfflineModelConfig(transducer = transducer)
            recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(),
                    modelConfig = modelConfig,
                ),
            )
            Log.i(tag, "离线 STT 就绪（流式中文）")
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
            listening = false
            return false
        }
        record = recorder

        Thread {
            try {
                recorder.startRecording()
                while (listening && !cancelled) {
                    val stream: OfflineStream = engine.createStream()
                    var fullText = ""
                    var silentMs = 0L
                    var lastPartial = ""
                    val shortBuf = ShortArray(minBuf / 2)
                    // 一句话的识别循环：采集 → 解码 → 静音检测结束
                    while (listening && !cancelled) {
                        val n = recorder.read(shortBuf, 0, shortBuf.size)
                        if (n <= 0) continue
                        // 静音检测（RMS）
                        var rms = 0f
                        for (i in 0 until n) rms += shortBuf[i] * shortBuf[i]
                        rms = kotlin.math.sqrt(rms / n)
                        if (rms < 300f) {   // 静音阈值
                            silentMs += n * 1000L / sampleRate
                            if (silentMs > 800L) break   // 静音 0.8s → 一句话结束
                        } else {
                            silentMs = 0L
                        }
                        // 解码
                        val floatBuf = FloatArray(n)
                        for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32767f
                        stream.acceptWaveform(floatBuf, sampleRate)
                        engine.decode(stream)
                        val text = engine.getResult(stream).text
                        if (text.isNotBlank() && text != lastPartial) {
                            lastPartial = text
                            fullText = text
                            val t = text
                            mainHandler.post { onPartial(t) }   // 实时字幕
                        }
                    }
                    // 一句话结束：回调结果
                    if (fullText.isNotBlank()) {
                        val t = fullText
                        mainHandler.post { onResult?.invoke(t) }
                    }
                    if (!autoRestart) break   // 不自动重听则结束
                }
            } catch (e: Exception) {
                Log.w(tag, "离线 STT 监听异常", e)
            } finally {
                try { recorder.stop(); recorder.release() } catch (e: Exception) {}
                listening = false
            }
        }.start()
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
