package com.leyu.melora.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.leyu.melora.playback.ComponentBlurMode
import com.leyu.melora.ui.theme.LocalComponentBlurMode
import com.leyu.melora.ui.theme.DarkGlass
import com.leyu.melora.ui.theme.LightSheetFrost
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

internal val LocalGlassBackdrop = compositionLocalOf<HazeState?> { null }

private val darkPanelRim = BorderStroke(0.6.dp, Brush.verticalGradient(
    0f to DarkGlass.topRim,
    0.18f to Color.White.copy(alpha = 0.04f),
    0.42f to Color.White.copy(alpha = 0f),
))
private val lightPanelRim = BorderStroke(0.8.dp, Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.90f),
    0.4f to Color.White.copy(alpha = 0.48f),
    1f to Color.White.copy(alpha = 0.24f),
))

@Composable
internal fun componentHeadersBlurred(): Boolean = LocalComponentBlurMode.current != ComponentBlurMode.Off

@Composable
internal fun componentSurfacesBlurred(): Boolean = LocalComponentBlurMode.current == ComponentBlurMode.All

@Composable
internal fun glassBackdropSource(baseColor: Color): HazeState? = LocalGlassBackdrop.current?.takeIf {
    componentSurfacesBlurred() && baseColor.isSpecified && baseColor.alpha == 1f
}

internal fun glassScrimAlpha(baseColor: Color): Float = if (baseColor.luminance() > 0.5f) 0.16f else DarkGlass.scrimAlpha

/** 按屏幕位置采样页面，跨窗口或画面未就绪时保留面板底色。 */
@Composable
internal fun glassPanelModifier(
    baseColor: Color,
    shape: Shape,
    backdropScrim: Color = Color.Transparent,
    frostTint: Color = Color.Unspecified,
): Modifier {
    val clipped = Modifier.clip(shape)
    val source = glassBackdropSource(baseColor) ?: return clipped.background(baseColor)
    val dark = baseColor.luminance() <= 0.5f
    val lightFrost = frostTint.isSpecified
    val surface = when {
        dark -> DarkGlass.base
        lightFrost -> LightSheetFrost.base
        else -> baseColor
    }
    val overlay = when {
        dark -> DarkGlass.tint
        lightFrost -> frostTint
        else -> lerp(surface, Color.White, 0.72f).copy(alpha = 0.68f)
    }
    // 跨窗口采样的是遮罩下方的页面，将遮罩与材质色按显示顺序合成。
    val tintedGlass = dark || lightFrost
    val tint = if (tintedGlass && backdropScrim.isSpecified) overlay.compositeOver(backdropScrim) else overlay
    val rim = when {
        lightFrost -> {
            val capHeight = with(LocalDensity.current) { 26.dp.toPx() }
            remember(capHeight) {
                BorderStroke(1.dp, Brush.verticalGradient(
                    listOf(LightSheetFrost.topRim, Color.White.copy(alpha = 0f)), endY = capHeight,
                ))
            }
        }
        dark -> darkPanelRim
        else -> lightPanelRim
    }
    val blur = remember(surface, tint, lightFrost, tintedGlass) {
        val matrix = ColorMatrix().apply { setToSaturation(if (lightFrost) 1.25f else 1.15f) }
        HazeBlurStyle {
            backgroundColor(surface)
            colorEffects(listOf(
                HazeColorEffect.colorFilter(ColorFilter.colorMatrix(matrix)),
                HazeColorEffect.tint(tint),
            ))
            blurRadius(if (tintedGlass) 30.dp else 22.dp)
            noiseFactor(if (tintedGlass) 0.01f else 0f)
            fallbackColorEffect(HazeColorEffect.tint(surface))
        }
    }
    return clipped.background(surface)
        .hazeBlur(
            input = HazeInput.Sources(source, retention = HazeSourceRetention.ClearWhenUnavailable),
            style = blur,
            performanceMode = HazePerformanceMode.Quality,
        )
        .border(rim, shape)
}
