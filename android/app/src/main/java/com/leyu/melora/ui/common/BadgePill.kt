package com.leyu.melora.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    "MASTER" -> if (isDark) Color(0xFFFCD34D) else Color(0xFFB45309)
    "LOCAL", "本地" -> if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A)
    else -> if (isBitrateBadge(text)) {
        if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A)
    } else if (isDark) Color(0xFFCBD5E1) else Color(0xFF64748B)
}

/**
 * 已有徽标沿用原转曲矢量；其他实测码率按真实文字绘制，不再遗漏非固定码率。
 * 共用13dp高度、细描边与主题配色，数字徽标保留26dp最小宽度避免VBR数值变化挤动副标题。
 */
private data class BadgeVector(val vector: ImageVector, val viewBoxWidth: Float, val viewBoxHeight: Float)

private fun badgeVector(text: String): BadgeVector? = when (text.uppercase()) {
    "HR" -> BadgeVector(BadgeHr, 22f, 13f)
    "MASTER" -> BadgeVector(BadgeMaster, 44f, 13f)
    "SQ" -> BadgeVector(BadgeSq, 20f, 13f)
    "HQ" -> BadgeVector(BadgeHq, 20f, 13f)
    "128K" -> BadgeVector(Badge128K, 26f, 13f)
    "320K" -> BadgeVector(Badge320K, 28f, 13f)
    else -> null
}

private fun isBitrateBadge(text: String): Boolean =
    text.endsWith("K", ignoreCase = true) && text.dropLast(1).toIntOrNull()?.let { it > 0 } == true

@Composable
internal fun BadgePill(
    text: String,
    modifier: Modifier = Modifier,
    height: Dp = 13.dp,
    tint: Color? = null,
) {
    val badge = badgeVector(text)
    if (badge == null && !isBitrateBadge(text)) return
    val isDark = LocalBadgeThemeDark.current ?: MeloraAppearance.isDark
    val resolvedTint = tint ?: badgeThemeColor(text, isDark)
    if (badge != null) {
        val scale = height.value / badge.viewBoxHeight
        Image(
            imageVector = badge.vector,
            contentDescription = text,
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(resolvedTint),
            modifier = modifier.width((badge.viewBoxWidth * scale).dp).height(height),
        )
    } else {
        val label = text.uppercase()
        val scale = height.value / 13f
        val density = LocalDensity.current
        Box(
            modifier = modifier
                .width(((8f + maxOf(4, label.length) * 4.5f) * scale).dp)
                .height(height)
                .border(scale.dp, resolvedTint, RoundedCornerShape((2.5f * scale).dp))
                .semantics { contentDescription = text },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = label,
                modifier = Modifier.clearAndSetSemantics { },
                style = TextStyle(
                    color = resolvedTint,
                    fontSize = with(density) { (8.5f * scale).dp.toSp() },
                    fontWeight = FontWeight.SemiBold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
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
