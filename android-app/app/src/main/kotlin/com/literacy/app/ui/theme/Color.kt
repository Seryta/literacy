package com.literacy.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── 适老识字 App 配色：温暖书香 + 高对比 ─────────────────────────────
// 目标用户是中老年/不识字的成年学习者：
// - 文字 vs 背景对比度全部 ≥ 4.5:1（WCAG AA），照顾视力下降
// - 主色用暖琥珀橙（教育、亲和、不冰冷），辅助墨绿（复习/次操作）
// - 背景米白纸感，不刺眼，长时间看护眼
val Primary = Color(0xFFB45309)          // 深琥珀橙（按钮/主操作，白字对比 ~4.7:1）
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFFFE0B2) // 浅杏橙（选中态/容器）
val OnPrimaryContainer = Color(0xFF3E1F00)

val Secondary = Color(0xFF4E6E58)        // 墨绿（复习/次要操作）
val OnSecondary = Color(0xFFFFFFFF)
val SecondaryContainer = Color(0xFFD7E8DB)
val OnSecondaryContainer = Color(0xFF10301D)

val Tertiary = Color(0xFF8C5A2B)         // 棕褐（辅助强调，如暂停态）
val OnTertiary = Color(0xFFFFFFFF)
val TertiaryContainer = Color(0xFFF6DDC2)
val OnTertiaryContainer = Color(0xFF3A2408)

val Background = Color(0xFFFDF8F0)       // 米白纸感
val OnBackground = Color(0xFF241A10)     // 暖深棕黑（正文）
val Surface = Color(0xFFFDF8F0)
val OnSurface = Color(0xFF241A10)
val SurfaceVariant = Color(0xFFF0E4D4)   // 浅暖灰（卡片/输入框底）
val OnSurfaceVariant = Color(0xFF554B3F) // 中暖灰（辅助文字，对比 ~6.5:1）
val Outline = Color(0xFF8A7F70)
val OutlineVariant = Color(0xFFD5C9B8)

val Error = Color(0xFFBA1A1A)
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFFFDAD6)
val OnErrorContainer = Color(0xFF410002)

// 米字格专用（与主题分离，保持笔画视觉稳定）
val GridLine = Color(0xFF9E948A)         // 网格线（比默认 #999 更暖）
val GridStroke = Color(0xFF4A4238)       // 字库笔画
val GridGuide = Color(0xFFC9C0B4)        // 未揭示笔画的引导轮廓
val GridUserStroke = Color(0xFF1E6FB8)   // 用户手写轨迹（蓝，与字库笔画区分）
