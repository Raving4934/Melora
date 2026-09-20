package com.leyu.melora.ui.player

import com.leyu.melora.ui.common.LocalBadgeThemeDark
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 播放器自己的颜色令牌；不读取或修改全局 MeloraAppearance。
 *
 * [artworkColor] 只来自全屏背景已经加载并模糊过的 128px 封面采样。这里不做图片处理，
 * 只把代表色一次性派生为浅/深主题的同一套材质令牌，避免 page0 与 queue page3 各自配色。
 */
internal data class PlayerBackdropOverlay(
    val baseColor: Color,
    val artworkAlpha: Float,
    val scrimStops: List<Pair<Float, Color>>,
)

internal data class PlayerColors(
    val isDark: Boolean,
    val background: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val progressActive: Color,
    val progressInactive: Color,
    val cardSurface: Color,
    val cardSelected: Color,
    val queueSelected: Color,
    val divider: Color,
    val outline: Color,
    val accent: Color,
    val accentSoft: Color,
    val sheetBackground: Color,
    val sheetText: Color,
    val sheetTextMuted: Color,
    val sheetTextFaint: Color,
    val segmentTrack: Color,
    val softFill: Color,
    val tintBlue: Color,
    val cardBorder: BorderStroke,
    val chipBorder: BorderStroke,
    val backdrop: PlayerBackdropOverlay,
)

private val DarkBackdropFallback = Color(0xFF13151A)
private val LightBackdropFallback = Color(0xFFF0F2F3)
private val DarkInkFallback = Color(0xFFF2F4F7)
private val LightInkFallback = Color(0xFF24313A)

/** 无封面时的兼容令牌；动态令牌通过 [playerColorsFor] 派生。 */
internal val DarkPlayerColors = buildDarkPlayerColors(null)
internal val LightPlayerColors = buildLightPlayerColors(null)

/**
 * 由封面代表色生成播放器令牌。
 *
 * 低饱和、透明、过暗或非有限颜色会被视为“无可用色”，保留柔和中性回退，避免浅色页
 * 因灰色封面变脏。卡片都使用同一色相的低 alpha 层，但选中态只提高 alpha，避免白色实心
 * 卡片盖住背景。
 */
internal fun playerColorsFor(dark: Boolean, artworkColor: Color? = null): PlayerColors {
    val tone = normalizePlayerArtworkColor(artworkColor)
    return if (dark) buildDarkPlayerColors(tone) else buildLightPlayerColors(tone)
}

/**
 * 只接受足够不透明、具有可辨色相的颜色。该函数保持无 Android/网络依赖，便于单元测试。
 */
internal fun normalizePlayerArtworkColor(color: Color?): Color? {
    if (color == null) return null
    if (!color.red.isFinite() || !color.green.isFinite() || !color.blue.isFinite() || !color.alpha.isFinite()) {
        return null
    }
    if (color.alpha < 0.35f) return null
    val normalized = color.copy(alpha = 1f)
    val maxChannel = maxOf(normalized.red, normalized.green, normalized.blue)
    val minChannel = minOf(normalized.red, normalized.green, normalized.blue)
    val saturation = if (maxChannel <= 0.0001f) 0f else (maxChannel - minChannel) / maxChannel
    return normalized.takeIf {
        maxChannel >= 0.10f && saturation >= 0.08f
    }
}

private fun buildDarkPlayerColors(tone: Color?): PlayerColors {
    val background = darkBackdropColor(tone)
    val textPrimary = darkThemeInk(tone)
    val textSecondary = if (tone == null) Color.White.copy(alpha = 0.80f) else textPrimary.copy(alpha = 0.80f)
    val cardTint = darkCardTint(tone)

    return PlayerColors(
        isDark = true,
        background = background,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        progressActive = textPrimary,
        progressInactive = if (tone == null) {
            Color.White.copy(alpha = 0.24f)
        } else {
            textPrimary.copy(alpha = 0.24f)
        },
        cardSurface = if (tone == null) Color.Black.copy(alpha = 0.12f) else cardTint.copy(alpha = 0.13f),
        cardSelected = if (tone == null) Color.Black.copy(alpha = 0.24f) else cardTint.copy(alpha = 0.22f),
        queueSelected = if (tone == null) {
            Color.White.copy(alpha = 0.12f)
        } else {
            cardTint.copy(alpha = 0.16f)
        },
        divider = Color.White.copy(alpha = 0.18f),
        outline = Color.White.copy(alpha = 0.26f),
        // 弹层/设置选择器继续使用原播放器蓝色，不随封面变色。
        accent = Color(0xFF1E88E5),
        accentSoft = Color(0xFF42A5F5),
        sheetBackground = Color(0xFF1B1E24),
        sheetText = Color(0xFFF2F4F7),
        sheetTextMuted = Color(0xFF9BA4B4),
        sheetTextFaint = Color(0xFF6C7482),
        segmentTrack = Color(0xFF22262E),
        softFill = Color(0xFF232830),
        tintBlue = Color(0xFF1C2739),
        cardBorder = BorderStroke(0.6.dp, Color(0xFF282F3B)),
        chipBorder = BorderStroke(0.5.dp, Color(0xFF2A313E)),
        backdrop = PlayerBackdropOverlay(
            baseColor = background,
            artworkAlpha = if (tone == null) 0.82f else 0.78f,
            scrimStops = listOf(
                0f to Color.Black.copy(alpha = 0.55f),
                0.42f to Color.Black.copy(alpha = 0.60f),
                0.78f to background.copy(alpha = 0.82f),
                1f to background.copy(alpha = 0.96f),
            ),
        ),
    )
}

private fun buildLightPlayerColors(tone: Color?): PlayerColors {
    val background = lightBackdropColor(tone)
    val textPrimary = lightThemeInk(tone)
    val textSecondary = if (tone == null) Color(0xFF515862) else textPrimary.copy(alpha = 0.72f)
    val cardTint = lightCardTint(tone)

    return PlayerColors(
        isDark = false,
        background = background,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        progressActive = textPrimary,
        progressInactive = textPrimary.copy(alpha = 0.18f),
        cardSurface = cardTint.copy(alpha = 0.105f),
        cardSelected = cardTint.copy(alpha = 0.18f),
        queueSelected = cardTint.copy(alpha = 0.14f),
        divider = Color.Black.copy(alpha = 0.14f),
        outline = Color.Black.copy(alpha = 0.22f),
        // 弹层/设置选择器继续使用原播放器蓝色，不随封面变色。
        accent = Color(0xFF1E88E5),
        accentSoft = Color(0xFF1976D2),
        sheetBackground = Color(0xFFF4F5F7),
        sheetText = Color(0xFF1E2024),
        sheetTextMuted = Color(0xFF6B7280),
        sheetTextFaint = Color(0xFF9CA3AF),
        segmentTrack = Color(0xFFE5E7EB),
        softFill = Color(0xFFF1F3F6),
        tintBlue = Color(0xFFEFF4FE),
        cardBorder = BorderStroke(0.6.dp, Color.Black.copy(alpha = 0.08f)),
        chipBorder = BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.10f)),
        backdrop = PlayerBackdropOverlay(
            baseColor = background,
            artworkAlpha = if (tone == null) 0.46f else 0.42f,
            scrimStops = listOf(
                0f to Color.White.copy(alpha = 0.34f),
                0.42f to Color.White.copy(alpha = 0.40f),
                0.78f to background.copy(alpha = 0.70f),
                1f to background.copy(alpha = 0.84f),
            ),
        ),
    )
}

private fun darkBackdropColor(tone: Color?): Color = tone?.let {
    // 背景只带一小段封面色相，保持深色页沉稳而不变成大块纯色。
    mixColor(DarkBackdropFallback, adjustSaturation(it, 0.78f), 0.24f)
} ?: DarkBackdropFallback

private fun lightBackdropColor(tone: Color?): Color = tone?.let {
    // 浅色背景始终靠近白色；色相只作为柔和环境色，不把整页压成灰。
    var result = mixColor(Color.White, adjustSaturation(it, 0.34f), 0.16f)
    if (result.luminance() < 0.78f) result = mixColor(result, Color.White, 0.28f)
    result
} ?: LightBackdropFallback

private fun darkCardTint(tone: Color?): Color = tone?.let {
    // 深色卡片需要比深背景稍亮，否则低 alpha 会完全消失。
    adjustSaturation(mixColor(Color.White, it, 0.66f), 0.82f)
} ?: Color.White

private fun lightCardTint(tone: Color?): Color = tone?.let {
    // 浅色卡片使用同一代表色的浅阶，而不是历史上的白色 .56/.78 实心层。
    adjustSaturation(mixColor(Color.White, it, 0.54f), 0.74f)
} ?: Color.White

private fun darkThemeInk(tone: Color?): Color {
    if (tone == null) return DarkInkFallback
    var result = adjustSaturation(mixColor(tone, Color.White, 0.56f), 1.04f)
    if (result.luminance() < 0.68f) result = mixColor(result, Color.White, 0.20f)
    return result.copy(alpha = 1f)
}

private fun lightThemeInk(tone: Color?): Color {
    if (tone == null) return LightInkFallback
    var result = adjustSaturation(mixColor(tone, Color.Black, 0.60f), 1.08f)
    if (result.luminance() > 0.30f) result = mixColor(result, Color.Black, 0.28f)
    return result.copy(alpha = 1f)
}

private fun mixColor(first: Color, second: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = first.red + (second.red - first.red) * t,
        green = first.green + (second.green - first.green) * t,
        blue = first.blue + (second.blue - first.blue) * t,
        alpha = first.alpha + (second.alpha - first.alpha) * t,
    )
}

private fun adjustSaturation(color: Color, factor: Float): Color {
    val gray = color.red * 0.299f + color.green * 0.587f + color.blue * 0.114f
    return Color(
        red = (gray + (color.red - gray) * factor).coerceIn(0f, 1f),
        green = (gray + (color.green - gray) * factor).coerceIn(0f, 1f),
        blue = (gray + (color.blue - gray) * factor).coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}

// 环境图与前景色同时接力；仅改变交接时间，不改变最终调色结果。
internal const val PlayerBackdropTransitionMillis = 240

/** 专辑/歌手聚合页属于App详情；系统栏与其真实画布取同一个主题。 */
internal fun playerPageIsLight(showingCollection: Boolean, playerIsDark: Boolean, appIsDark: Boolean): Boolean =
    !(if (showingCollection) appIsDark else playerIsDark)

internal val LocalPlayerColors = staticCompositionLocalOf { DarkPlayerColors }

@Composable
internal fun PlayerAppearanceProvider(
    dark: Boolean,
    artworkColor: Color? = null,
    content: @Composable () -> Unit,
) {
    // 不把播放进度、手势或模式放进派生 key；代表色变化只发生在封面加载/换歌时。
    val target = remember(dark, artworkColor) { playerColorsFor(dark, artworkColor) }
    val animationSpec = remember { tween<Color>(durationMillis = PlayerBackdropTransitionMillis) }
    val background by animateColorAsState(target.background, animationSpec, label = "playerBackground")
    val textPrimary by animateColorAsState(target.textPrimary, animationSpec, label = "playerTextPrimary")
    val textSecondary by animateColorAsState(target.textSecondary, animationSpec, label = "playerTextSecondary")
    val cardSurface by animateColorAsState(target.cardSurface, animationSpec, label = "playerCardSurface")
    val cardSelected by animateColorAsState(target.cardSelected, animationSpec, label = "playerCardSelected")
    val queueSelected by animateColorAsState(target.queueSelected, animationSpec, label = "playerQueueSelected")
    val artworkAlpha by animateFloatAsState(
        target.backdrop.artworkAlpha,
        tween(PlayerBackdropTransitionMillis),
        label = "playerBackdropArtworkAlpha",
    )
    val backdrop = target.backdrop.copy(
        baseColor = background,
        artworkAlpha = artworkAlpha,
        scrimStops = target.backdrop.scrimStops.mapIndexed { index, stop ->
            if (index >= 2) stop.first to background.copy(alpha = stop.second.alpha) else stop
        },
    )
    val colors = target.copy(
        background = background,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        progressActive = textPrimary,
        progressInactive = textPrimary.copy(alpha = target.progressInactive.alpha),
        cardSurface = cardSurface,
        cardSelected = cardSelected,
        queueSelected = queueSelected,
        backdrop = backdrop,
    )
    CompositionLocalProvider(
        LocalPlayerColors provides colors,
        LocalBadgeThemeDark provides colors.isDark,
        content = content,
    )
}

// 播放器组件统一从局部令牌读取颜色，避免把全局界面主题混入播放器。
@get:Composable
internal val FullPlayerTextPrimary: Color
    get() = LocalPlayerColors.current.textPrimary

@get:Composable
internal val FullPlayerTextMuted: Color
    get() = LocalPlayerColors.current.textSecondary

@get:Composable
internal val FullPlayerProgressActive: Color
    get() = LocalPlayerColors.current.progressActive

@get:Composable
internal val FullPlayerProgressInactive: Color
    get() = LocalPlayerColors.current.progressInactive

@get:Composable
internal val FullPlayerCardSurface: Color
    get() = LocalPlayerColors.current.cardSurface

@get:Composable
internal val FullPlayerCardSelected: Color
    get() = LocalPlayerColors.current.cardSelected

@get:Composable
internal val FullPlayerSheetBackground: Color
    get() = LocalPlayerColors.current.sheetBackground

@get:Composable
internal val FullPlayerSheetTextPrimary: Color
    get() = LocalPlayerColors.current.sheetText

@get:Composable
internal val FullPlayerSheetTextMuted: Color
    get() = LocalPlayerColors.current.sheetTextMuted

@get:Composable
internal val FullPlayerSheetPrimaryBlue: Color
    get() = LocalPlayerColors.current.accent

@get:Composable
internal val FullPlayerSheetDivider: Color
    get() = LocalPlayerColors.current.divider

@get:Composable
internal val FullPlayerSheetTextFaint: Color
    get() = LocalPlayerColors.current.sheetTextFaint

@get:Composable
internal val FullPlayerSheetSegmentTrack: Color
    get() = LocalPlayerColors.current.segmentTrack

@get:Composable
internal val FullPlayerSheetSoftFill: Color
    get() = LocalPlayerColors.current.softFill

@get:Composable
internal val FullPlayerSheetTintBlue: Color
    get() = LocalPlayerColors.current.tintBlue

@get:Composable
internal val FullPlayerSheetCard: Color
    get() = if (LocalPlayerColors.current.isDark) {
        Color.Black.copy(alpha = 0.24f)
    } else {
        Color.White
    }

@get:Composable
internal val FullPlayerSheetAccentRed: Color
    get() = Color(0xFFE53935)

@get:Composable
internal val FullPlayerSheetCardBorder: BorderStroke
    get() = LocalPlayerColors.current.cardBorder

@get:Composable
internal val FullPlayerSheetChipBorder: BorderStroke
    get() = LocalPlayerColors.current.chipBorder
