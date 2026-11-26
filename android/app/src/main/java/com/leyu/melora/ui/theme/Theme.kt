package com.leyu.melora.ui.theme

import android.app.Activity
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.leyu.melora.playback.ComponentBlurMode
import com.leyu.melora.playback.MeloraSettings

private val LightColors = lightColorScheme(
    primary = MeloraBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEFF4FE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = MeloraIndigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF0FF),
    onSecondaryContainer = Color(0xFF312E81),
    background = MeloraSurfaceLight,
    onBackground = Color(0xFF111827),
    surface = MeloraSurfaceLight,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F3F6),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFF4F5F7),
    surfaceContainerHigh = Color(0xFFEEF0F4),
    surfaceContainerHighest = Color(0xFFE5E7EB),
    outline = Color(0xFFC9CFD9),
    outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = MeloraBlueDark,
    onPrimary = Color(0xFF081736),
    primaryContainer = Color(0xFF1C2536),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = MeloraIndigoDark,
    onSecondary = Color(0xFF101342),
    secondaryContainer = Color(0xFF1E1E21),
    onSecondaryContainer = Color(0xFFE2E6FF),
    background = MeloraSurfaceDark,
    onBackground = Color(0xFFEBEBEF),
    surface = MeloraSurfaceDark,
    onSurface = Color(0xFFEBEBEF),
    surfaceVariant = Color(0xFF1E1E22),
    onSurfaceVariant = Color(0xFF9898A0),
    surfaceContainerLowest = Color(0xFF070709),
    surfaceContainerLow = MeloraCardDark,
    surfaceContainer = Color(0xFF141416),
    surfaceContainerHigh = Color(0xFF1E1E22),
    surfaceContainerHighest = Color(0xFF26262A),
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF27272B),
    error = Color(0xFFF2635C),
    onError = Color(0xFF410E0B),
    errorContainer = Color(0xFF2D191D),
    scrim = Color.Black,
)

internal val componentBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
internal val LocalComponentBlurMode = compositionLocalOf { ComponentBlurMode.Off }

@Composable
fun MeloraTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val themeMode by MeloraSettings.themeMode.collectAsStateWithLifecycle()
    val darkTheme = themeMode.isDark(isSystemInDarkTheme())

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // 同步全局外观调色板，使硬编码设计令牌随主题切换。
    SideEffect { MeloraAppearance.isDark = darkTheme }

    val blurMode by MeloraSettings.componentBlurMode.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalComponentBlurMode provides if (componentBlurSupported) blurMode else ComponentBlurMode.Off) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/** 局部抽屉主题覆盖（例如全屏播放页独立深/浅色）；未提供时沿用全局 MeloraAppearance.isDark。 */
internal val LocalSheetDarkOverride = staticCompositionLocalOf<Boolean?> { null }

@Composable
internal fun currentMeloraSheetPalette(): MeloraThemePalette =
    MeloraAppearance.palette(LocalSheetDarkOverride.current ?: MeloraAppearance.isDark)

@Composable
internal fun WithMeloraSheetTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSheetDarkOverride provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}

@Composable
internal fun meloraSwitchColors(): SwitchColors {
    val colors = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = colors.onPrimary,
        checkedTrackColor = colors.primary,
        uncheckedThumbColor = colors.onSurfaceVariant,
        uncheckedTrackColor = colors.surfaceVariant,
        uncheckedBorderColor = colors.onSurfaceVariant,
    )
}

/** 临时窗口策略随播放子树传入 Dialog，不写入用户的全局设置。 */
internal val LocalForceHideStatusBar = staticCompositionLocalOf { false }

/** 普通页面与播放层互斥调用，系统栏明暗只保留这一处写入实现。 */
@Composable
internal fun SystemBarsAppearance(
    darkStatusIcons: Boolean,
    darkNavigationIcons: Boolean = darkStatusIcons,
    keepScreenAwake: Boolean = false,
    forceHideStatusBar: Boolean = LocalForceHideStatusBar.current,
) {
    val context = LocalContext.current
    val view = LocalView.current
    SystemBarsVisibility(forceHideStatusBar)
    val controller = remember(context) {
        (context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
    }
    DisposableEffect(controller) {
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        onDispose {
            if (previousStatus != null) controller.isAppearanceLightStatusBars = previousStatus
            if (previousNav != null) controller.isAppearanceLightNavigationBars = previousNav
        }
    }
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }
    SideEffect {
        controller?.isAppearanceLightStatusBars = darkStatusIcons
        controller?.isAppearanceLightNavigationBars = darkNavigationIcons
        // Window 的 keepScreenOn 只作用于可见窗口，不持有后台 WakeLock。
        view.keepScreenOn = keepScreenAwake
    }
}

/** 每个窗口自行应用可见性：底部抽屉/Dialog不继承Activity的InsetsController状态。 */
@Composable
internal fun SystemBarsVisibility(forceHideStatusBar: Boolean = LocalForceHideStatusBar.current) {
    val view = LocalView.current
    val context = LocalContext.current
    val configuredHideStatusBar by MeloraSettings.hideStatusBar.collectAsStateWithLifecycle()
    val hideStatusBar = configuredHideStatusBar || forceHideStatusBar
    val hideNavBar = forceHideStatusBar
    val window = remember(view, context) {
        generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()?.window ?: (context as? Activity)?.window
    } ?: return
    DisposableEffect(window, view, hideStatusBar, hideNavBar) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        fun applyVisibility() {
            controller.systemBarsBehavior = if (hideStatusBar || hideNavBar) {
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (hideStatusBar) controller.hide(WindowInsetsCompat.Type.statusBars())
            else controller.show(WindowInsetsCompat.Type.statusBars())
            if (hideNavBar) controller.hide(WindowInsetsCompat.Type.navigationBars())
            else controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        applyVisibility()
        // Dialog首次获焦、返回主窗口时再次同步；不替换Compose自己的Insets监听器。
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) applyVisibility()
        }
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }
    }
}
