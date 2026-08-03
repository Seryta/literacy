package com.literacy.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ── 圆角系统：温和现代，大按钮大圆角 ────────────────────────────────
val LiteracyShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),   // 小胶囊/标签
    small = RoundedCornerShape(12.dp),        // 输入框
    medium = RoundedCornerShape(16.dp),       // 卡片
    large = RoundedCornerShape(20.dp),        // 主按钮 / 大卡片
    extraLarge = RoundedCornerShape(28.dp),   // 全屏卡 / 品牌 logo
)
