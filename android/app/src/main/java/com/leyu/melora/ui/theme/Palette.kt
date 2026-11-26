package com.leyu.melora.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal class MeloraThemePalette(val isDark: Boolean, val isEInk: Boolean = false) {
    val canvas: Color = when {
        isEInk -> Color.White
        isDark -> MeloraSurfaceDark
        else -> Color(0xFFF4F5F7)
    }
    val card: Color = when {
        isEInk -> Color.White
        isDark -> MeloraCardDark
        else -> Color.White
    }
    internal val sheet: Color = when {
        isEInk -> Color.White
        isDark -> Color(0xFF141416)
        else -> canvas
    }
    internal val sheetCard: Color = when {
        isEInk -> Color.White
        isDark -> Color(0xFF1E1E21)
        else -> card
    }
    val textMain: Color = when {
        isEInk -> Color.Black
        isDark -> Color(0xFFEBEBEF)
        else -> Color(0xFF111827)
    }
    val textSub: Color = when {
        isEInk -> Color(0xFF222222)
        isDark -> Color(0xFF9898A0)
        else -> Color(0xFF6B7280)
    }
    val textMuted: Color = when {
        isEInk -> Color(0xFF4A4A4A)
        isDark -> Color(0xFF63636B)
        else -> Color(0xFF9CA3AF)
    }
    val divider: Color = when {
        isEInk -> Color(0xFFCCCCCC)
        isDark -> Color(0x0FFFFFFF)
        else -> Color(0xFFF3F4F6)
    }
    val brand: Color = when {
        isEInk -> Color.Black
        isDark -> MeloraBlueDark
        else -> MeloraBlue
    }
    val accent: Color = when {
        isEInk -> Color.Black
        isDark -> Color(0xFFF2635C)
        else -> Color(0xFFE53935)
    }
    val segmentTrack: Color = when {
        isEInk -> Color(0xFFE5E5E5)
        isDark -> Color(0xFF1A1A1D)
        else -> Color(0xFFE5E7EB)
    }
    val segmentThumb: Color = when {
        isEInk -> Color.White
        isDark -> Color(0xFF2A2A2E)
        else -> Color.White
    }
    val softFill: Color = when {
        isEInk -> Color(0xFFF2F2F2)
        isDark -> Color(0xFF1E1E22)
        else -> Color(0xFFF1F3F6)
    }
    // 骨架屏底色：需同时与画布和卡片拉开对比，softFill 放画布上几乎不可见
    val skeleton: Color = when {
        isEInk -> Color(0xFFE0E0E0)
        isDark -> Color(0xFF242428)
        else -> Color(0xFFE6E9EF)
    }
    val tintBlue: Color = when {
        isEInk -> Color(0xFFE8E8E8)
        isDark -> Color(0xFF1C2536)
        else -> Color(0xFFEFF4FE)
    }
    val tintRed: Color = when {
        isEInk -> Color(0xFFE8E8E8)
        isDark -> Color(0xFF2D191D)
        else -> Color(0xFFFEE2E2)
    }

    // 现代化极简卡片微描边：替代发脏的不对称单向阴影，深浅统一半透明细边
    val cardBorderColor: Color = when {
        isEInk -> Color(0xFF1F1F1F)
        isDark -> Color(0x08FFFFFF)
        else -> Color(0x0D000000)
    }
    val cardBorder: BorderStroke = BorderStroke(if (isEInk) 1.dp else 0.6.dp, cardBorderColor)
    val chipBorderColor: Color = when {
        isEInk -> Color(0xFF2B2B2B)
        isDark -> Color(0x0AFFFFFF)
        else -> Color(0x0A000000)
    }
    val chipBorder: BorderStroke = BorderStroke(if (isEInk) 0.9.dp else 0.5.dp, chipBorderColor)
}

object MeloraAppearance {
    private val lightPalette = MeloraThemePalette(isDark = false)
    private val darkPalette = MeloraThemePalette(isDark = true)
    private val eInkPalette = MeloraThemePalette(isDark = false, isEInk = true)

    var isDark: Boolean by mutableStateOf(false)
        internal set

    var isEInk: Boolean by mutableStateOf(false)
        internal set

    internal fun palette(dark: Boolean): MeloraThemePalette = when {
        isEInk -> eInkPalette
        dark -> darkPalette
        else -> lightPalette
    }

    private val current: MeloraThemePalette get() = palette(isDark)

    val canvas: Color get() = current.canvas
    val card: Color get() = current.card
    internal val sheet: Color get() = current.sheet
    internal val sheetCard: Color get() = current.sheetCard
    val textMain: Color get() = current.textMain
    val textSub: Color get() = current.textSub
    val textMuted: Color get() = current.textMuted
    val divider: Color get() = current.divider
    val brand: Color get() = current.brand
    val accent: Color get() = current.accent
    val segmentTrack: Color get() = current.segmentTrack
    val segmentThumb: Color get() = current.segmentThumb
    val softFill: Color get() = current.softFill
    val skeleton: Color get() = current.skeleton
    val tintBlue: Color get() = current.tintBlue
    val tintRed: Color get() = current.tintRed
    val cardBorderColor: Color get() = current.cardBorderColor
    val cardBorder: BorderStroke get() = current.cardBorder
    val chipBorderColor: Color get() = current.chipBorderColor
    val chipBorder: BorderStroke get() = current.chipBorder
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
