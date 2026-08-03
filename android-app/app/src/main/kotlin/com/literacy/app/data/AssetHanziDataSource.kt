package com.literacy.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.literacy.agent.data.HanziDataSource
import com.literacy.agent.data.HanziInfo
import com.literacy.agent.model.StrokePoint
import com.literacy.app.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Android 字库实现：assets/hanzi.db（构建时由 data/hanzi.db 复制）→ 私有目录 → SQLiteDatabase 直连。
 *
 * 字库是只读外部数据（makemeahanzi），用原生 SQLite 打开；App 自身表（characters/sessions）
 * 用 Room（见 AppDatabase）。strokes/medians 为 zlib 压缩 BLOB，读取时解压（与管线一致）。
 *
 * 版本检查（APK 更新不替换旧库问题）：hanzi 表无版本字段（管线不改），以 assets 文件 SHA-256
 * 作为版本指纹——私有目录存 hanzi.db.sha256 标记，启动时比对 assets 哈希，不一致则重新复制。
 * 哈希成本 ~18MB/次（进程内首次访问字库时一次性，MessageDigest 流式）：release 下 versionCode
 * 未变且 db+marker 在 → 信任副本跳过比对（同版本 APK 内容不变）；debug 下 versionCode 固定 1
 * 但 assets 字库可能随开发变更 → 始终全量比对保证正确性。
 * 标记格式 v2：`<versionCode>:<sha256>`（旧格式纯 sha256 兼容，versionCode 解析为 null 走全量比对）。
 */
class AssetHanziDataSource(context: Context) : HanziDataSource {

    private val db: SQLiteDatabase by lazy {
        val target = File(context.filesDir, HANZI_DB)
        ensureFreshCopy(context, target)
        SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    /** assets 字库与已复制副本的版本比对：哈希不一致（或首次/旧版升级无标记）→ 重新复制。
     *  预筛：release 且 versionCode 未变 + db/marker 均在 → 跳过 18MB 流式哈希（同版本 APK 内容不变）。
     *  debug 不预筛：versionCode 固定 1，assets 字库可能随开发变更，始终比对保证正确性。 */
    private fun ensureFreshCopy(context: Context, target: File) {
        val marker = File(context.filesDir, HANZI_VERSION_FILE)
        if (!BuildConfig.DEBUG && target.exists() && marker.exists()) {
            val markerVersion = marker.readText().substringBefore(':').toIntOrNull()
            if (markerVersion == BuildConfig.VERSION_CODE) return   // 同版本 APK 内容不变，信任副本
        }
        val currentHash = sha256Hex(context.assets.open(HANZI_DB))
        val markerHash = marker.takeIf { it.exists() }?.readText()?.let { text ->
            if (text.contains(':')) text.substringAfter(':') else text   // 兼容旧格式（纯 hash）
        }
        val stale = !target.exists() || markerHash != currentHash
        if (stale) {
            // review-09 P2-7 + review-10 P2-18：临时文件 + ATOMIC_MOVE 原子替换——
            // 不预删目标（删-rename 两步之间进程终止会丢库）；rename 失败兜底删除重试
            val tmp = File(context.filesDir, HANZI_DB + ".tmp")
            context.assets.open(HANZI_DB).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                if (tmp.exists()) tmp.delete()   // 兜底失败清理临时文件
                throw e
            }
        }
        marker.writeText("${BuildConfig.VERSION_CODE}:$currentHash")
    }

    private fun sha256Hex(stream: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        stream.use { s ->
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun find(char: String): HanziInfo? {
        val rs = db.query(
            "hanzi", null, "char = ?", arrayOf(char), null, null, null,
        )
        return rs.use {
            if (!it.moveToFirst()) return null
            HanziInfo(
                char = it.getString(it.getColumnIndexOrThrow("char")),
                pinyin = it.getString(it.getColumnIndexOrThrow("pinyin")),
                decomposition = it.getString(it.getColumnIndexOrThrow("decomposition")),
                radical = it.getString(it.getColumnIndexOrThrow("radical")),
                definition = it.getString(it.getColumnIndexOrThrow("definition")),
                strokeCount = it.getInt(it.getColumnIndexOrThrow("stroke_count")),
                strokes = parseSvgList(decompress(it.getBlob(it.getColumnIndexOrThrow("strokes")))),
                medians = parseMedians(decompress(it.getBlob(it.getColumnIndexOrThrow("medians")))),
            )
        }
    }

    override fun strokeCount(char: String): Int = find(char)?.strokeCount ?: 0

    override fun referenceStrokes(char: String): List<List<StrokePoint>>? =
        find(char)?.medians?.takeIf { list -> list.isNotEmpty() && list.all { it.size >= 2 } }

    private companion object {
        const val HANZI_DB = "hanzi.db"
        const val HANZI_VERSION_FILE = "hanzi.db.sha256"
    }

    private fun decompress(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "[]"
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toString("UTF-8")
    }

    private fun parseSvgList(json: String): List<String> = try {
        val loaded = org.yaml.snakeyaml.Yaml().load<Any>(json)
        if (loaded is List<*>) loaded.mapNotNull { it?.toString() } else emptyList()
    } catch (e: Exception) { emptyList() }

    private fun parseMedians(json: String): List<List<StrokePoint>> = try {
        @Suppress("UNCHECKED_CAST")
        (org.yaml.snakeyaml.Yaml().load<Any>(json) as? List<List<List<Number>>>)
            ?.map { line -> line.map { StrokePoint(it[0].toFloat(), it[1].toFloat()) } }
            ?: emptyList()
    } catch (e: Exception) { emptyList() }
}
