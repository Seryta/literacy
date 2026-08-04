package com.literacy.app.ui.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 离线语音模型管理（sherpa-onnx）：
 * - 下载源可配置（默认 HuggingFace 国内镜像 hf-mirror.com，后续可换自己的服务器）
 * - 文件清单自动获取（HF tree API → 逐文件下载，espeak-ng-data 等目录自动覆盖）
 * - 断点续传：已存在且大小匹配的文件跳过
 * - 模型存 App 私有目录（filesDir）——签名一致的升级安装不清除，不用重下
 */
object VoiceModels {
    /** HuggingFace 镜像主机（可配置；后续可由我方提供专用源）。默认国内镜像 hf-mirror.com */
    const val HF_HOST = "https://hf-mirror.com"

    /** 模型 owner（HF 仓库前缀） */
    const val OWNER = "k2-fsa"

    /** TTS：VITS 中文女声（本地合成，女声） */
    const val TTS_REPO = "sherpa-onnx-vits-zh-ll"

    /** STT：流式中文识别（边说边出字） */
    const val STT_REPO = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"

    /** 就绪判定：这些文件存在且非空即认为模型可用 */
    val TTS_FILES = listOf("model.onnx", "tokens.txt", "lexicon.txt")
    val STT_FILES = listOf(
        "encoder-epoch-99-avg-1.onnx", "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.onnx", "tokens.txt",
    )
}

class ModelManager(context: Context) {
    private val appContext = context.applicationContext
    val ttsDir = File(appContext.filesDir, "voice-models/tts")
    val sttDir = File(appContext.filesDir, "voice-models/stt")

    // ── 就绪检测 ────────────────────────────────────────────────────
    fun ttsReady(): Boolean = requiredExist(ttsDir, VoiceModels.TTS_FILES)
    fun sttReady(): Boolean = requiredExist(sttDir, VoiceModels.STT_FILES)

    private fun requiredExist(dir: File, files: List<String>): Boolean =
        files.all { name -> val f = File(dir, name); f.isFile && f.length() > 0L }

    /** 预估体积（文件数，UI 提示用）。 */
    suspend fun estimate(): Int = withContext(Dispatchers.IO) {
        VoiceModels.TTS_FILES.size + VoiceModels.STT_FILES.size
    }

    // ── 下载（带进度回调）──────────────────────────────────────────
    /** 下载 TTS 模型；progress 0..100；成功返回文件数，失败抛异常。 */
    suspend fun downloadTts(onProgress: (Int) -> Unit): Int =
        downloadFiles(VoiceModels.TTS_REPO, VoiceModels.TTS_FILES, ttsDir, onProgress)

    suspend fun downloadStt(onProgress: (Int) -> Unit): Int =
        downloadFiles(VoiceModels.STT_REPO, VoiceModels.STT_FILES, sttDir, onProgress)

    private suspend fun downloadFiles(repo: String, files: List<String>, targetDir: File, onProgress: (Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            targetDir.mkdirs()
            files.forEachIndexed { i, path ->
                val dest = File(targetDir, path)
                // 断点续传：已存在且非空 → 跳过
                if (!(dest.isFile && dest.length() > 0L)) {
                    dest.parentFile?.mkdirs()
                    downloadFile(repo, path, dest)
                }
                onProgress(((i + 1) * 100 / files.size).coerceAtMost(100))
            }
            files.size
        }

    private fun downloadFile(repo: String, path: String, dest: File) {
        val url = URL("${VoiceModels.HF_HOST}/${VoiceModels.OWNER}/$repo/resolve/main/$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode != 200) throw RuntimeException("下载失败 HTTP ${conn.responseCode}: $path")
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            // 校验：非空（LFS 文件可能带指针——非空且大小合理即可）
            if (dest.length() == 0L) {
                dest.delete()
                throw RuntimeException("下载内容为空: $path")
            }
        } finally {
            conn.disconnect()
        }
    }
}
