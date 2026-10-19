package com.leyu.melora.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

/**
 * 只在可用窗口的短边超过常规平板的 800dp 时提升 UI 尺寸。
 * 窄窗口原样；旋转不改变比例；分屏按自己的窗口重新计算，不依赖品牌或物理 PPI。
 */
internal fun appDisplayScale(windowSize: IntSize, density: Float): Float =
    (minOf(windowSize.width, windowSize.height) / density / 800f).coerceIn(1f, 2f)

/**
 * 仅调整应用的 Compose 尺寸单位，让文字、触控区、侧栏和播放条一起变大，
 * 布局仍按实际可用 dp 重排。保留系统 fontScale，不修改系统配置或屏幕密度。
 * 放在 Activity 内容根部，避免键盘、页面内容高度和弹窗尺寸反过来影响比例。
 */
@Composable
internal fun AdaptiveDisplay(content: @Composable () -> Unit) {
    val systemDensity = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val scale = appDisplayScale(windowSize, systemDensity.density)
    val density = remember(systemDensity, scale) {
        if (scale == 1f) systemDensity
        else Density(systemDensity.density * scale, systemDensity.fontScale)
    }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}
