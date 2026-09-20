package com.leyu.melora.ui.theme

import android.app.Activity
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.leyu.melora.playback.MeloraSettings

private val LightColors = lightColorScheme(
    primary = MeloraBlue,
    secondary = MeloraIndigo,
    background = MeloraSurfaceLight,
    surface = MeloraSurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = MeloraBlueDark,
    secondary = MeloraIndigo,
    background = MeloraSurfaceDark,
    surface = MeloraSurfaceDark,
)

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

    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** 普通页面与播放层互斥调用，系统栏明暗只保留这一处写入实现。 */
@Composable
internal fun SystemBarsAppearance(
    darkStatusIcons: Boolean,
    darkNavigationIcons: Boolean = darkStatusIcons,
    keepScreenAwake: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    SystemBarsVisibility()
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
internal fun SystemBarsVisibility() {
    val view = LocalView.current
    val context = LocalContext.current
    val hideStatusBar by MeloraSettings.hideStatusBar.collectAsStateWithLifecycle()
    val window = remember(view, context) {
        generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()?.window ?: (context as? Activity)?.window
    } ?: return
    DisposableEffect(window, view, hideStatusBar) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        fun applyVisibility() {
            controller.systemBarsBehavior = if (hideStatusBar) {
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (hideStatusBar) controller.hide(WindowInsetsCompat.Type.statusBars())
            else controller.show(WindowInsetsCompat.Type.statusBars())
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
