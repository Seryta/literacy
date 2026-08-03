package com.literacy.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── 三个视觉方向（给用户挑选）───────────────────────────────────────
// 共同约束：文字 vs 背景对比度 ≥ 4.5:1（WCAG AA），适合视力下降的中老年用户。
// 差异：主色气质、背景冷暖、视觉性格。

// ── 方案 A：暖琥珀 · 书香 ───────────────────────────────────────────
// 米白纸感 + 琥珀橙，温暖、传统教育感（当前已实现的版本）
object SchemeWarm {
    val primary = Color(0xFFB45309)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFFFE0B2)
    val onPrimaryContainer = Color(0xFF3E1F00)
    val secondary = Color(0xFF4E6E58)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFD7E8DB)
    val onSecondaryContainer = Color(0xFF10301D)
    val tertiary = Color(0xFF8C5A2B)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFF6DDC2)
    val onTertiaryContainer = Color(0xFF3A2408)
    val background = Color(0xFFFDF8F0)
    val onBackground = Color(0xFF241A10)
    val surface = Color(0xFFFDF8F0)
    val onSurface = Color(0xFF241A10)
    val surfaceVariant = Color(0xFFF0E4D4)
    val onSurfaceVariant = Color(0xFF554B3F)
    val outline = Color(0xFF8A7F70)
    val outlineVariant = Color(0xFFD5C9B8)
}

// ── 方案 B：清新青绿 · 现代 ─────────────────────────────────────────
// 浅青白 + 深青绿主色，暖橙点缀，冷静、现代学习工具感
object SchemeFresh {
    val primary = Color(0xFF2F6B5E)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFD0E9E0)
    val onPrimaryContainer = Color(0xFF0B3A30)
    val secondary = Color(0xFFC97B4A)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFFBE3D2)
    val onSecondaryContainer = Color(0xFF4A2410)
    val tertiary = Color(0xFF5B6E8C)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFDCE4F2)
    val onTertiaryContainer = Color(0xFF1A2A42)
    val background = Color(0xFFF4FAF7)
    val onBackground = Color(0xFF1D2622)
    val surface = Color(0xFFF4FAF7)
    val onSurface = Color(0xFF1D2622)
    val surfaceVariant = Color(0xFFE3EDE8)
    val onSurfaceVariant = Color(0xFF4C5A54)
    val outline = Color(0xFF7B8A83)
    val outlineVariant = Color(0xFFCBD9D2)
}

// ── 方案 C：活力橙 · 高对比 ─────────────────────────────────────────
// 亮橙红 + 暖白底，深蓝辅助，高饱和高对比，对视弱用户最醒目
object SchemeVivid {
    val primary = Color(0xFFC8400E)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFFFDBC8)
    val onPrimaryContainer = Color(0xFF3E1600)
    val secondary = Color(0xFF27486B)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFD4E3F5)
    val onSecondaryContainer = Color(0xFF0E2A48)
    val tertiary = Color(0xFF3E6B27)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFC9E8B8)
    val onTertiaryContainer = Color(0xFF163500)
    val background = Color(0xFFFFF7F0)
    val onBackground = Color(0xFF241812)
    val surface = Color(0xFFFFF7F0)
    val onSurface = Color(0xFF241812)
    val surfaceVariant = Color(0xFFF2E3D8)
    val onSurfaceVariant = Color(0xFF5B4A40)
    val outline = Color(0xFF8F7C70)
    val outlineVariant = Color(0xFFDDCBBE)
}

// 米字格专用（中性色，三套方案共用，保持笔画视觉稳定）
val GridLine = Color(0xFF9E948A)         // 网格线
val GridStroke = Color(0xFF4A4238)       // 字库笔画
val GridGuide = Color(0xFFC9C0B4)        // 未揭示笔画的引导轮廓
val GridUserStroke = Color(0xFF1E6FB8)   // 用户手写轨迹（蓝，与字库笔画区分）
