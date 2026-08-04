package com.literacy.app.ui.voice

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 离线语音模型管理（sherpa-onnx）：
 * - 下载源：ModelScope（魔搭，国内 CDN 直连，已验证：小文件 200 直连、大文件 302→cdn-lfs-cn 206）
 * - 多文件清单直下（每文件独立 URL），断点续传（已存在且非空跳过）
 * - 模型存 App 私有目录（filesDir）——签名一致的升级安装不清除，不用重下
 * - 源可配置（替换 BASE 即可换自有源）
 */
object VoiceModels {
    /** ModelScope 下载基地址（国内可达；可配置换自有源） */
    const val BASE = "https://modelscope.cn/models"

    /** 模型定义：namespace/name 对应 modelscope 模型仓库，files 为下载清单（含官方 SHA256 校验） */
    data class ModelSpec(
        val namespace: String,
        val name: String,
        val files: List<Pair<String, String>>,   // (路径, 官方 SHA256) —— 防投毒校验
    ) {
        val paths: List<String> get() = files.map { it.first }
    }

    /** TTS：中文女声（VITS aishell3，官方模型；自建 modelscope 仓库，SHA256 锁定官方内容） */
    val TTS = ModelSpec(
        namespace = "mojo233",
        name = "literacy-voice-models",
        files = listOf(
            "tts/model.onnx" to "5511d651b7840c0a93a6bbfd4afd070a2c7f39ca1ec3ff2ecd73191519bbb852",
            "tts/tokens.txt" to "50b45a7b7de1752fd3c7b4755661c285f1547f59186eca2281089a81307ad953",
            "tts/lexicon.txt" to "ab2e61d357551e7b24ddd965d924aca784c20165ff58c150794e539c6b5e9e35",
        ),
    )

    /** STT：流式中文识别（zipformer-zh-14M，官方模型） */
    val STT = ModelSpec(
        namespace = "mojo233",
        name = "literacy-voice-models",
        files = listOf(
            "stt/encoder-epoch-99-avg-1.onnx" to "84c6a8f372686faa5b8f45f2d79f0816f76dcd9f547acb9a90eba2772d7eda8b",
            "stt/decoder-epoch-99-avg-1.onnx" to "5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78",
            "stt/joiner-epoch-99-avg-1.onnx" to "030212efaea9a8b6a4fa98faf6ac6055529c4408cf4865e898220ddd02780f34",
            "stt/tokens.txt" to "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac",
        ),
    )

    /** 单文件下载 URL（modelscope resolve 格式，302 自动跟随到国内 LFS CDN） */
    fun fileUrl(spec: ModelSpec, path: String): String =
        "$BASE/${spec.namespace}/${spec.name}/resolve/master/$path"
}

class ModelManager(context: Context) {
    private val appContext = context.applicationContext
    val ttsDir = File(appContext.filesDir, "voice-models/tts")
    val sttDir = File(appContext.filesDir, "voice-models/stt")

    // ── 就绪检测 ────────────────────────────────────────────────────
    fun ttsReady(): Boolean = ready(ttsDir, VoiceModels.TTS.paths)
    fun sttReady(): Boolean = ready(sttDir, VoiceModels.STT.paths)

    private fun ready(dir: File, files: List<String>): Boolean =
        files.all { File(dir, it.substringAfterLast('/')).isFile && File(dir, it.substringAfterLast('/')).length() > 0 }

    /** 定位模型文件（下载后文件名即原始名；兼容子目录递归）。 */
    fun findFile(dir: File, name: String): File? {
        if (!dir.isDirectory) return null
        dir.listFiles()?.forEach { f ->
            if (f.isFile) { if (f.name == name) return f }
            else findFile(f, name)?.let { return it }
        }
        return null
    }

    // ── 下载（多文件清单，带进度）──────────────────────────────────
    suspend fun downloadTts(onProgress: (Int) -> Unit): Boolean =
        downloadModel(VoiceModels.TTS, ttsDir, onProgress)

    suspend fun downloadStt(onProgress: (Int) -> Unit): Boolean =
        downloadModel(VoiceModels.STT, sttDir, onProgress)

    private suspend fun downloadModel(spec: VoiceModels.ModelSpec, targetDir: File, onProgress: (Int) -> Unit): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            targetDir.mkdirs()
            if (ready(targetDir, spec.paths)) return@withContext true
            spec.files.forEachIndexed { i, (path, sha256) ->
                val dest = File(targetDir, path.substringAfterLast('/'))
                if (!(dest.isFile && dest.length() > 0)) {
                    downloadFile(VoiceModels.fileUrl(spec, path), dest, sha256)
                }
                onProgress(((i + 1) * 100 / spec.files.size).coerceAtMost(100))
            }
            ready(targetDir, spec.paths)
        }

    /** 下载 + SHA256 校验（防投毒：与官方哈希不一致即删文件抛异常）。 */
    private fun downloadFile(url: String, dest: File, expectedSha256: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.instanceFollowRedirects = true   // 跟随 302（大文件 → 国内 LFS CDN）
        try {
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("下载失败 HTTP $code")
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            // SHA256 校验：与官方一致才通过，否则视为异常文件删除
            val actual = sha256(dest)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                dest.delete()
                throw RuntimeException("文件校验失败（可能被篡改）：${dest.name}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
