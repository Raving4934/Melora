package com.leyu.melora.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.leyu.melora.ui.theme.DarkGlass
import com.leyu.melora.ui.theme.LightSheetFrost
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.SystemBarsVisibility
import com.leyu.melora.ui.theme.currentMeloraSheetPalette
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 保留同一个状态与档位，手势阈值和业务确认回调始终读取最新值。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberMeloraBottomSheetState(
    skipPartiallyExpanded: Boolean = false,
    confirmValueChange: (SheetValue) -> Boolean = { true },
): SheetState {
    val density by rememberUpdatedState(LocalDensity.current)
    val confirm by rememberUpdatedState(confirmValueChange)
    val enabledValues = remember(skipPartiallyExpanded) {
        if (skipPartiallyExpanded) setOf(SheetValue.Hidden, SheetValue.Expanded)
        else setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
    }
    // 与 Material3 1.4 的默认阈值一致；lambda 不捕获首次创建时的 Density。
    val positionalThreshold = { with(density) { 56.dp.toPx() } }
    val velocityThreshold = { with(density) { 125.dp.toPx() } }
    val confirmChange: (SheetValue) -> Boolean = { confirm(it) }
    return rememberSaveable(
        enabledValues,
        saver = SheetState.Saver(
            enabledValues = enabledValues,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            confirmValueChange = confirmChange,
        ),
    ) {
        SheetState(
            enabledValues = enabledValues,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            confirmValueChange = confirmChange,
        )
    }
}

/** 内容滚动到下边界后，不能将剩余上抛速度交给抽屉的弹簧动画。下拉关闭仍由抽屉处理。 */
internal object SheetBoundaryFlingConnection : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y.coerceAtMost(0f))
}

/** 显式关闭与遮罩/返回一样先退场，再交还业务；重复点击不启动第二次关闭。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSheetDismiss(sheetState: SheetState): (() -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    var closing by remember(sheetState) { mutableStateOf(false) }
    return remember(sheetState, scope) {
        { onHidden ->
            if (!closing) {
                closing = true
                scope.launch {
                    try {
                        sheetState.hide()
                        if (!sheetState.isVisible) onHidden()
                    } finally {
                        closing = false
                    }
                }
            }
        }
    }
}

private val LocalGlassHandleColor = compositionLocalOf { Color.Unspecified }

@Composable
internal fun MeloraSheetHandle(
    color: Color = currentMeloraSheetPalette().textSub.copy(alpha = 0.25f),
    width: Dp = 36.dp,
    topPadding: Dp = 10.dp,
    bottomPadding: Dp = 6.dp,
) {
    val glassColor = LocalGlassHandleColor.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = topPadding, bottom = bottomPadding),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width, 4.dp)
                .background(if (glassColor.isSpecified) glassColor else color, RoundedCornerShape(2.dp)),
        )
    }
}

/** 统一抽屉的边界行为与随位移变化的遮罩，保留原生面板动画、档位及关闭手势。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeloraBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    containerColor: Color = currentMeloraSheetPalette().sheet,
    tonalElevation: Dp = 0.dp,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    lightFrostTint: Color = if (glassBackdropSource(containerColor) != null) LightSheetFrost.tint else Color.Unspecified,
    dragHandle: @Composable (() -> Unit)? = {
        val glassColor = LocalGlassHandleColor.current
        if (glassColor.isSpecified) BottomSheetDefaults.DragHandle(color = glassColor)
        else BottomSheetDefaults.DragHandle()
    },
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalPageActive.current) return
    val eInk = MeloraAppearance.isEInk
    val resolvedContainerColor = if (eInk) Color.White else containerColor
    val glassSource = if (eInk) null else glassBackdropSource(resolvedContainerColor)
    val lightFrost = !eInk && lightFrostTint.isSpecified && resolvedContainerColor.isSpecified &&
        resolvedContainerColor.alpha == 1f && resolvedContainerColor.luminance() > 0.5f
    val glassHandleColor = when {
        eInk -> Color.Black
        lightFrost -> LightSheetFrost.handle
        glassSource != null && resolvedContainerColor.luminance() <= 0.5f -> DarkGlass.handle
        else -> Color.Unspecified
    }
    val panelScrim = when {
        scrimColor != BottomSheetDefaults.ScrimColor -> scrimColor
        eInk -> Color.Black.copy(alpha = 0.25f)
        lightFrost -> LightSheetFrost.scrim
        glassSource != null -> Color.Black.copy(alpha = glassScrimAlpha(resolvedContainerColor))
        else -> scrimColor
    }
    // 窗口沿用系统 Density，避免应用缩放触发 Material 重建窗口及其 Composition。
    val contentDensity = LocalDensity.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemDensity = LocalResources.current.displayMetrics.density
    val windowDensity = remember(systemDensity, configuration.fontScale) { Density(context) }
    val windowScale = contentDensity.density / windowDensity.density
    val windowShape = remember(shape, contentDensity) {
        object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                shape.createOutline(size, layoutDirection, contentDensity)
        }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var handleHeight by remember(dragHandle != null) { mutableIntStateOf(0) }
    CompositionLocalProvider(LocalDensity provides windowDensity) {
        ModalBottomSheet(
            // 外层位于 Material3 的位移和 Surface 裁剪之前，遮罩不会随面板移动。
            modifier = Modifier
                .layout { measurable, constraints ->
                    val sheet = measurable.measure(constraints)
                    viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
                    layout(sheet.width, sheet.height) { sheet.place(0, 0) }
                }
                .drawBehind {
                    if (panelScrim == Color.Unspecified || size.height == 0f) return@drawBehind
                    val height = viewport.height.toFloat()
                    val visibleHeight = if (sheetState.hasPartiallyExpandedState) {
                        minOf(size.height, height / 2f)
                    } else {
                        size.height.coerceAtMost(height)
                    }
                    val progress = ((height - sheetState.requireOffset()) / visibleHeight)
                        .coerceIn(0f, 1f)
                    drawRect(
                        color = panelScrim,
                        alpha = progress,
                        topLeft = Offset((size.width - viewport.width) / 2f, 0f),
                        size = Size(viewport.width.toFloat(), height),
                    )
                },
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            sheetMaxWidth = BottomSheetDefaults.SheetMaxWidth * windowScale,
            containerColor = when {
                glassSource != null -> Color.Transparent
                eInk -> Color.White
                lightFrost -> LightSheetFrost.base
                else -> resolvedContainerColor
            },
            contentColor = contentColorFor(resolvedContainerColor),
            // 保留原生遮罩的点击/无障碍关闭；可视遮罩只由上方物理位移驱动。
            scrimColor = if (scrimColor == Color.Unspecified) Color.Unspecified else Color.Transparent,
            tonalElevation = tonalElevation,
            shape = windowShape,
            contentWindowInsets = {
                if (glassSource == null) BottomSheetDefaults.modalWindowInsets else WindowInsets(0, 0, 0, 0)
            },
            dragHandle = dragHandle?.let { handle ->
                {
                    CompositionLocalProvider(
                        LocalDensity provides contentDensity,
                        LocalGlassHandleColor provides glassHandleColor,
                    ) {
                        if (glassSource == null) handle()
                        else Box(Modifier.onSizeChanged { handleHeight = it.height }) { handle() }
                    }
                }
            },
        ) {
            SystemBarsVisibility()
            CompositionLocalProvider(
                LocalOverscrollFactory provides null,
                LocalDensity provides contentDensity,
            ) {
                val nestedSource = if (glassSource == null) null else rememberHazeState()
                val backdropModifier = if (nestedSource == null) Modifier
                    else Modifier.zIndex(-1f).hazeSource(nestedSource)
                Box(Modifier.fillMaxWidth().then(backdropModifier)) {
                    if (glassSource != null) {
                        // 由原生面板负责裁切与位移，将底板向上延伸到原生把手区域。
                        Box(
                            Modifier.matchParentSize()
                                .layout { measurable, constraints ->
                                    val height = constraints.maxHeight + handleHeight
                                    val panel = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                                    layout(panel.width, constraints.maxHeight) { panel.place(0, -handleHeight) }
                                }
                                .glassPanel(containerColor, windowShape, backdropScrim = panelScrim,
                                    frostTint = if (lightFrost) lightFrostTint else Color.Unspecified),
                        )
                    }
                    val insetsModifier = if (glassSource == null) Modifier
                        else Modifier.windowInsetsPadding(BottomSheetDefaults.modalWindowInsets)
                    CompositionLocalProvider(LocalGlassBackdrop provides (nestedSource ?: LocalGlassBackdrop.current)) {
                        Column(
                            Modifier.fillMaxWidth()
                                .then(insetsModifier)
                                .nestedScroll(SheetBoundaryFlingConnection)
                                // 静态菜单仍通过嵌套滚动交接手势，滚动距离由子列表消费。
                                .scrollable(rememberScrollableState { 0f }, Orientation.Vertical),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

/** 歌单与有声书分类抽屉共用的标签胶囊，保持亮/暗与纯色/毛玻璃下的一致质感。 */
@Composable
internal fun SheetTagChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = currentMeloraSheetPalette()
    val eInk = palette.isEInk
    val dark = !eInk && palette.isDark
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = when {
            eInk -> if (isSelected) Color.Black else Color.White
            dark -> if (isSelected) DarkGlass.selectedFill else DarkGlass.chip
            isSelected -> LightSheetFrost.blue
            else -> LightSheetFrost.chip
        },
        contentColor = when {
            eInk -> if (isSelected) Color.White else Color.Black
            isSelected -> if (dark) DarkGlass.selectedText else Color.White
            else -> palette.textMain
        },
        border = when {
            eInk -> if (isSelected) null else BorderStroke(1.dp, Color.Black)
            dark -> BorderStroke(0.5.dp, if (isSelected) DarkGlass.selectedEdge else DarkGlass.border)
            isSelected -> null
            else -> LightSheetFrost.chipBorder
        },
        modifier = modifier.semantics { selected = isSelected },
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private val SheetButtonHorizontalPadding = 12.dp
private val SheetActionSpacing = 12.dp
private const val SHEET_BUTTON_MAX_LINES = 2

@Composable
private fun sheetButtonTextStyle(primary: Boolean) = LocalTextStyle.current.copy(
    fontSize = 14.sp,
    lineHeight = 18.sp,
    fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
)

/** 抽屉主/次胶囊按钮，统一亮暗色、毛玻璃与墨水屏语义配色。 */
@Composable
internal fun MeloraSheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    working: Boolean = false,
    minHeight: Dp = 44.dp,
    maxLines: Int = SHEET_BUTTON_MAX_LINES,
) {
    val palette = currentMeloraSheetPalette()
    val colors = MaterialTheme.colorScheme
    val accented = primary && (enabled || working)
    val fill = when {
        accented -> colors.primary
        primary -> palette.softFill
        palette.isEInk -> Color.White
        else -> palette.softFill
    }
    val content = when {
        accented -> colors.onPrimary
        primary -> palette.textSub
        enabled || working -> palette.textMain
        else -> palette.textMuted
    }
    val border = when {
        accented -> null
        primary && !palette.isEInk -> null
        palette.isEInk -> BorderStroke(1.dp, if (enabled || working) Color.Black else palette.divider)
        else -> palette.chipBorder
    }
    Surface(
        onClick = onClick,
        enabled = enabled && !working,
        shape = CircleShape,
        color = fill,
        contentColor = content,
        border = border,
        modifier = modifier.heightIn(min = minHeight),
    ) {
        Box(Modifier.padding(horizontal = SheetButtonHorizontalPadding, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                modifier = Modifier.alpha(if (working) 0f else 1f),
                style = sheetButtonTextStyle(primary),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (working) {
                CircularProgressIndicator(
                    color = content,
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** 抽屉底部双操作栏。歌单操作等含动态长文案的场景可在横排放不下时改为纵排。 */
@Composable
internal fun MeloraSheetActionBar(
    secondaryText: String,
    onSecondary: () -> Unit,
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryEnabled: Boolean = true,
    secondaryWorking: Boolean = false,
    primaryEnabled: Boolean = true,
    primaryWorking: Boolean = false,
    secondaryWeight: Float = 1f,
    primaryWeight: Float = 1f,
    minHeight: Dp = 44.dp,
    secondaryModifier: Modifier = Modifier,
    primaryModifier: Modifier = Modifier,
    stackWhenLabelsOverflow: Boolean = false,
) {
    val buttons: @Composable (Modifier, Modifier, Int) -> Unit = { secondary, primary, maxLines ->
        MeloraSheetButton(
            text = secondaryText,
            onClick = onSecondary,
            enabled = secondaryEnabled,
            working = secondaryWorking,
            minHeight = minHeight,
            maxLines = maxLines,
            modifier = secondary.then(secondaryModifier),
        )
        MeloraSheetButton(
            text = primaryText,
            onClick = onPrimary,
            primary = true,
            enabled = primaryEnabled,
            working = primaryWorking,
            minHeight = minHeight,
            maxLines = maxLines,
            modifier = primary.then(primaryModifier),
        )
    }
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(
            modifier = rowModifier.fillMaxWidth().height(IntrinsicSize.Max).heightIn(min = minHeight),
            horizontalArrangement = Arrangement.spacedBy(SheetActionSpacing),
        ) {
            buttons(Modifier.weight(secondaryWeight).fillMaxHeight(), Modifier.weight(primaryWeight).fillMaxHeight(), SHEET_BUTTON_MAX_LINES)
        }
    }
    if (!stackWhenLabelsOverflow) {
        row(modifier)
        return
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val spacingPx = with(density) { SheetActionSpacing.roundToPx() }
        val paddingPx = with(density) { SheetButtonHorizontalPadding.roundToPx() } * 2
        val buttonWidth = (constraints.maxWidth - spacingPx).coerceAtLeast(0)
        val primaryWidth = (buttonWidth * primaryWeight / (secondaryWeight + primaryWeight)).roundToInt()
        val secondaryWidth = buttonWidth - primaryWidth
        val secondaryStyle = sheetButtonTextStyle(primary = false)
        val primaryStyle = sheetButtonTextStyle(primary = true)
        val secondaryOverflows = measurer.measure(
            secondaryText, secondaryStyle, overflow = TextOverflow.Ellipsis, maxLines = SHEET_BUTTON_MAX_LINES,
            constraints = Constraints(maxWidth = (secondaryWidth - paddingPx).coerceAtLeast(0)),
        ).hasVisualOverflow
        val primaryOverflows = measurer.measure(
            primaryText, primaryStyle, overflow = TextOverflow.Ellipsis, maxLines = SHEET_BUTTON_MAX_LINES,
            constraints = Constraints(maxWidth = (primaryWidth - paddingPx).coerceAtLeast(0)),
        ).hasVisualOverflow
        if (secondaryOverflows || primaryOverflows) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SheetActionSpacing)) {
                buttons(Modifier.fillMaxWidth(), Modifier.fillMaxWidth(), Int.MAX_VALUE)
            }
        } else {
            row(Modifier)
        }
    }
}
