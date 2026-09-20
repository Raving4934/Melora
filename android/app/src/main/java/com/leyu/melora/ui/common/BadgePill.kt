package com.leyu.melora.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leyu.melora.ui.theme.MeloraAppearance

internal val LocalBadgeThemeDark = compositionLocalOf<Boolean?> { null }

/**
 * 徽标双模自适应色彩系统：
 * - 在全屏播放页动态背景下跟随播放器明暗自适应；
 * - 在列表与常态页面下跟随全局深浅色主题自适应；
 * - 绝不使用突兀的高对比白色，保留优雅克制的品味与高辨识度。
 */
internal fun badgeThemeColor(text: String, isDark: Boolean): Color = when (text.uppercase()) {
    "HR" -> if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309) // 琥珀金：深色下温润明朗，浅色下沉稳醇厚
    "SQ" -> if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7) // 青碧蓝：深色下清澈明亮，浅色下清晰雅致
    "HQ" -> if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB) // 曜空蓝：深色下避开暗沉，浅色下纯净饱满
    "128K", "320K" -> if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A) // 银石灰：深色下高透银灰，浅色下利落坚实
    "MASTER" -> if (isDark) Color(0xFFFCD34D) else Color(0xFFB45309)
    "LOCAL", "本地" -> if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A)
    else -> if (isDark) Color(0xFFCBD5E1) else Color(0xFF64748B)
}

/**
 * 徽标：使用 Badges.kt 中的 ImageVector（Google Sans 转曲矢量，无资源文件）。
 * 设计基准 13dp 高，等比缩放：HR 22 / SQ 20 / MASTER 44 / 320K·128K 28 / 本地 24（宽）。
 */
private data class BadgeVector(val vector: ImageVector, val viewBoxWidth: Float, val viewBoxHeight: Float)

private fun badgeVector(text: String): BadgeVector? = when (text.uppercase()) {
    "HR" -> BadgeVector(BadgeHr, 22f, 13f)
    "MASTER" -> BadgeVector(BadgeMaster, 44f, 13f)
    "SQ" -> BadgeVector(BadgeSq, 20f, 13f)
    "HQ" -> BadgeVector(BadgeHq, 20f, 13f)
    "320K" -> BadgeVector(Badge320K, 28f, 13f) // 旧档位保留兼容，当前分档统一用 HQ
    "128K" -> BadgeVector(Badge128K, 26f, 13f)
    else -> null
}

@Composable
internal fun BadgePill(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 13.dp,
    tint: Color? = null,
) {
    val badge = badgeVector(text) ?: return
    val scale = height.value / badge.viewBoxHeight
    val isDark = LocalBadgeThemeDark.current ?: MeloraAppearance.isDark
    val resolvedTint = tint ?: badgeThemeColor(text, isDark)
    Image(
        imageVector = badge.vector,
        contentDescription = text,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(resolvedTint),
        modifier = modifier
            .width((badge.viewBoxWidth * scale).dp)
            .height(height),
    )
}

/** 「本地」来源徽标：BadgeLocal（24×13 设计基准）。 */
@Composable
internal fun LocalBadgePill(modifier: Modifier = Modifier, height: Dp = 13.dp) {
    val scale = height.value / 13f
    val isDark = LocalBadgeThemeDark.current ?: MeloraAppearance.isDark
    val resolvedTint = badgeThemeColor("LOCAL", isDark)
    Image(
        imageVector = BadgeLocal,
        contentDescription = "本地",
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(resolvedTint),
        modifier = modifier
            .width((24f * scale).dp)
            .height(height),
    )
}
