package com.leyu.melora.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全局外观调色板：由 [MeloraTheme] 按自动/浅色/深色模式切换。
 * 各页面通过只读 getter 引用，变更后读取了快照状态的 Composable 自动重组。
 */
object MeloraAppearance {
    var isDark: Boolean by mutableStateOf(false)
        internal set

    val canvas: Color get() = if (isDark) Color(0xFF000000) else Color(0xFFF4F5F7)
    val card: Color get() = if (isDark) Color(0xFF191C23) else Color.White
    val textMain: Color get() = if (isDark) Color(0xFFF2F4F7) else Color(0xFF111827)
    val textSub: Color get() = if (isDark) Color(0xFF9BA4B4) else Color(0xFF6B7280)
    val textMuted: Color get() = if (isDark) Color(0xFF6C7482) else Color(0xFF9CA3AF)
    val divider: Color get() = if (isDark) Color(0xFF262B34) else Color(0xFFF3F4F6)
    val brand: Color get() = if (isDark) Color(0xFF6E9BFF) else Color(0xFF2563EB)
    val accent: Color get() = Color(0xFFE53935)
    val segmentTrack: Color get() = if (isDark) Color(0xFF22262E) else Color(0xFFE5E7EB)
    val segmentThumb: Color get() = if (isDark) Color(0xFF353D4B) else Color.White
    val softFill: Color get() = if (isDark) Color(0xFF232830) else Color(0xFFF1F3F6)
    // 骨架屏底色：需同时与画布和白色卡片拉开对比，softFill 放画布上几乎不可见
    val skeleton: Color get() = if (isDark) Color(0xFF262C36) else Color(0xFFE6E9EF)
    val tintBlue: Color get() = if (isDark) Color(0xFF1C2739) else Color(0xFFEFF4FE)
    val tintRed: Color get() = if (isDark) Color(0xFF3A1F22) else Color(0xFFFEE2E2)

    // 现代化极简卡片微描边：替代发脏的不对称单向阴影，四边完全均匀细腻
    val cardBorderColor: Color get() = if (isDark) Color(0xFF282F3B) else Color(0x0D000000)
    val cardBorder: BorderStroke get() = BorderStroke(0.6.dp, cardBorderColor)
    val chipBorderColor: Color get() = if (isDark) Color(0xFF2A313E) else Color(0x0A000000)
    val chipBorder: BorderStroke get() = BorderStroke(0.5.dp, chipBorderColor)
}
