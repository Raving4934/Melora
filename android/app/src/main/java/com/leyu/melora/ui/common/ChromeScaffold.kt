package com.leyu.melora.ui.common

import android.content.Context
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.ui.theme.MeloraAppearance
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt

/** 当前页面固定栏累计占用高度。仅加入全页列表 contentPadding，不裁切滚动视口。 */
internal val LocalChromeTopInset = compositionLocalOf { 0.dp }
/** 主导航材质向侧栏留白外绘的宽度；手机关闭侧栏时，外绘部分自然落在屏幕外。 */
internal val LocalChromeStartBleed = compositionLocalOf { 0.dp }
// 系统安全区与导航栏高度分离：替换导航的详情页不能继承上一帧仍可见的主栏高度。
private val LocalChromeSystemTopInset = compositionLocalOf { 0.dp }

internal fun chromeHeaderTopInset(parentHeader: Dp, systemTop: Dp, stackOnParent: Boolean): Dp =
    if (stackOnParent) parentHeader else systemTop
private val LocalChromeBlurEnabled = compositionLocalOf { false }
private val LocalChromeGeometry = compositionLocalOf<ChromeHeaderGeometry?> { null }
private val LocalChromeDepth = compositionLocalOf { 0 }

/** 所有固定栏共用正文源和渐变坐标，禁止把下层栏的阴影/模糊结果再采样。 */
internal class ChromeHeaderGeometry {
    private data class Region(val top: Dp, val bottom: Dp, val depth: Int, val source: HazeState?, val hasSecondaryRow: Boolean)
    private val regions = mutableStateMapOf<Any, Region>()
    val hasSecondaryRow: Boolean get() = regions.values.any { it.hasSecondaryRow }
    val minimumTop: Dp get() = regions.values.minOfOrNull { it.top } ?: 0.dp
    val maximumBottom: Dp get() = regions.values.maxOfOrNull { it.bottom } ?: 0.dp
    // 已知栏高作为首帧范围；登记尚未完成时不能先创建1px遮罩，下一帧再重建模糊层。
    fun materialBottom(knownBottom: Dp): Dp = maxOf(maximumBottom, knownBottom)

    /** 首帧即使用自身正文；只有更深层正文可接管，绝不采样包含当前顶栏的祖先区域。 */
    fun sourceFor(depth: Int, ownSource: HazeState): HazeState = regions.values
        .filter { it.depth > depth && it.source != null }
        .maxByOrNull { it.depth }?.source ?: ownSource

    fun update(owner: Any, top: Dp, bottom: Dp, depth: Int, source: HazeState?, hasSecondaryRow: Boolean = false) {
        regions[owner] = Region(top, bottom, depth, source, hasSecondaryRow)
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

/** 委托标题时，外壳不裁掉横屏安全区；交给内容页在全宽材质内统一避让。 */
internal fun chromeBodyPadding(padding: PaddingValues, direction: LayoutDirection, delegatesHeader: Boolean): PaddingValues =
    PaddingValues(
        start = if (delegatesHeader) 0.dp else padding.calculateStartPadding(direction),
        end = if (delegatesHeader) 0.dp else padding.calculateEndPadding(direction),
        bottom = padding.calculateBottomPadding(),
    )

/** 已知栏高时首帧直接给出正文起点，避免等待 Scaffold 测量回写后整页下坠。 */
internal fun chromeContentTopInset(
    measured: Dp,
    headerTop: Dp,
    systemTop: Dp,
    root: Boolean,
    topBarHeight: Dp?,
): Dp = topBarHeight?.let { headerTop + (if (root) systemTop else 0.dp) + it } ?: measured

/**
 * Compose Insets 在冷启动的首次 composition 可能暂时为 0。根栏始终保留稳定状态栏占位，
 * 系统栏显隐只改变图标可见性，不能触发整个页面上下重排。
 */
internal fun resolveSystemTopInsetPx(
    composeInset: Int,
    platformInset: Int,
    resourceInset: Int,
): Int = maxOf(composeInset, platformInset, resourceInset)

/** 同一屏幕方向内只允许系统顶部占位增大，状态栏隐藏时不得因 OEM 返回 0 而缩小正文起点。 */
internal fun resolveStableSystemTopInsetPx(
    previousInset: Int,
    composeInset: Int,
    platformInset: Int,
    resourceInset: Int,
): Int = maxOf(previousInset, resolveSystemTopInsetPx(composeInset, platformInset, resourceInset))

// 首帧Insets尚未分发、或OEM隐藏状态栏返回0时的尺寸兜底；不是替代正常Insets的数据通道。
@android.annotation.SuppressLint("InternalInsetResource", "DiscouragedApi")
private fun Context.statusBarHeightPx(): Int {
    val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId != 0) resources.getDimensionPixelSize(resourceId) else 0
}

/**
 * 系统区只占一次；无标题外壳委托内容页完整绘制系统区与标题，叠加操作栏仍共享页内坐标。
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
    expectedTopBarHeight: Dp? = null,
    hasSecondaryRow: Boolean = false,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val enabled by MeloraSettings.blurTopBar.collectAsStateWithLifecycle()
    val inheritedTop = LocalChromeTopInset.current
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val startBleed = LocalChromeStartBleed.current
    val startBleedPx = with(density) { startBleed.roundToPx() }
    val view = LocalView.current
    val orientation = LocalConfiguration.current.orientation
    val inheritedGeometry = LocalChromeGeometry.current
    val geometry = remember(inheritedGeometry) { inheritedGeometry ?: ChromeHeaderGeometry() }
    val depth = LocalChromeDepth.current + 1
    val owner = remember { Any() }
    DisposableEffect(geometry, owner) { onDispose { geometry.remove(owner) } }
    val ownSource = rememberHazeState()
    val bodySource = contentSource ?: ownSource
    // 登记自身区域不等于采样源变化，不能因此重新执行整页Scaffold/列表测量。
    val activeSource by remember(geometry, depth, bodySource) {
        derivedStateOf { geometry.sourceFor(depth, bodySource) }
    }
    val root = inheritedGeometry == null
    // 无页面标题的外壳只提供安全区/底栏，整块状态栏+标题材质由内容页拥有。
    // 并行进出的两页各自保有一份连续渐变，不能在固定状态栏上抢用目标页的采样源。
    val delegatesHeader = root && expectedTopBarHeight == 0.dp
    val stableSystemTopPx = remember(view, orientation) { intArrayOf(view.context.statusBarHeightPx()) }
    val systemTop = if (root) with(density) {
        val stableTypes = WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
        val platformTop = ViewCompat.getRootWindowInsets(view)
            ?.getInsetsIgnoringVisibility(stableTypes)
            ?.top
            ?: 0
        val resolved = resolveStableSystemTopInsetPx(
            previousInset = stableSystemTopPx[0],
            composeInset = WindowInsets.safeDrawing.getTop(this),
            platformInset = platformTop,
            resourceInset = view.context.statusBarHeightPx(),
        )
        stableSystemTopPx[0] = resolved
        resolved.toDp()
    } else LocalChromeSystemTopInset.current
    val headerTop = if (root) 0.dp else chromeHeaderTopInset(inheritedTop, systemTop, stackOnParent)
    CompositionLocalProvider(
        LocalChromeGeometry provides geometry,
        LocalChromeDepth provides depth,
        LocalChromeSystemTopInset provides systemTop,
    ) {
        Scaffold(
            // Surface会裁切子材质；只扩大绘制视口，再对三个槽位作等量内缩。
            // 对外报告原尺寸，嵌套页也共享同一条外边界，不逐层累计留白。
            modifier = modifier.layout { measurable, constraints ->
                val layer = measurable.measure(constraints.copy(
                    minWidth = constraints.minWidth + startBleedPx,
                    maxWidth = constraints.maxWidth + startBleedPx,
                ))
                layout(layer.width - startBleedPx, layer.height) { layer.placeRelative(-startBleedPx, 0) }
            },
            containerColor = containerColor,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Column(Modifier.padding(start = startBleed)) {
                    Spacer(Modifier.height(headerTop))
                    Box(
                        Modifier.fillMaxWidth()
                            .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                    ) {
                        if (!delegatesHeader) ChromeMaterial(
                            activeSource, enabled, headerTop, geometry, headerColor, hasSecondaryRow,
                            knownBottom = headerTop + (if (root) systemTop else 0.dp) + (expectedTopBarHeight ?: 0.dp),
                        )
                        // 材质与交互内容分层：仅背景向下渐隐，文字/按钮仍按原区域裁切。
                        Column(Modifier.clipToBounds().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                            // 只占一次系统 inset，底色/模糊由外层统一绘制，系统图标不属于采样内容。
                            if (root) Spacer(Modifier.fillMaxWidth().height(systemTop))
                            CompositionLocalProvider(LocalChromeBlurEnabled provides enabled) { topBar() }
                        }
                    }
                }
            },
            bottomBar = {
                Box(Modifier.layout { measurable, constraints ->
                    val bar = measurable.measure(constraints.offset(horizontal = -startBleedPx))
                    // 空槽必须仍为0×0，否则Scaffold会误判存在底栏并撤销系统导航栏留白。
                    val width = if (bar.width == 0 && bar.height == 0) 0 else bar.width + startBleedPx
                    layout(width, bar.height) { bar.placeRelative(startBleedPx, 0) }
                }) { bottomBar() }
            },
        ) { padding ->
            val headerBottom = chromeContentTopInset(
                measured = padding.calculateTopPadding(),
                headerTop = headerTop,
                systemTop = systemTop,
                root = root,
                topBarHeight = expectedTopBarHeight,
            )
            SideEffect {
                if (!delegatesHeader) geometry.update(owner, headerTop, headerBottom, depth, bodySource, hasSecondaryRow)
                else geometry.remove(owner)
            }
            val sidesAndBottom = chromeBodyPadding(padding, direction, delegatesHeader)
            Box(
                modifier = Modifier.fillMaxSize()
                    .padding(start = startBleed)
                    .padding(sidesAndBottom)
                    .consumeWindowInsets(if (delegatesHeader) PaddingValues(
                        top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(),
                    ) else padding)
                    .then(if (!delegatesHeader && enabled && contentSource == null && activeSource === bodySource && headerBottom > 0.dp) {
                        Modifier.hazeSource(bodySource)
                    } else Modifier),
            ) {
                CompositionLocalProvider(
                    LocalChromeTopInset provides headerBottom,
                    LocalChromeGeometry provides if (delegatesHeader) null else geometry,
                    LocalChromeDepth provides if (delegatesHeader) 0 else depth,
                ) { content() }
            }
        }
    }
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun BoxScope.ChromeMaterial(
    state: HazeState,
    enabled: Boolean,
    topOffset: Dp,
    geometry: ChromeHeaderGeometry,
    canvas: Color = MeloraAppearance.canvas,
    hasSecondaryRow: Boolean = false,
    knownBottom: Dp,
) {
    val density = LocalDensity.current
    if (!enabled) {
        Box(Modifier.matchParentSize().background(canvas))
        return
    }
    val bounds by remember(geometry, topOffset, knownBottom) {
        derivedStateOf { (geometry.minimumTop - topOffset) to (geometry.materialBottom(knownBottom) - topOffset) }
    }
    val start = with(density) { bounds.first.toPx() }
    val end = with(density) { bounds.second.toPx() }.coerceAtLeast(start + 1f)
    val protectActions by remember(geometry, hasSecondaryRow) {
        derivedStateOf { hasSecondaryRow || geometry.hasSecondaryRow }
    }
    val fadeHeight = with(density) { if (protectActions) 28.dp.roundToPx() else 0 }
    // 两端斜率归零，避免操作栏下沿与正文交界出现线性渐变的横带。
    // 仅双行材质使用平滑曲线；单行保留原来的线性遮色与60%渐隐。
    val smoothRamp = remember {
        List(17) { index ->
            val t = index / 16f
            t * t * (3f - 2f * t)
        }
    }
    val ramp = remember(protectActions) { if (protectActions) smoothRamp else listOf(0f, 1f) }
    // 整个操作区保持完整模糊；只有最后一栏下面的绘制尾部渐隐。
    // 用实际画布高度兜住首次测量（未知栏高尚未登记），不依赖下一帧回写尺寸。
    val fade = remember(start, end, fadeHeight, protectActions, ramp) {
        val colors = ramp.map { Color.Black.copy(alpha = 1f - it) }
        object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val bottom = maxOf(end, size.height - fadeHeight)
                val fadeStart = if (protectActions) bottom else start + (bottom - start) * 0.60f
                return LinearGradientShader(
                    Offset(0f, fadeStart), Offset(0f, bottom + fadeHeight),
                    colors,
                )
            }
        }
    }
    val veil = remember(canvas, start, end, fadeHeight, fade, protectActions, ramp) {
        val colors = ramp.map { canvas.copy(alpha = canvas.alpha * (1f - it * if (protectActions) 0.35f else 1f)) }
        val tint = object : ShaderBrush() {
            override fun createShader(size: Size) = LinearGradientShader(
                Offset(0f, start), Offset(0f, maxOf(end, size.height - fadeHeight)),
                colors,
            )
        }
        if (protectActions) Brush.composite(tint, fade, BlendMode.DstIn) else tint
    }
    val direction = LocalLayoutDirection.current
    val startBleedPx = with(density) { LocalChromeStartBleed.current.roundToPx() }
    val (materialMask, materialVeil) = remember(fade, veil, startBleedPx, direction) {
        if (startBleedPx == 0) fade to veil else {
            val colors = smoothRamp.map { Color.Black.copy(alpha = it) }
            val edge = object : ShaderBrush() {
                override fun createShader(size: Size) = LinearGradientShader(
                    from = Offset(if (direction == LayoutDirection.Ltr) 0f else size.width, 0f),
                    to = Offset(if (direction == LayoutDirection.Ltr) startBleedPx.toFloat() else size.width - startBleedPx, 0f),
                    colors = colors,
                )
            }
            // 只在边界外渐隐，不再向正文内部刷一条画布色带。
            Brush.composite(fade, edge, BlendMode.DstIn) to Brush.composite(veil, edge, BlendMode.DstIn)
        }
    }
    Box(
        Modifier.matchParentSize()
            .layout { measurable, constraints ->
                // 不扩张父栏尺寸或触摸范围；上面的栏不伸进下一行，避免重复叠加材质。
                val extension = if (constraints.maxHeight >= end.roundToInt()) fadeHeight else 0
                val height = constraints.maxHeight + extension
                val width = constraints.maxWidth + startBleedPx
                val layer = measurable.measure(constraints.copy(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height))
                layout(constraints.maxWidth, constraints.maxHeight) { layer.placeRelative(-startBleedPx, 0) }
            }
            .clipToBounds()
            .hazeBlur(
                input = HazeInput.Sources(state, retention = HazeSourceRetention.ClearWhenUnavailable),
                style = HazeBlurStyle {
                    backgroundColor(canvas)
                    colorEffects(emptyList())
                    blurRadius(20.dp)
                    noiseFactor(0f)
                    fallbackColorEffect(HazeColorEffect.tint(canvas))
                    // 使用系统高斯与渐隐遮罩，不引入可变半径内核。
                    progressive(null)
                    mask(materialMask)
                },
                // 对应原全分辨率采样，避免降采样改变渐变末端的边缘与细节。
                performanceMode = HazePerformanceMode.Quality,
            ).drawBehind { drawRect(materialVeil) },
    )
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
    val activeSource by remember(geometry, depth, state) {
        derivedStateOf { geometry.sourceFor(depth, state) }
    }
    val density = LocalDensity.current
    // 该栏布局高度已知，同帧按物理像素对齐登记，不再等待onSizeChanged回写下一帧。
    val layoutHeight = with(density) { height.roundToPx().toDp() }
    DisposableEffect(geometry, owner) { onDispose { geometry.remove(owner) } }
    SideEffect {
        if (pinned) geometry.update(owner, topOffset, topOffset + layoutHeight, depth, source = null, hasSecondaryRow = true) else geometry.remove(owner)
    }
    Box(
        modifier = modifier.height(height),
    ) {
        ChromeMaterial(activeSource, enabled && pinned, topOffset, geometry, hasSecondaryRow = pinned, knownBottom = topOffset + layoutHeight)
        Box(Modifier.matchParentSize().clipToBounds()) {
            CompositionLocalProvider(LocalChromeBlurEnabled provides (enabled && pinned)) { content() }
        }
    }
}
