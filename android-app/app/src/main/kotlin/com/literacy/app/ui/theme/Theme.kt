package com.literacy.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── 视觉方向切换点 ───────────────────────────────────────────────────
// 构建前改这一行，分别出三版截图给用户挑选：
//   ACTIVE_SCHEME = "warm"   → 暖琥珀·书香
//   ACTIVE_SCHEME = "fresh"  → 清新青绿·现代
//   ACTIVE_SCHEME = "vivid"  → 活力橙·高对比
private const val ACTIVE_SCHEME = "warm"

private val LiteracyColorScheme = when (ACTIVE_SCHEME) {
    "fresh" -> lightColorScheme(
        primary = SchemeFresh.primary,
        onPrimary = SchemeFresh.onPrimary,
        primaryContainer = SchemeFresh.primaryContainer,
        onPrimaryContainer = SchemeFresh.onPrimaryContainer,
        secondary = SchemeFresh.secondary,
        onSecondary = SchemeFresh.onSecondary,
        secondaryContainer = SchemeFresh.secondaryContainer,
        onSecondaryContainer = SchemeFresh.onSecondaryContainer,
        tertiary = SchemeFresh.tertiary,
        onTertiary = SchemeFresh.onTertiary,
        tertiaryContainer = SchemeFresh.tertiaryContainer,
        onTertiaryContainer = SchemeFresh.onTertiaryContainer,
        background = SchemeFresh.background,
        onBackground = SchemeFresh.onBackground,
        surface = SchemeFresh.surface,
        onSurface = SchemeFresh.onSurface,
        surfaceVariant = SchemeFresh.surfaceVariant,
        onSurfaceVariant = SchemeFresh.onSurfaceVariant,
        outline = SchemeFresh.outline,
        outlineVariant = SchemeFresh.outlineVariant,
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )
    "vivid" -> lightColorScheme(
        primary = SchemeVivid.primary,
        onPrimary = SchemeVivid.onPrimary,
        primaryContainer = SchemeVivid.primaryContainer,
        onPrimaryContainer = SchemeVivid.onPrimaryContainer,
        secondary = SchemeVivid.secondary,
        onSecondary = SchemeVivid.onSecondary,
        secondaryContainer = SchemeVivid.secondaryContainer,
        onSecondaryContainer = SchemeVivid.onSecondaryContainer,
        tertiary = SchemeVivid.tertiary,
        onTertiary = SchemeVivid.onTertiary,
        tertiaryContainer = SchemeVivid.tertiaryContainer,
        onTertiaryContainer = SchemeVivid.onTertiaryContainer,
        background = SchemeVivid.background,
        onBackground = SchemeVivid.onBackground,
        surface = SchemeVivid.surface,
        onSurface = SchemeVivid.onSurface,
        surfaceVariant = SchemeVivid.surfaceVariant,
        onSurfaceVariant = SchemeVivid.onSurfaceVariant,
        outline = SchemeVivid.outline,
        outlineVariant = SchemeVivid.outlineVariant,
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )
    else -> lightColorScheme(   // warm（默认）
        primary = SchemeWarm.primary,
        onPrimary = SchemeWarm.onPrimary,
        primaryContainer = SchemeWarm.primaryContainer,
        onPrimaryContainer = SchemeWarm.onPrimaryContainer,
        secondary = SchemeWarm.secondary,
        onSecondary = SchemeWarm.onSecondary,
        secondaryContainer = SchemeWarm.secondaryContainer,
        onSecondaryContainer = SchemeWarm.onSecondaryContainer,
        tertiary = SchemeWarm.tertiary,
        onTertiary = SchemeWarm.onTertiary,
        tertiaryContainer = SchemeWarm.tertiaryContainer,
        onTertiaryContainer = SchemeWarm.onTertiaryContainer,
        background = SchemeWarm.background,
        onBackground = SchemeWarm.onBackground,
        surface = SchemeWarm.surface,
        onSurface = SchemeWarm.onSurface,
        surfaceVariant = SchemeWarm.surfaceVariant,
        onSurfaceVariant = SchemeWarm.onSurfaceVariant,
        outline = SchemeWarm.outline,
        outlineVariant = SchemeWarm.outlineVariant,
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )
}

@Composable
fun LiteracyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LiteracyColorScheme,
        typography = LiteracyTypography,
        shapes = LiteracyShapes,
        content = content,
    )
}

/** 主操作按钮的标准尺寸：全宽 + 60dp 高度（Material3 默认 40dp 对适老用户偏小）。 */
object LiteracyDimens {
    val ActionButtonHeight = 60.dp
    val IconButtonSize = 56.dp
    val CardPadding = 18.dp
    val ScreenPadding = 20.dp
    val SectionSpacing = 20.dp
}
