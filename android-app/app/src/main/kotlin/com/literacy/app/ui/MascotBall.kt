package com.literacy.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 悬浮吉祥物：代码绘制的扁平卡通角色（矢量，可缩放）。
 * - 4 个候选形象（小怪兽/小狐狸/八爪鱼/小幽灵），Canvas 绘制，扁平插画风（粗描边+高光+腮红）
 * - 浮动动画（上下呼吸，不遮视线）
 * - 可拖拽（用户手动避让遮挡）
 * - 气泡提示（时机性提示文字跟随角色）
 * - 录音态（语音输入时变色/光环）
 */

// ── 角色定义 ────────────────────────────────────────────────────────
enum class MascotVariant(val id: String, val label: String, val tagline: String, val body: Color, val dark: Color, val accent: Color) {
    MONSTER("monster", "小绿", "古怪又爱玩的小怪兽", Color(0xFF7CB342), Color(0xFF558B2F), Color(0xFFAED581)),
    FOX("fox", "小狐", "机灵鬼，最爱教你认字", Color(0xFFF5A623), Color(0xFFD9820B), Color(0xFFFFD180)),
    OCTOPUS("octopus", "阿八", "八只手，帮你写笔画", Color(0xFF9B7EDE), Color(0xFF7E5BC4), Color(0xFFC0A8F0)),
    GHOST("ghost", "小白", "软乎乎，陪你慢慢学", Color(0xFFF2F0EC), Color(0xFFD5D0C8), Color(0xFFE8E4DC)),
}

data class Mascot(val variant: MascotVariant)

object Mascots {
    val candidates: List<Mascot> = MascotVariant.entries.map { Mascot(it) }
    val default: Mascot get() = Mascot(MascotVariant.MONSTER)
}

// 扁平插画外描边色（暖深棕，所有角色统一）
private val Outline = Color(0xFF3A2E28)

/**
 * 卡通角色头像（Canvas 绘制，扁平插画风）。
 * @param variant 角色类型
 * @param listening 录音态（光环）
 * @param size 头像直径
 */
@Composable
fun MascotAvatar(
    variant: MascotVariant,
    modifier: Modifier = Modifier,
    listening: Boolean = false,
    size: Dp = 64.dp,
) {
    val r = MaterialTheme.colorScheme.tertiary
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        // 录音光环
        if (listening) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(r.copy(alpha = 0.25f), radius = size.toPx() / 2, style = Stroke(width = 6f))
            }
        }
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val s = this.size.minDimension
            val cx = this.size.width / 2
            val cy = this.size.height / 2
            when (variant) {
                MascotVariant.MONSTER -> drawMonster(cx, cy, s, variant)
                MascotVariant.FOX -> drawFox(cx, cy, s, variant)
                MascotVariant.OCTOPUS -> drawOctopus(cx, cy, s, variant)
                MascotVariant.GHOST -> drawGhost(cx, cy, s, variant)
            }
        }
    }
}

// ── 小怪兽：圆身 + 双角 + 大眼 + 露牙笑（古怪） ─────────────────────
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMonster(cx: Float, cy: Float, s: Float, v: MascotVariant) {
    val bodyW = s * 0.78f
    val bodyH = s * 0.66f
    // 角（后层）
    drawPath(Path().apply {
        moveTo(cx - bodyW * 0.30f, cy - bodyH * 0.48f)
        lineTo(cx - bodyW * 0.38f, cy - bodyH * 0.95f)
        lineTo(cx - bodyW * 0.16f, cy - bodyH * 0.60f)
        close()
    }, v.dark)
    drawPath(Path().apply {
        moveTo(cx + bodyW * 0.30f, cy - bodyH * 0.48f)
        lineTo(cx + bodyW * 0.38f, cy - bodyH * 0.95f)
        lineTo(cx + bodyW * 0.16f, cy - bodyH * 0.60f)
        close()
    }, v.dark)
    // 身体（渐变 + 描边）
    val bodyRect = Rect(cx - bodyW / 2, cy - bodyH / 2, cx + bodyW / 2, cy + bodyH / 2)
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(v.accent, v.body)),
        topLeft = bodyRect.topLeft,
        size = bodyRect.size,
        cornerRadius = CornerRadius(s * 0.30f),
    )
    drawRoundRect(
        color = Outline,
        topLeft = bodyRect.topLeft,
        size = bodyRect.size,
        cornerRadius = CornerRadius(s * 0.30f),
        style = Stroke(width = s * 0.045f),
    )
    // 眼睛
    drawEye(cx - bodyW * 0.22f, cy - bodyH * 0.05f, s * 0.105f, s)
    drawEye(cx + bodyW * 0.22f, cy - bodyH * 0.05f, s * 0.105f, s)
    // 腮红
    drawCircle(Color(0xFFE57373).copy(alpha = 0.55f), radius = s * 0.07f, center = Offset(cx - bodyW * 0.36f, cy + bodyH * 0.16f))
    drawCircle(Color(0xFFE57373).copy(alpha = 0.55f), radius = s * 0.07f, center = Offset(cx + bodyW * 0.36f, cy + bodyH * 0.16f))
    // 露牙笑
    val smileY = cy + bodyH * 0.12f
    drawArc(
        color = Outline, startAngle = 20f, sweepAngle = 140f, useCenter = false,
        topLeft = Offset(cx - bodyW * 0.20f, smileY - bodyH * 0.10f),
        size = Size(bodyW * 0.40f, bodyH * 0.24f),
        style = Stroke(width = s * 0.04f, cap = StrokeCap.Round),
    )
    // 小牙齿
    drawRoundRect(Outline, Offset(cx - s * 0.045f, smileY + s * 0.02f), Size(s * 0.045f, s * 0.055f), CornerRadius(s * 0.012f))
    drawRoundRect(Outline, Offset(cx + s * 0.002f, smileY + s * 0.02f), Size(s * 0.045f, s * 0.055f), CornerRadius(s * 0.012f))
}

// ── 小狐狸：尖耳圆脸 + 白口鼻 + 大眼（亲和） ────────────────────────
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFox(cx: Float, cy: Float, s: Float, v: MascotVariant) {
    val headW = s * 0.74f
    val headH = s * 0.70f
    val top = cy - headH / 2
    // 耳朵（外）
    drawPath(Path().apply {
        moveTo(cx - headW * 0.36f, top + headH * 0.10f)
        lineTo(cx - headW * 0.52f, top - headH * 0.40f)
        lineTo(cx - headW * 0.12f, top + headH * 0.02f)
        close()
    }, v.body)
    drawPath(Path().apply {
        moveTo(cx + headW * 0.36f, top + headH * 0.10f)
        lineTo(cx + headW * 0.52f, top - headH * 0.40f)
        lineTo(cx + headW * 0.12f, top + headH * 0.02f)
        close()
    }, v.body)
    // 耳朵（内粉）
    drawPath(Path().apply {
        moveTo(cx - headW * 0.34f, top + headH * 0.08f)
        lineTo(cx - headW * 0.44f, top - headH * 0.26f)
        lineTo(cx - headW * 0.18f, top + headH * 0.02f)
        close()
    }, v.accent)
    drawPath(Path().apply {
        moveTo(cx + headW * 0.34f, top + headH * 0.08f)
        lineTo(cx + headW * 0.44f, top - headH * 0.26f)
        lineTo(cx + headW * 0.18f, top + headH * 0.02f)
        close()
    }, v.accent)
    // 头
    drawOval(Brush.verticalGradient(listOf(v.accent, v.body)), topLeft = Offset(cx - headW / 2, top), size = Size(headW, headH))
    drawOval(Outline, topLeft = Offset(cx - headW / 2, top), size = Size(headW, headH), style = Stroke(width = s * 0.045f))
    // 白色口鼻部
    drawOval(
        Color(0xFFFFF8EF),
        topLeft = Offset(cx - headW * 0.30f, cy + headH * 0.12f),
        size = Size(headW * 0.60f, headH * 0.36f),
    )
    // 眼睛（大圆眼 + 高光）
    drawEye(cx - headW * 0.20f, cy - headH * 0.08f, s * 0.10f, s)
    drawEye(cx + headW * 0.20f, cy - headH * 0.08f, s * 0.10f, s)
    // 鼻子 + 嘴
    drawCircle(Outline, radius = s * 0.028f, center = Offset(cx, cy + headH * 0.12f))
    drawArc(Outline, 30f, 120f, false, Offset(cx - s * 0.06f, cy + headH * 0.14f), Size(s * 0.12f, s * 0.09f), style = Stroke(width = s * 0.03f, cap = StrokeCap.Round))
    // 腮红
    drawCircle(Color(0xFFE57373).copy(alpha = 0.5f), radius = s * 0.055f, center = Offset(cx - headW * 0.36f, cy + headH * 0.10f))
    drawCircle(Color(0xFFE57373).copy(alpha = 0.5f), radius = s * 0.055f, center = Offset(cx + headW * 0.36f, cy + headH * 0.10f))
}

// ── 八爪鱼：圆头 + 波浪触手 + 呆萌眼（古怪亲和） ─────────────────────
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOctopus(cx: Float, cy: Float, s: Float, v: MascotVariant) {
    val headR = s * 0.36f
    val headC = Offset(cx, cy - s * 0.06f)
    // 触手（波浪，后层）
    val tentacle = Path()
    val baseY = headC.y + headR * 0.55f
    val w = s * 0.34f
    for (i in 0 until 5) {
        val tx = cx - s * 0.34f + i * w * 0.42f
        tentacle.moveTo(tx, baseY)
        tentacle.quadraticBezierTo(tx - s * 0.03f, baseY + s * 0.32f, tx + s * 0.05f, baseY + s * 0.28f)
    }
    drawPath(tentacle, v.body)
    // 头
    drawCircle(Brush.verticalGradient(listOf(v.accent, v.body)), radius = headR, center = headC)
    drawCircle(Outline, radius = headR, center = headC, style = Stroke(width = s * 0.045f))
    // 眼睛（呆萌大眼）
    drawEye(cx - s * 0.13f, headC.y - s * 0.02f, s * 0.11f, s)
    drawEye(cx + s * 0.13f, headC.y - s * 0.02f, s * 0.11f, s)
    // 嘴（小 O 形）
    drawCircle(Outline, radius = s * 0.045f, center = Offset(cx, headC.y + s * 0.16f))
    // 腮红
    drawCircle(Color(0xFFE57373).copy(alpha = 0.5f), radius = s * 0.06f, center = Offset(cx - s * 0.24f, headC.y + s * 0.12f))
    drawCircle(Color(0xFFE57373).copy(alpha = 0.5f), radius = s * 0.06f, center = Offset(cx + s * 0.24f, headC.y + s * 0.12f))
}

// ── 小幽灵：椭圆身 + 波浪底 + 呆萌眼（软萌） ────────────────────────
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGhost(cx: Float, cy: Float, s: Float, v: MascotVariant) {
    val w = s * 0.60f
    val h = s * 0.78f
    val top = cy - h / 2
    val body = Path().apply {
        // 椭圆上部 + 波浪底
        moveTo(cx - w / 2, top + h * 0.45f)
        // 左弧到顶
        arcTo(
            Rect(cx - w / 2, top, w, h * 0.9f),
            startAngleDegrees = 180f, sweepAngleDegrees = 180f, forceMoveTo = false,
        )
        // 波浪底
        val baseY = top + h * 0.78f
        lineTo(cx + w * 0.20f, baseY)
        quadraticBezierTo(cx + w * 0.30f, baseY + h * 0.10f, cx + w * 0.42f, baseY)
        lineTo(cx + w * 0.02f, baseY)
        quadraticBezierTo(cx + w * 0.12f, baseY + h * 0.10f, cx + w * 0.24f, baseY)
        lineTo(cx - w * 0.42f, baseY)
        quadraticBezierTo(cx - w * 0.30f, baseY + h * 0.10f, cx - w * 0.20f, baseY)
        lineTo(cx - w / 2, baseY)
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(Color.White, v.body)))
    drawPath(body, Outline, style = Stroke(width = s * 0.045f))
    // 眼睛（黑色呆萌圆眼）
    drawCircle(Outline, radius = s * 0.07f, center = Offset(cx - s * 0.13f, cy - h * 0.10f))
    drawCircle(Outline, radius = s * 0.07f, center = Offset(cx + s * 0.13f, cy - h * 0.10f))
    drawCircle(Color.White, radius = s * 0.024f, center = Offset(cx - s * 0.11f, cy - h * 0.13f))
    drawCircle(Color.White, radius = s * 0.024f, center = Offset(cx + s * 0.15f, cy - h * 0.13f))
    // 嘴（小 O）
    drawOval(Outline, Offset(cx - s * 0.045f, cy + h * 0.02f), Size(s * 0.09f, s * 0.12f))
    // 腮红
    drawCircle(Color(0xFFE57373).copy(alpha = 0.4f), radius = s * 0.055f, center = Offset(cx - s * 0.24f, cy))
    drawCircle(Color(0xFFE57373).copy(alpha = 0.4f), radius = s * 0.055f, center = Offset(cx + s * 0.24f, cy))
}

/** 通用大眼绘制（白底 + 黑瞳 + 高光）。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEye(cx: Float, cy: Float, r: Float, s: Float) {
    drawCircle(Color.White, radius = r, center = Offset(cx, cy))
    drawCircle(Outline, radius = r * 0.62f, center = Offset(cx, cy))
    drawCircle(Color.White, radius = r * 0.22f, center = Offset(cx - r * 0.22f, cy - r * 0.24f))
}

/** 悬浮吉祥物组件：浮动动画 + 拖拽避让 + 气泡提示 + 录音态。 */
@Composable
fun MascotBall(
    mascot: Mascot,
    listening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val v = mascot.variant
    // 上下浮动（呼吸感）
    val transition = rememberInfiniteTransition(label = "mascotFloat")
    val floatY by transition.animateFloat(
        initialValue = 0f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mascotFloatY",
    )
    // 拖拽避让（用户拖到哪停哪，松手保持；钳制在屏幕内防拖丢主入口）
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp

    Box(
        modifier = modifier
            .offset { IntOffset(dragOffset.x.roundToInt(), (dragOffset.y + floatY).roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        dragOffset = Offset(
                            (dragOffset.x + drag.x).coerceIn(-screenW * 0.9f, screenW * 0.9f),
                            (dragOffset.y + drag.y).coerceIn(-screenH * 0.9f, screenH * 0.9f),
                        )
                    },
                )
            }
            .size(84.dp)
            .clickable(enabled = true) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        // 角色本体
        MascotAvatar(variant = v, listening = listening, size = 62.dp)
        // 录音状态：右上角红点
        if (listening) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .background(Color(0xFFE53935), CircleShape),
            )
        }
    }
}
