package com.leyu.melora.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object MeloraAppearance {
    var isDark: Boolean by mutableStateOf(false)
        internal set

    val canvas: Color get() = if (isDark) MeloraSurfaceDark else Color(0xFFF4F5F7)
    val card: Color get() = if (isDark) MeloraCardDark else Color.White
    internal val sheet: Color get() = if (isDark) Color(0xFF141416) else canvas
    internal val sheetCard: Color get() = if (isDark) Color(0xFF1E1E21) else card
    val textMain: Color get() = if (isDark) Color(0xFFEBEBEF) else Color(0xFF111827)
    val textSub: Color get() = if (isDark) Color(0xFF9898A0) else Color(0xFF6B7280)
    val textMuted: Color get() = if (isDark) Color(0xFF63636B) else Color(0xFF9CA3AF)
    val divider: Color get() = if (isDark) Color(0x0FFFFFFF) else Color(0xFFF3F4F6)
    val brand: Color get() = if (isDark) MeloraBlueDark else MeloraBlue
    val accent: Color get() = if (isDark) Color(0xFFF2635C) else Color(0xFFE53935)
    val segmentTrack: Color get() = if (isDark) Color(0xFF1A1A1D) else Color(0xFFE5E7EB)
    val segmentThumb: Color get() = if (isDark) Color(0xFF2A2A2E) else Color.White
    val softFill: Color get() = if (isDark) Color(0xFF1E1E22) else Color(0xFFF1F3F6)
    // 骨架屏底色：需同时与画布和卡片拉开对比，softFill 放画布上几乎不可见
    val skeleton: Color get() = if (isDark) Color(0xFF242428) else Color(0xFFE6E9EF)
    val tintBlue: Color get() = if (isDark) Color(0xFF1C2536) else Color(0xFFEFF4FE)
    val tintRed: Color get() = if (isDark) Color(0xFF2D191D) else Color(0xFFFEE2E2)

    // 现代化极简卡片微描边：替代发脏的不对称单向阴影，深浅统一半透明细边
    val cardBorderColor: Color get() = if (isDark) Color(0x08FFFFFF) else Color(0x0D000000)
    val cardBorder: BorderStroke get() = BorderStroke(0.6.dp, cardBorderColor)
    val chipBorderColor: Color get() = if (isDark) Color(0x0AFFFFFF) else Color(0x0A000000)
    val chipBorder: BorderStroke get() = BorderStroke(0.5.dp, chipBorderColor)
}

internal object LightSheetFrost {
    val base = Color(0xFFF6F7FA)
    val tint = base.copy(alpha = 0.78f)
    val categoryTint = base.copy(alpha = 0.80f)
    private val ink = Color(0xFF0F172A)
    val scrim = ink.copy(alpha = 0.10f)
    val handle = ink.copy(alpha = 0.16f)
    val topRim = Color.White.copy(alpha = 0.92f)
    val card = ink.copy(alpha = 0.045f)
    val chip = ink.copy(alpha = 0.05f)
    val cardBorder = BorderStroke(0.5.dp, ink.copy(alpha = 0.035f))
    val chipBorder = BorderStroke(0.5.dp, ink.copy(alpha = 0.03f))
    val iconWell = Color.White.copy(alpha = 0.72f)
    val blue = Color(0xFF3567E8)
    val activeText = Color(0xFF1D4ED8)
    val activeCard = blue.copy(alpha = 0.12f)
    val activeBorder = BorderStroke(1.dp, blue.copy(alpha = 0.28f))
}

internal object DarkGlass {
    val base = Color(0xFF101012)
    val tint = base.copy(alpha = 0.70f)
    const val scrimAlpha = 0.20f
    val topRim = Color.White.copy(alpha = 0.06f)
    val handle = Color.White.copy(alpha = 0.20f)
    private const val cardAlpha = 0.04f
    val card = Color.White.copy(alpha = cardAlpha)
    val chip = Color.White.copy(alpha = cardAlpha + 0.01f)
    val icon = Color.White.copy(alpha = cardAlpha + 0.02f)
    val border = Color.White.copy(alpha = 0.08f)
    val selectedFill = Color(0xFF72A0FF).copy(alpha = 0.20f)
    val selectedText = Color(0xFFA8C5FF)
    val selectedEdge = MeloraBlueDark.copy(alpha = 0.32f)
}
