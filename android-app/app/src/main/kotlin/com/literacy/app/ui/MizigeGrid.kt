package com.literacy.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 米字格：网格线 + 字库笔画渲染（SVG 路径）+ 用户手写轨迹。
 *
 * - 字库笔画用 Compose PathParser 解析 SVG path 数据
 * - revealStrokes 控制逐笔揭示（引导跟写）；独立书写时全隐藏
 * - P1-8：用户轨迹在内部以 Canvas 像素显示，onStrokeComplete 回调以
 *   **字库坐标系（0-1024）** 上报（逆变换），保证评估不受尺寸/密度影响
 * - 笔画计数（画了几笔）由 onStrokeCountChanged 回调
 */
@Composable
fun MizigeGrid(
    char: String,
    strokes: List<String>,           // 字库 SVG 笔画路径
    revealStrokes: Int,              // 已揭示笔画数（-1 = 全部，0 = 不显示）
    showOutline: Boolean,            // 是否显示字形轮廓（recognize/demonstrate 用）
    onStrokeComplete: (List<Pair<Float, Float>>) -> Unit,  // 字库坐标（评估用）
    onStrokeCountChanged: (Int) -> Unit = {},
    resetKey: Any = char,   // P1-7：阶段变化时重置轨迹（跟写轨迹不混入独立写）
    enabled: Boolean = true,   // P1-14：loading/paused/ended 时禁绘制
    modifier: Modifier = Modifier,
) {
    val gridColor = com.literacy.app.ui.theme.GridLine
    val strokeColor = com.literacy.app.ui.theme.GridStroke
    val guideColor = com.literacy.app.ui.theme.GridGuide
    val userColor = com.literacy.app.ui.theme.GridUserStroke
    val density = LocalDensity.current
    // review-09 P1-2：输入逆变换用实际画布尺寸（onSizeChanged 记录），不再固定 300.dp——
    // 绘制侧用 size.minDimension，两者不一致会导致评估坐标与显示错位
    val gridSidePx = with(density) { 300.dp.toPx() }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // 性能：SVG 解析只在笔画数据变化时做一次（remember 按内容键控，List.equals 结构比较），
    // 拖动帧只做变换绘制，不复解析（原先每帧在 draw lambda 内 parsePathString）。
    // 解析失败项为 null，绘制时跳过（与原行为一致）。
    val parsedStrokes = remember(char, strokes) {
        strokes.map { svg ->
            try {
                val parser = PathParser()
                parser.parsePathString(svg)
                parser.toPath()
            } catch (e: Exception) { null }
        }
    }

    var current by remember(char) { mutableStateOf<List<Offset>>(emptyList()) }
    var drawnStrokes by remember(resetKey) { mutableStateOf<List<List<Offset>>>(emptyList()) }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(char, enabled) {
            if (!enabled) return@pointerInput
            detectDragGestures(
                onDragStart = { current = listOf(it) },
                onDrag = { change, _ -> current = current + change.position },
                onDragEnd = {
                    if (current.size >= 2) {
                        // P1-2（review-09）：逆变换用实际画布尺寸（与绘制同源）；字库坐标系为
                        // Y-up（origin 左下，makemeahanzi 官方 scale(1,-1)），Canvas 为 Y-down——
                        // 翻转 Y 后评估坐标与显示一致
                        val side = if (canvasSize.width > 0f) minOf(canvasSize.width, canvasSize.height) else gridSidePx
                        val left = (size.width - side) / 2
                        val top = (size.height - side) / 2
                        val scale = side / 1024f
                        val libraryPath = current.map {
                            // review-10 P1-4：字库 Y 范围 [-124, 900]（非 0~1024）——以 900 为翻转锚点，
                            // 保留负 Y（此前 1024 锚点裁掉底部坐标影响评分）
                            (((it.x - left) / scale).coerceIn(0f, 1024f)) to
                                (900f - ((it.y - top) / scale)).coerceIn(-124f, 900f)
                        }
                        drawnStrokes = drawnStrokes + listOf(current)
                        onStrokeCountChanged(drawnStrokes.size)
                        onStrokeComplete(libraryPath)
                    }
                    current = emptyList()
                },
                onDragCancel = { current = emptyList() },
            )
        },
    ) {
        val side = size.minDimension
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val scale = side / 1024f   // 字库坐标系约 0-1024

        // 米字格：外框 + 十字 + 对角线
        drawRect(color = gridColor, style = Stroke(width = 2f))
        drawLine(gridColor, Offset(left, top + side / 2), Offset(left + side, top + side / 2), 1f)
        drawLine(gridColor, Offset(left + side / 2, top), Offset(left + side / 2, top + side), 1f)
        drawLine(gridColor, Offset(left, top), Offset(left + side, top + side), 1f)
        drawLine(gridColor, Offset(left + side, top), Offset(left, top + side), 1f)

        // 字库笔画（预解析 Path 缓存 → withTransform 缩放平移，拖动帧零解析）
        if (showOutline || revealStrokes != 0) {
            val visible = if (showOutline) strokes.size else revealStrokes.coerceAtMost(strokes.size)
            for (i in 0 until visible) {
                val path = parsedStrokes.getOrNull(i) ?: continue
                val color = if (i < revealStrokes) strokeColor else guideColor
                withTransform({
                    // P1-2（review-09）：字库 Y-up（origin 左下）→ Canvas Y-down：scale(1,-1) 翻转。
                    // review-10 P1-4：锚点用 900（数据范围 -124~900，y=900 顶部、y=-124 底部正好贴画布底）
                    translate(left, top + 900f * scale)
                    scale(scale, -scale)
                }) {
                    drawPath(path, color)
                }
            }
        }

        // 用户手写轨迹（已完成笔画 + 当前笔画，Canvas 像素显示）
        (drawnStrokes + listOf(current)).forEach { stroke ->
            if (stroke.size >= 2) {
                val path = Path()
                path.moveTo(stroke.first().x, stroke.first().y)
                stroke.drop(1).forEach { path.lineTo(it.x, it.y) }
                drawPath(path, userColor, style = Stroke(width = 6f))
            }
        }
    }
}
