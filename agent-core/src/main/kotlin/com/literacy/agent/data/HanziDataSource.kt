package com.literacy.agent.data

import com.literacy.agent.model.StrokePoint

/** 单个字的字库信息（makemeahanzi 数据，data/hanzi.db）。 */
data class HanziInfo(
    val char: String,
    val pinyin: String,
    val decomposition: String,
    val radical: String,
    val definition: String,
    val strokeCount: Int,
    val strokes: List<String>,          // SVG 路径（M/L/Q/C/Z，UI 渲染用）
    val medians: List<List<StrokePoint>>, // 笔画骨架线（书写轨迹，评估参考用）
)

/**
 * 字库数据源抽象（Android 兼容：Room / android.database 实现可替换）。
 *
 * - JVM 测试基线：SqliteHanziRepository（data/hanzi.db，sqlite-jdbc）
 * - Android App：Room 实现同接口
 */
interface HanziDataSource {
    /** 按字查询；未收录返回 null。 */
    fun find(char: String): HanziInfo?

    /** 笔画数（未收录返回 0）。 */
    fun strokeCount(char: String): Int

    /** 参考笔画骨架线（medians），供 StrokeEvaluator；无数据返回 null。 */
    fun referenceStrokes(char: String): List<List<StrokePoint>>?
}
