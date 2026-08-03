package com.literacy.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// 适老识字 App 统一主题（当前仅浅色；深色模式后续按需补充）
private val LiteracyColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
)

@Composable
fun LiteracyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LiteracyColorScheme,
        typography = LiteracyTypography,
        shapes = LiteracyShapes,
        content = content,
    )
}

/** 主操作按钮的标准尺寸：全宽 + 56dp 高度（Material3 默认 40dp 对适老用户偏小）。 */
object LiteracyDimens {
    val ActionButtonHeight = 60.dp
    val IconButtonSize = 56.dp
    val CardPadding = 18.dp
    val ScreenPadding = 20.dp
    val SectionSpacing = 20.dp
}
