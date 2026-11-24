package com.leyu.melora.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.AudioQualityBadge
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.QualityBadgeMode
import com.leyu.melora.ui.theme.MeloraAppearance

internal val LocalBadgeThemeDark = compositionLocalOf<Boolean?> { null }
internal val QualityBadgeHeight = 18.dp
private val BadgeShape = RoundedCornerShape(4.dp)
private val CompactBadgeWeight = FontWeight(650)

@Composable
internal fun BadgePill(
    badge: AudioQualityBadge,
    modifier: Modifier = Modifier,
    mode: QualityBadgeMode? = null,
    height: Dp = QualityBadgeHeight,
    outlined: Boolean = false,
) {
    val resolvedMode = mode ?: MeloraSettings.qualityBadgeMode.collectAsStateWithLifecycle().value
    val dark = LocalBadgeThemeDark.current ?: MeloraAppearance.isDark
    val compact = resolvedMode == QualityBadgeMode.Compact
    // 同一音轨的各个样式共用档位颜色；图文模式的图标仍取自实际编码。
    val palette = audioBadgeAppearance(badge.compactTier).palette
    val ink = if (dark) palette.dark else palette.light
    val badgeSurface = if (outlined) {
        Modifier.border(1.dp, ink.copy(alpha = 0.55f), BadgeShape)
    } else {
        val alpha = if (dark) palette.darkFillAlpha else palette.lightFillAlpha
        Modifier.background(ink.copy(alpha = alpha), BadgeShape)
    }
    if (compact) {
        val description = buildList {
            add(badge.label)
            val spec = badge.shortSpec?.takeIf { it.isNotBlank() && !badge.label.endsWith(it) }
            val codec = badge.splitLabel.takeIf { spec != null && it != badge.label }
            if (codec != null) add(codec)
            if (spec != null) add(spec)
        }.joinToString(" · ")
        Box(
            modifier = modifier
                .clearAndSetSemantics { contentDescription = description }
                .height(17.dp)
                .then(badgeSurface)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            BadgeText(badge.compactLabel, ink, 9.25.dp, weight = CompactBadgeWeight, letterSpacing = 0.05.em)
        }
        return
    }
    val scale = height / QualityBadgeHeight
    val spec = badge.shortSpec?.takeIf { resolvedMode == QualityBadgeMode.Split && it.isNotBlank() }
    val label = if (spec == null) badge.label else badge.splitLabel
    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = if (spec == null) label else "$label · $spec" }
            .height(height)
            .then(badgeSurface)
            .padding(horizontal = 5.5.dp * scale),
        horizontalArrangement = Arrangement.spacedBy(3.5.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(audioBadgeAppearance(badge.tier).icon, contentDescription = null, modifier = Modifier.size(10.5.dp * scale), tint = ink)
        BadgeText(label, ink, 9.5.dp * scale)
        if (spec != null) {
            Box(Modifier.size(1.dp, 7.dp * scale).background(ink.copy(alpha = 0.28f), CircleShape))
            BadgeText(spec, ink, 8.5.dp * scale, specification = true)
        }
    }
}

@Composable
internal fun LocalBadgePill(modifier: Modifier = Modifier) {
    val dark = LocalBadgeThemeDark.current ?: MeloraAppearance.isDark
    val ink = if (dark) Color(0xFFADB5C3) else Color(0xFF586273)
    Box(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = "本地" }
            .height(QualityBadgeHeight)
            .background(ink.copy(alpha = if (dark) 0.105f else 0.085f), BadgeShape)
            .padding(horizontal = 5.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        BadgeText("本地", ink, 9.5.dp)
    }
}

@Composable
private fun BadgeText(
    text: String,
    ink: Color,
    size: Dp,
    specification: Boolean = false,
    weight: FontWeight = if (specification) FontWeight.Medium else FontWeight.SemiBold,
    letterSpacing: TextUnit = if (specification) 0.02.em else 0.055.em,
) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    BasicText(
        text = text,
        style = TextStyle(
            color = ink,
            fontSize = fontSize,
            fontWeight = weight,
            fontFamily = if (specification) FontFamily.Monospace else FontFamily.Default,
            fontFeatureSettings = "tnum",
            letterSpacing = letterSpacing,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
