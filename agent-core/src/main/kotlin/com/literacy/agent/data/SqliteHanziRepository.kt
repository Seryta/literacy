package com.literacy.agent.data

import com.literacy.agent.model.StrokePoint
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.Inflater

/**
 * SQLite 字库实现（JVM 测试基线用，data/hanzi.db）。
 * Android App 用 Room 实现同接口（HanziDataSource）。
 */
class SqliteHanziRepository(private val dbPath: File) : HanziDataSource {

    private var connection: Connection? = null

    private fun connect(): Connection {
        connection?.let { if (!it.isClosed) return it }
        val conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.absolutePath}")
        connection = conn
        return conn
    }

    fun close() {
        connection?.let { if (!it.isClosed) it.close() }
        connection = null
    }

    /** 按字查询；未收录返回 null。 */
    override fun find(char: String): HanziInfo? {
        val conn = connect()
        conn.prepareStatement("SELECT * FROM hanzi WHERE char = ?").use { stmt ->
            stmt.setString(1, char)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                return HanziInfo(
                    char = rs.getString("char"),
                    pinyin = rs.getString("pinyin"),
                    decomposition = rs.getString("decomposition"),
                    radical = rs.getString("radical"),
                    definition = rs.getString("definition"),
                    strokeCount = rs.getInt("stroke_count"),
                    strokes = parseSvgList(decompress(rs.getBytes("strokes"))),
                    medians = parseMedians(decompress(rs.getBytes("medians"))),
                )
            }
        }
    }

    override fun strokeCount(char: String): Int = find(char)?.strokeCount ?: 0

    /** 参考笔画骨架线（medians），供 StrokeEvaluator 对比；无数据返回 null。 */
    override fun referenceStrokes(char: String): List<List<StrokePoint>>? =
        find(char)?.medians?.takeIf { list -> list.isNotEmpty() && list.all { it.size >= 2 } }

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

    /** medians: [[[x,y],[x,y]...]...] → 每笔一条折线。 */
    private fun parseMedians(json: String): List<List<StrokePoint>> = try {
        @Suppress("UNCHECKED_CAST")
        (org.yaml.snakeyaml.Yaml().load<Any>(json) as? List<List<List<Number>>>)
            ?.map { line -> line.map { StrokePoint(it[0].toFloat(), it[1].toFloat()) } }
            ?: emptyList()
    } catch (e: Exception) { emptyList() }
}

/** SVG path 解析（M/L/Q/C/Z 子集，makemeahanzi 笔画路径格式）。 */
object SvgPathParser {

    /**
     * 解析 SVG 路径为坐标点序列。支持 M/L/Q/C 命令与 Z 闭合；
     * 折线/曲线用命令点近似采样（Q/C 的中点采样保证笔画形态）。
     */
    fun parse(path: String): List<StrokePoint> {
        val tokens = Regex("([MLQCZ])([^MLQCZ]*)").findAll(path)
        val points = mutableListOf<StrokePoint>()
        var cursor = StrokePoint(0f, 0f)
        var start = StrokePoint(0f, 0f)
        for (m in tokens) {
            val cmd = m.groupValues[1]
            val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(m.groupValues[2])
                .mapNotNull { it.value.toFloatOrNull() }.toList()
            when (cmd) {
                "M" -> {
                    if (nums.size >= 2) {
                        cursor = StrokePoint(nums[0], nums[1])
                        start = cursor
                        points += cursor
                    }
                }
                "L" -> {
                    for (i in nums.indices step 2) {
                        if (i + 1 < nums.size) {
                            cursor = StrokePoint(nums[i], nums[i + 1])
                            points += cursor
                        }
                    }
                }
                "Q" -> {
                    for (i in nums.indices step 4) {
                        if (i + 3 < nums.size) {
                            val cx = nums[i]; val cy = nums[i + 1]
                            val ex = nums[i + 2]; val ey = nums[i + 3]
                            cursor = quadToPoints(cursor, StrokePoint(cx, cy), StrokePoint(ex, ey), points)
                        }
                    }
                }
                "C" -> {
                    for (i in nums.indices step 6) {
                        if (i + 5 < nums.size) {
                            val c1 = StrokePoint(nums[i], nums[i + 1])
                            val c2 = StrokePoint(nums[i + 2], nums[i + 3])
                            val e = StrokePoint(nums[i + 4], nums[i + 5])
                            cursor = cubicToPoints(cursor, c1, c2, e, points)
                        }
                    }
                }
                "Z" -> {
                    points += start
                    cursor = start
                }
            }
        }
        return points
    }

    private fun quadToPoints(p0: StrokePoint, c: StrokePoint, p1: StrokePoint, out: MutableList<StrokePoint>): StrokePoint {
        // 二次贝塞尔中点多段采样（de Casteljau）
        val mid = StrokePoint(
            (p0.x + 2 * c.x + p1.x) / 4,
            (p0.y + 2 * c.y + p1.y) / 4,
        )
        out += mid
        out += p1
        return p1
    }

    private fun cubicToPoints(p0: StrokePoint, c1: StrokePoint, c2: StrokePoint, p1: StrokePoint, out: MutableList<StrokePoint>): StrokePoint {
        // 三次贝塞尔取两个中点近似（保持曲线形态的轻量采样）
        val a = StrokePoint((p0.x + c1.x) / 2, (p0.y + c1.y) / 2)
        val b = StrokePoint((c1.x + c2.x) / 2, (c1.y + c2.y) / 2)
        val d = StrokePoint((c2.x + p1.x) / 2, (c2.y + p1.y) / 2)
        val mid1 = StrokePoint((a.x + b.x) / 2, (a.y + b.y) / 2)
        val mid2 = StrokePoint((b.x + d.x) / 2, (b.y + d.y) / 2)
        out += mid1
        out += mid2
        out += p1
        return p1
    }
}
