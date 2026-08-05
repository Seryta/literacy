package com.literacy.app.ui.voice

import android.annotation.SuppressLint
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

    // 引擎级互斥——
    // - ttsLock：同一时刻只允许一个 generate+play（native OfflineTts 非并发安全）
    // - sttLock：recognizer 的捕获/释放/重建与 startListening 串行（防 use-after-release）
    private val ttsLock = Any()
    private val sttLock = Any()

    // ── TTS（VITS 中文女声）────────────────────────────────────────
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    // 播放代次——新朗读/取消时 +1；在途任务 play 前校验失败则不播放
    // （协程/队列 cancel 不中断同步 native generate，旧任务可能在新任务之后才生成完）
    private val ttsGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    fun initTts() {
        val dir = modelManager.ttsDir
        if (!modelManager.ttsReady()) return
        // 残余修复（验收 P2）：重建前停止播放并释放旧 native 实例（重复下载/重初始化不遗留）；
        // 构建+赋值整体在锁内——speak 不得在 release 与替换之间并发拿已释放实例
        synchronized(ttsLock) {
            cancelSpeak()
            stop()
            tts?.release()
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
    }

    val ttsAvailable: Boolean get() = tts != null

    /** 新朗读代次：调用方（LearnViewModel）在启动朗读前获取；
     *  在途旧任务（同步 generate 不可中断）play 前校验失败则不播放。 */
    fun nextSpeakGeneration(): Int = ttsGeneration.incrementAndGet()

    /** 取消在途朗读（离页/释放/用户开口打断）：代次 +1，正在 generate 的旧任务完成后不播放。 */
    fun cancelSpeak() { ttsGeneration.incrementAndGet() }

    /** 当前播放代次（取消/新朗读后 +1）。调用方用「speak 返回 false 时的 currentSpeakGeneration」
     *  区分「被取代/取消」（代次已变，不得重播旧文本/走系统兜底）与「真实失败」（代次未变，可兜底）。 */
    fun currentSpeakGeneration(): Int = ttsGeneration.get()

    /** 朗读文本（女声）；返回是否成功。
     *  @param generation 播放代次（0=不校验，仅无打断语义的旧调用方用）；
     *         非 0 时生成后/播放前校验，代次已过期（被新朗读/取消取代）则不播放。
     *  ⚠ 返回 false 两种含义：真实失败（引擎不可用/生成异常/音频空）或被取代/取消（代次过期）。
     *  调用方必须用 [currentSpeakGeneration] 区分——代次已变时不得走系统兜底重播旧文本。
     *  串行：ttsLock 内 generate+play 原子执行（native OfflineTts 非并发安全）。 */
    fun speak(text: String, generation: Int = 0): Boolean {
        if (text.isBlank()) return false
        // 残余修复（验收 P1）：engine 捕获与 generate+play 整体在 ttsLock 内——
        // initTts 重建（同样持锁 release+替换）不会并发释放 speak 正在使用的实例
        return synchronized(ttsLock) {
            val engine = tts ?: return false
            // 已被更新的朗读/取消取代：不生成（省一次 CPU 重操作）
            if (generation != 0 && generation != ttsGeneration.get()) return false
            try {
                stop()
                val audio = engine.generate(text)
                // 生成期间被取消/取代：不播放（旧音频不得盖过新音频/离页后不响）
                if (generation != 0 && generation != ttsGeneration.get()) return false
                // 残余修复（验收 P2）：play 前二次校验代次（play 与代次检查间的窗口被取消则不播）
                val samples = audio.samples
                if (samples.isEmpty() || (generation != 0 && generation != ttsGeneration.get())) return false
                play(samples, audio.sampleRate, generation)
                true
            } catch (e: Exception) {
                Log.w(tag, "离线 TTS 朗读失败", e)
                false
            }
        }
    }

    fun stop() {
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
    }

    private fun play(samples: FloatArray, sampleRate: Int, generation: Int) {
        // 残余修复（验收 P2）：PCM 转换/建轨/写入是真实播放前的大窗口——转换后、play() 前
        // 各校验一次代次（此间 cancel+stop 只停已发布 track，看不到未发布的；不查则旧音频仍会响起）
        if (generation != 0 && generation != ttsGeneration.get()) return
        val short = ShortArray(samples.size)
        for (i in samples.indices) {
            val s = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            short[i] = s.toShort()
        }
        if (generation != 0 && generation != ttsGeneration.get()) return
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
        if (generation != 0 && generation != ttsGeneration.get()) {
            track.release()   // 窗口内被取消：不播，直接释放
            return
        }
        track.play()
        audioTrack = track
    }

    // ── STT（流式 zipformer 中文识别）─────────────────────────────
    private var recognizer: OnlineRecognizer? = null
    private var record: AudioRecord? = null
    // review-09 P1-04：跨线程状态 @Volatile 同步（主线程写，采集线程读）
    @Volatile private var listening = false
    @Volatile private var cancelled = false
    @Volatile private var autoRestart = false
    @Volatile private var onResult: ((String) -> Unit)? = null
    @Volatile private var onPartialCb: ((String) -> Unit)? = null   // 实时字幕回调（成员保存：initStt 重建后恢复监听不丢）
    @Volatile private var listenThread: Thread? = null   // 采集线程（start 前 join 旧线程，避免并发操作 recognizer 崩溃）
    @Volatile private var listenGeneration = 0   // 监听代次：取消/重启后旧代结果不投递（防穿透）

    fun initStt() {
        val dir = modelManager.sttDir
        if (!modelManager.sttReady()) return
        // recognizer 释放/重建与 startListening 的捕获/启动互斥——
        // 锁内 join 旧采集线程 + 释放 + 重建；startListening 锁内捕获引擎并启动 worker，
        // 二者串行后释放必然发生在 worker 已退出之后（不再 use-after-release）
        synchronized(sttLock) {
            // 重建会终止活动监听（cancelListening 停麦 + 作废代次；SpeechInputManager 无感知、
            // 无错误回调，新引擎无监听器——用户说话会无声）——记录重建前监听状态，
            // 重建成功后自动恢复（回调/autoRestart 沿用旧的，不丢实时字幕与打断）
            val wasListening = listening
            val savedOnResult = onResult
            val savedAutoRestart = autoRestart
            cancelListening()   // 重建前清理（会递增 listenGeneration 作废旧代在途回调）
            val captureGen = listenGeneration   // 重建基线：其后只有外部 cancel（退后台）会再递增
            listenThread?.let { old ->
                try { old.join(2000) } catch (e: InterruptedException) {}
                // 残余修复：旧线程仍存活（可能停在 native decode）→ 不释放正在使用的
                // recognizer（release 正在使用的句柄会再次 native 闪退），放弃本次重建
                if (old.isAlive) {
                    Log.w(tag, "initStt 重建：旧采集线程仍存活（2s 超时），放弃重建避免释放使用中的 recognizer")
                    return
                }
            }
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
                // review-09 P1-03：旧 recognizer 释放后再重建（避免 native 泄漏）
                recognizer?.release()
                // OnlineRecognizer：标准流式（isReady 控制 decode 节奏，特征维度 80）。
                // review-09 P2-1：enableEndpoint=false——代码不调用 isEndpoint/reset，
                // 端点检测由本类静音逻辑（RMS + 文本增量）承担，避免配置与行为不一致
                recognizer = OnlineRecognizer(
                    config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(featureDim = 80),
                        modelConfig = modelConfig,
                        enableEndpoint = false,
                    ),
                )
                Log.i(tag, "离线 STT 就绪（流式中文，OnlineRecognizer）")
            } catch (e: Exception) {
                Log.w(tag, "离线 STT 初始化失败，回退系统", e)
                recognizer = null
            }
            // 重建前有活动监听：自动恢复（synchronized 可重入；新 recognizer 已就绪）——
            // 不做则监听静默死亡，用户说话无声。
            // 残余修复（验收 P1）：重建期间若被页面 cancel（退后台，listenGeneration 已变），
            // 不再自动开麦（否则恢复会覆盖退后台取消、在后台重新开麦）
            if (wasListening && recognizer != null && listenGeneration == captureGen) {
                val ok = startListening(savedOnResult ?: {}, onPartialCb ?: {}, savedAutoRestart)
                if (!ok) Log.w(tag, "initStt 重建后恢复监听失败（无输入设备？）")
            }
        }
    }

    val sttAvailable: Boolean get() = recognizer != null

    /** 开始持续监听（语音段检测：静音 0.8s = 一句话完 → 回调结果；autoRestart 则继续听）。
     *  review-09 P2-5：录音权限由调用方统一申请（MainActivity/Onboarding），此处静态标注豁免 lint。 */
    @SuppressLint("MissingPermission")
    fun startListening(
        onResultText: (String) -> Unit,
        onPartial: (String) -> Unit = {},
        autoRestart: Boolean = false,
    ): Boolean {
        // 引擎捕获 + 旧线程 join + worker 启动在 sttLock 内原子完成——
        // initStt 的释放/重建与这里互斥：initStt 要么在锁外等 worker 退出后再释放，
        // 要么（先拿到锁）释放重建后本方法捕获到的是新实例；绝不捕获已释放句柄
        synchronized(sttLock) {
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
            this.onPartialCb = onPartial
            this.autoRestart = autoRestart
            listening = true
            val generation = ++listenGeneration   // 新一代监听，旧代在途回调作废

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
                        try {
                            Log.d(tag, "createStream 成功，开始采集")
                            var fullText = ""
                            var silentMs = 0L
                            var silentRun = 0   // review-09 W1：连续静音帧计数（消抖）
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
                                // 静音检测（RMS）：阈值调低（真机轻声也能触发）；语音活动即重置
                                var rms = 0f
                                for (i in 0 until n) rms += shortBuf[i] * shortBuf[i]
                                rms = kotlin.math.sqrt(rms / n)
                                if (rms < 80f) {   // 静音阈值（低：避免轻声说话被当静音）
                                    // review-09 W1：连续静音帧消抖——单帧/偶发噪声（爆音、环境音）
                                    // 不累计静音；连续 5 帧（≈40ms）静音才计入，避免停顿前
                                    // 一两帧噪声打断语音段
                                    silentRun++
                                    if (silentRun >= 5) {
                                        silentMs += n * 1000L / sampleRate
                                        // review-09 P1-01 + W1：已有文本 → 700ms 静音即结束
                                        // （老人慢语速停顿 500-800ms 常见，500ms 会把一句切两段）；
                                        // 无文本（等待开口）→ 长静音 900ms
                                        val limit = if (fullText.isNotBlank()) 700L else 900L
                                        if (silentMs > limit) break   // 一句话结束
                                    }
                                } else {
                                    silentRun = 0   // 有语音活动（非静音）重置（含消抖计数）
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
                                    // review-09 P1-01：只在文本有增量（新文本）时重置静音——
                                    // 在线结果保留旧文本时重复文本不重置，保证“说完→静音”能结束
                                    if (text.isNotBlank() && text != lastPartial) {
                                        lastPartial = text
                                        fullText = text
                                        silentMs = 0L   // 新文本视为正在说话（停顿不截断）
                                        val t = text
                                        Log.d(tag, "实时转写: $t")
                                        val gen = generation
                                        mainHandler.post { if (gen == listenGeneration) onPartialCb?.invoke(t) }   // 实时字幕（代次校验防穿透）
                                    }
                                }
                            }
                            // review-09 P1-04（C1）：取消后不再做尾解码——退后台 ON_PAUSE →
                            // speech.cancel → cancelListening 只停麦，采集线程仍可能走到这里
                            // （inputFinished/decode 耗时窗口），跳过避免在途结果被投递
                            if (cancelled) return@Thread
                            // review-09 P1-02：退出内层循环后提交尾部剩余样本 + inputFinished +
                            // 最终 decode + getResult——尾音/末字不丢（此前不足 0.5s 的尾部直接丢弃）
                            if (accum.isNotEmpty()) {
                                val floatBuf = FloatArray(accum.size)
                                for (i in accum.indices) floatBuf[i] = accum[i] / 32767f
                                stream.acceptWaveform(floatBuf, sampleRate)
                                accum.clear()
                            }
                            stream.inputFinished()
                            while (engine.isReady(stream)) engine.decode(stream)
                            val finalText = engine.getResult(stream).text
                            // review-09 P1-04（C1）：解码完成后再检查一次——取消可能发生在
                            // 尾解码期间；不得把结果投递给 onResult（触发在途 LLM 调用/工具写库）
                            if (cancelled) return@Thread
                            // 一句话结束：回调最终结果（尾音解码后的文本优先；代次校验防穿透到新监听）
                            val gen = generation
                            if (finalText.isNotBlank()) {
                                val t = finalText
                                Log.d(tag, "识别结果: $t")
                                mainHandler.post { if (gen == listenGeneration) onResult?.invoke(t) }
                            } else if (fullText.isNotBlank()) {
                                val t = fullText
                                Log.d(tag, "识别结果: $t")
                                mainHandler.post { if (gen == listenGeneration) onResult?.invoke(t) }
                            } else {
                                Log.d(tag, "识别超时/无结果（静音）")
                            }
                            if (!autoRestart) break   // 不自动重听则结束
                        } finally {
                            // review-09 P1-03：stream 必须显式释放（v1.13.4 有 release，之前注释说没有是错的）
                            try { stream.release() } catch (e: Exception) {}
                        }
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
    }

    fun cancelListening() {
        // 残余修复（验收 P1）：与 initStt 恢复块（检查代次 + startListening）同锁串行——
        // 外部取消要么先于检查（代次变→不恢复），要么后于 startListening（停麦生效），
        // 不再有"检查通过→取消插入→startListening 覆盖 cancelled"的重新开麦窗口
        synchronized(sttLock) {
            cancelled = true
            listening = false
            listenGeneration++   // 作废旧代在途回调
            try { record?.stop() } catch (e: Exception) {}
        }
    }

    fun destroy() {
        cancelListening()
        listenThread?.let { old ->
            try { old.join(2000) } catch (e: InterruptedException) {}
        }
        stop()
        // 引擎实例是 VoiceHub 单例（旋转/重建不释放，重新初始化由 initStt 先 release 旧实例）
    }
}
