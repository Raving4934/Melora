package com.leyu.melora.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.ui.theme.MeloraAppearance
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** 当前页面固定栏累计占用高度。仅加入全页列表 contentPadding，不裁切滚动视口。 */
internal val LocalChromeTopInset = compositionLocalOf { 0.dp }
// 系统安全区与导航栏高度分离：替换导航的详情页不能继承上一帧仍可见的主栏高度。
private val LocalChromeSystemTopInset = compositionLocalOf { 0.dp }

internal fun chromeHeaderTopInset(parentHeader: Dp, systemTop: Dp, stackOnParent: Boolean): Dp =
    if (stackOnParent) parentHeader else systemTop
private val LocalChromeBlurEnabled = compositionLocalOf { false }
private val LocalChromeGeometry = compositionLocalOf<ChromeHeaderGeometry?> { null }
private val LocalChromeDepth = compositionLocalOf { 0 }

/** 所有固定栏共用正文源和渐变坐标，禁止把下层栏的阴影/模糊结果再采样。 */
internal class ChromeHeaderGeometry {
    private data class Region(val top: Dp, val bottom: Dp, val depth: Int, val source: HazeState?)
    private val regions = mutableStateMapOf<Any, Region>()
    val minimumTop: Dp get() = regions.values.minOfOrNull { it.top } ?: 0.dp
    val maximumBottom: Dp get() = regions.values.maxOfOrNull { it.bottom } ?: 0.dp

    /** 首帧即使用自身正文；只有更深层正文可接管，绝不采样包含当前顶栏的祖先区域。 */
    fun sourceFor(depth: Int, ownSource: HazeState): HazeState = regions.values
        .filter { it.depth > depth && it.source != null }
        .maxByOrNull { it.depth }?.source ?: ownSource

    fun update(owner: Any, top: Dp, bottom: Dp, depth: Int, source: HazeState?) {
        regions[owner] = Region(top, bottom, depth, source)
    }
    fun remove(owner: Any) { regions.remove(owner) }
}

/** 只对当前固定/吸顶栏生效，普通按钮、列表卡片和底部操作条不受影响。 */
@Composable
internal fun chromeHeaderBlurred(): Boolean = LocalChromeBlurEnabled.current

@Composable
internal fun chromeHeaderColor(): Color =
    if (chromeHeaderBlurred()) Color.Transparent else MeloraAppearance.canvas

/** 导航/操作栏统一使用透明动作面；样式不依赖模糊开关或吸顶状态。 */
@Composable
internal fun ChromeActionSurface(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = LocalContentColor.current,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        modifier = modifier,
        enabled = enabled,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
internal fun chromeContentPadding(base: PaddingValues = PaddingValues(0.dp)): PaddingValues =
    withChromeTopInset(base, LocalChromeTopInset.current, LocalLayoutDirection.current)

internal fun withChromeTopInset(base: PaddingValues, topInset: Dp, direction: LayoutDirection): PaddingValues =
    PaddingValues(
        start = base.calculateStartPadding(direction),
        top = base.calculateTopPadding() + topInset,
        end = base.calculateEndPadding(direction),
        bottom = base.calculateBottomPadding(),
    )

/**
 * 系统区由根层占一次；详情替换主栏，只有常驻搜索条显式叠在父导航下。
 * Material Scaffold 同帧测量并保留原 contentPadding；切换开关不改变布局。
 * 状态栏与标题/操作栏共用原生模糊和同一渐变；嵌套栏只采样最内层正文。
 * contentSource 用于正文内有吸顶栏的页面：页面只记录正文项，绝不记录栏本身。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun ChromeScaffold(
    modifier: Modifier = Modifier,
    containerColor: Color = MeloraAppearance.canvas,
    headerColor: Color = MeloraAppearance.canvas,
    contentSource: HazeState? = null,
    stackOnParent: Boolean = false,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val enabled by MeloraSettings.blurTopBar.collectAsStateWithLifecycle()
    val inheritedTop = LocalChromeTopInset.current
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val inheritedGeometry = LocalChromeGeometry.current
    val geometry = remember(inheritedGeometry) { inheritedGeometry ?: ChromeHeaderGeometry() }
    val depth = LocalChromeDepth.current + 1
    val owner = remember { Any() }
    DisposableEffect(geometry, owner) { onDispose { geometry.remove(owner) } }
    val ownSource = rememberHazeState()
    val bodySource = contentSource ?: ownSource
    val activeSource = geometry.sourceFor(depth, bodySource)
    val root = inheritedGeometry == null
    val systemTop = if (root) with(density) {
        WindowInsets.safeDrawing.getTop(this).toDp()
    } else LocalChromeSystemTopInset.current
    val headerTop = if (root) 0.dp else chromeHeaderTopInset(inheritedTop, systemTop, stackOnParent)
    val material = Modifier.chromeMaterial(activeSource, enabled, headerTop, geometry, headerColor)

    CompositionLocalProvider(
        LocalChromeGeometry provides geometry,
        LocalChromeDepth provides depth,
        LocalChromeSystemTopInset provides systemTop,
    ) {
        Scaffold(
            modifier = modifier,
            containerColor = containerColor,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Column {
                    Spacer(Modifier.height(headerTop))
                    Box(
                        Modifier.fillMaxWidth()
                            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                            .clipToBounds().then(material),
                    ) {
                        // 材质覆盖整个窗口宽度；切口/导航栏的横向避让只限制文字与按钮。
                        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                            // 只占一次系统 inset，底色/模糊由外层统一绘制，系统图标不属于采样内容。
                            if (root) Spacer(Modifier.fillMaxWidth().height(systemTop))
                            CompositionLocalProvider(LocalChromeBlurEnabled provides enabled) { topBar() }
                        }
                    }
                }
            },
            bottomBar = bottomBar,
        ) { padding ->
            val headerBottom = padding.calculateTopPadding()
            SideEffect { geometry.update(owner, headerTop, headerBottom, depth, bodySource) }
            val sidesAndBottom = PaddingValues(
                start = padding.calculateStartPadding(direction),
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding(),
            )
            Box(
                modifier = Modifier.fillMaxSize()
                    .padding(sidesAndBottom)
                    .consumeWindowInsets(padding)
                    .then(if (enabled && contentSource == null && activeSource === bodySource && headerBottom > 0.dp) {
                        Modifier.hazeSource(bodySource)
                    } else Modifier),
            ) {
                CompositionLocalProvider(LocalChromeTopInset provides headerBottom) { content() }
            }
        }
    }
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun Modifier.chromeMaterial(
    state: HazeState,
    enabled: Boolean,
    topOffset: Dp,
    geometry: ChromeHeaderGeometry,
    canvas: Color = MeloraAppearance.canvas,
): Modifier {
    val density = LocalDensity.current
    if (!enabled) return background(canvas)
    val start = with(density) { (geometry.minimumTop - topOffset).toPx() }
    val end = with(density) { (geometry.maximumBottom - topOffset).toPx() }.coerceAtLeast(start + 1f)
    // 遮色直接在画布混合，不在 RenderEffect 内生成半透明中间纹理。
    val veil = remember(canvas, start, end) {
        Brush.verticalGradient(listOf(canvas, canvas.copy(alpha = 0f)), startY = start, endY = end)
    }
    val fade = remember(start, end) {
        Brush.verticalGradient(
            0f to Color.Black,
            0.60f to Color.Black,
            1f to Color.Transparent,
            startY = start,
            endY = end,
        )
    }
    return hazeEffect(
        state = state,
        style = HazeStyle(
            backgroundColor = canvas,
            tint = HazeTint(Color.Transparent),
            blurRadius = 20.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(canvas),
        ),
    ) {
        // 所有栏都使用同一份正文、同一物理坐标范围，不能单独缩放或重启渐变。
        inputScale = HazeInputScale.None
        // 使用系统高斯与渐隐遮罩，避开自定义可变半径内核的裁切暗边。
        progressive = null
        mask = fade
    }.drawBehind { drawRect(veil) }

}

/** 保留 Hero 下方原控制条的位置，吸顶后才加入同一渐变材质范围。 */
@Composable
internal fun ChromeFloatingBar(
    state: HazeState,
    topOffset: Dp,
    height: Dp,
    pinned: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val enabled by MeloraSettings.blurTopBar.collectAsStateWithLifecycle()
    val geometry = checkNotNull(LocalChromeGeometry.current)
    val depth = LocalChromeDepth.current
    val owner = remember { Any() }
    val density = LocalDensity.current
    // 该栏布局高度已知，同帧按物理像素对齐登记，不再等待onSizeChanged回写下一帧。
    val layoutHeight = with(density) { height.roundToPx().toDp() }
    DisposableEffect(geometry, owner) { onDispose { geometry.remove(owner) } }
    SideEffect {
        if (pinned) geometry.update(owner, topOffset, topOffset + layoutHeight, depth, source = null) else geometry.remove(owner)
    }
    Box(
        modifier = modifier
            .height(height)
            .clipToBounds()
            .then(Modifier.chromeMaterial(geometry.sourceFor(depth, state), enabled && pinned, topOffset, geometry)),
    ) {
        CompositionLocalProvider(LocalChromeBlurEnabled provides (enabled && pinned)) { content() }
    }
}
