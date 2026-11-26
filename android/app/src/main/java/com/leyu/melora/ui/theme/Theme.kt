package com.leyu.melora.ui.theme

import android.app.Activity
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.leyu.melora.playback.ComponentBlurMode
import com.leyu.melora.playback.MeloraSettings
import kotlinx.coroutines.launch

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

private val EInkColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color.Black,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color.Black,
    tertiary = Color.Black,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E8E8),
    onTertiaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceTint = Color.Black,
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF222222),
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    inversePrimary = Color.White,
    surfaceDim = Color(0xFFF2F2F2),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF2F2F2),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    outline = Color.Black,
    outlineVariant = Color(0xFF4A4A4A),
    error = Color.Black,
    onError = Color.White,
    errorContainer = Color(0xFFE8E8E8),
    onErrorContainer = Color.Black,
    scrim = Color.Black,
)

/** 墨水屏按压与键盘焦点都以静态状态显示。 */
private object EInkIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other === this

    private class Node(private val interactionSource: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var press: PressInteraction.Press? = null
        private var focus: FocusInteraction.Focus? = null

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    val wasPressed = press != null
                    val wasFocused = focus != null
                    when (interaction) {
                        is PressInteraction.Press -> press = interaction
                        is PressInteraction.Release -> if (press == interaction.press) press = null
                        is PressInteraction.Cancel -> if (press == interaction.press) press = null
                        is FocusInteraction.Focus -> focus = interaction
                        is FocusInteraction.Unfocus -> if (focus == interaction.focus) focus = null
                    }
                    if (wasPressed != (press != null) || wasFocused != (focus != null)) {
                        invalidateDraw()
                    }
                }
            }
        }

        override fun onDetach() {
            press = null
            focus = null
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (press != null) drawRect(Color.Gray.copy(alpha = 0.20f))
            if (focus != null) {
                val stroke = minOf(2.dp.toPx(), size.minDimension / 4)
                inset(stroke) {
                    drawRect(Color.White, style = Stroke(stroke * 2))
                    drawRect(Color.Black, style = Stroke(stroke))
                }
            }
        }
    }
}

private object EInkMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

// Material3 控件内部自带波纹。将按压和拖动的不透明度设为零，同时保留独立的焦点描边。
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
private val EInkRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(draggedAlpha = 0f, focusedAlpha = 0f, hoveredAlpha = 0f, pressedAlpha = 0f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeloraMaterialTheme(
    colorScheme: ColorScheme,
    eInk: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorScheme,
        motionScheme = if (eInk) EInkMotionScheme else MaterialTheme.motionScheme,
    ) {
        CompositionLocalProvider(
            LocalIndication provides if (eInk) EInkIndication else LocalIndication.current,
            LocalRippleConfiguration provides if (eInk) EInkRipple else LocalRippleConfiguration.current,
            LocalRippleThemeConfiguration provides
                if (eInk) RippleDefaults.InsetFocusRingThemeConfiguration else LocalRippleThemeConfiguration.current,
            content = content,
        )
    }
}

internal val componentBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
internal val LocalComponentBlurMode = compositionLocalOf { ComponentBlurMode.Off }

@Composable
fun MeloraTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val themeMode by MeloraSettings.themeMode.collectAsStateWithLifecycle()
    val eInkMode by MeloraSettings.eInkMode.collectAsStateWithLifecycle()
    val darkTheme = !eInkMode && themeMode.isDark(isSystemInDarkTheme())

    val colorScheme = when {
        eInkMode -> EInkColors
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // 同步全局外观调色板，使硬编码设计令牌随主题切换。
    SideEffect {
        MeloraAppearance.isDark = darkTheme
        MeloraAppearance.isEInk = eInkMode
    }

    val blurMode by MeloraSettings.componentBlurMode.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalComponentBlurMode provides if (!eInkMode && componentBlurSupported) blurMode else ComponentBlurMode.Off,
    ) {
        MeloraMaterialTheme(colorScheme, eInkMode, content)
    }
}

/** 局部抽屉主题覆盖（例如全屏播放页独立深/浅色）；未提供时沿用全局 MeloraAppearance.isDark。 */
internal val LocalSheetDarkOverride = staticCompositionLocalOf<Boolean?> { null }

@Composable
internal fun currentMeloraSheetPalette(): MeloraThemePalette =
    MeloraAppearance.palette(LocalSheetDarkOverride.current ?: MeloraAppearance.isDark)

@Composable
internal fun WithMeloraSheetTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val eInk = MeloraAppearance.isEInk
    val resolvedDark = !eInk && darkTheme
    CompositionLocalProvider(LocalSheetDarkOverride provides resolvedDark) {
        MeloraMaterialTheme(
            colorScheme = when {
                eInk -> EInkColors
                resolvedDark -> DarkColors
                else -> LightColors
            },
            eInk = eInk,
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
            // 隐藏导航栏时用默认行为，让系统仍能把侧边滑动交给返回手势。
            controller.systemBarsBehavior = if (hideStatusBar && !hideNavBar) {
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
