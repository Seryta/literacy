package com.literacy.agent.learning

import com.literacy.agent.model.StrokePoint

/**
 * 手写评估（RESEARCH-TECH：本地规则引擎对比标准笔画特征）。
 *
 * 输入用户笔画坐标序列与参考笔画路径，输出偏差评分。
 * 特征对比：起笔位置 / 收笔位置 / 笔画长度比 / 主方向。
 * 纯 JVM 可测；Android 端只需提供 MotionEvent 坐标序列（落笔→移动→抬笔）。
 */
interface StrokeEvaluator {
    fun evaluate(input: List<StrokePoint>, reference: List<StrokePoint>): StrokeEvaluation
}

/** 笔画评估结果（对齐 WritingEvaluated：score 0.0-1.0 / ok 偏差在阈值内 / issues）。 */
data class StrokeEvaluation(
    val score: Double,
    val ok: Boolean,
    val issues: List<String> = emptyList(),
)

/**
 * 规则引擎实现（MASTERY-CRITERIA §1 书写维度检测）。
 * 简化几何特征对比：起笔/收笔位置偏差（相对笔画长度归一化）、长度比、主方向角差。
 */
class RuleStrokeEvaluator(private val threshold: Double = 0.6) : StrokeEvaluator {

    override fun evaluate(input: List<StrokePoint>, reference: List<StrokePoint>): StrokeEvaluation {
        if (input.size < 2 || reference.size < 2) {
            return StrokeEvaluation(0.0, false, listOf("笔画数据不足"))
        }
        val refLen = pathLength(reference)
        if (refLen <= 0.0) return StrokeEvaluation(0.0, false, listOf("参考笔画无效"))

        val startDist = dist(input.first(), reference.first()) / refLen
        val endDist = dist(input.last(), reference.last()) / refLen
        val lenRatio = pathLength(input) / refLen
        val dirDelta = angleDelta(input, reference)

        // 特征评分 → 综合（起笔/收笔各 0.3，长度/方向各 0.2）
        val score = (scoreByDistance(startDist, 0.3) * 0.3 +
            scoreByDistance(endDist, 0.3) * 0.3 +
            scoreByRatio(lenRatio, 0.5) * 0.2 +
            scoreByAngle(dirDelta) * 0.2)

        val issues = mutableListOf<String>()
        if (startDist > 0.3) issues += "起笔位置偏差"
        if (endDist > 0.3) issues += "收笔位置偏差"
        if (lenRatio < 0.5 || lenRatio > 1.5) issues += "笔画长度异常"
        if (dirDelta > 45.0) issues += "笔画方向偏斜"

        return StrokeEvaluation(score, score >= threshold, issues)
    }

    private fun pathLength(p: List<StrokePoint>): Double {
        var len = 0.0
        for (i in 1 until p.size) len += dist(p[i - 1], p[i])
        return len
    }

    private fun dist(a: StrokePoint, b: StrokePoint): Double =
        kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    /** 首尾向量夹角（度，0-180）。 */
    private fun angleDelta(input: List<StrokePoint>, reference: List<StrokePoint>): Double {
        val a = kotlin.math.atan2(
            (input.last().y - input.first().y).toDouble(),
            (input.last().x - input.first().x).toDouble(),
        )
        val b = kotlin.math.atan2(
            (reference.last().y - reference.first().y).toDouble(),
            (reference.last().x - reference.first().x).toDouble(),
        )
        var d = kotlin.math.abs(a - b)
        if (d > Math.PI) d = 2 * Math.PI - d
        return Math.toDegrees(d)
    }

    private fun scoreByDistance(d: Double, max: Double): Double = (1.0 - d / max).coerceIn(0.0, 1.0)
    private fun scoreByRatio(r: Double, tolerance: Double): Double =
        (1.0 - kotlin.math.abs(r - 1.0) / tolerance).coerceIn(0.0, 1.0)
    private fun scoreByAngle(deg: Double): Double = (1.0 - deg / 90.0).coerceIn(0.0, 1.0)
}
