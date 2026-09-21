package com.leyu.melora.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlin.math.roundToInt

/** 离场页与不可见底页保留列表位置，但不能争抢返回或点击。 */
internal val LocalPageActive = compositionLocalOf { true }
private val PageEnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val PageReturnEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

internal fun pageMotionSpec(entering: Boolean) = tween<Float>(
    durationMillis = if (entering) 280 else 300,
    easing = if (entering) PageEnterEasing else PageReturnEasing,
)

/** 两页共享整数像素位移；页边始终相接，避免各自取整产生细缝或重叠。 */
internal fun pageTranslationX(progress: Float, width: Float, detail: Boolean): Float =
    (if (detail) width else 0f) - (progress.coerceIn(0f, 1f) * width).roundToInt()

internal fun pageReturnInProgress(targetExists: Boolean, retainedExists: Boolean): Boolean =
    !targetExists && retainedExists

@Composable
internal fun PageBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled && LocalPageActive.current, onBack = onBack)
}

private fun Modifier.pageInput(active: Boolean): Modifier = if (active) this else
    clearAndSetSemantics { }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

/** 相邻两页在同一平面移动；只保留底页和退出中的详情，不另建分页导航或第二套动画。 */
@Composable
internal fun <T : Any> DetailPageHost(
    target: T?,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any = { it },
    detail: @Composable (T) -> Unit,
    content: @Composable () -> Unit,
) {
    val parentActive = LocalPageActive.current
    val progress = remember { Animatable(if (target == null) 0f else 1f) }
    var retained by remember { mutableStateOf(target) }
    // 同一key的详情数据更新不重启转场，退出时仍使用最后展示的那份数据。
    SideEffect { if (target != null) retained = target }
    LaunchedEffect(target != null) {
        val destination = if (target == null) 0f else 1f
        if (progress.value != destination) progress.animateTo(destination, pageMotionSpec(entering = target != null))
        // 中途重新进入会取消这次退出，不能提前清掉仍在画面上的详情。
        if (target == null) retained = null
    }
    val visible = target ?: retained
    Box(modifier.fillMaxSize().clipToBounds()) {
        val baseActive = parentActive && target == null
        CompositionLocalProvider(LocalPageActive provides baseActive) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationX = pageTranslationX(progress.value, size.width, detail = false)
                // 完全移出视口才停绘；可见阶段不做透明度动画，也不重绘隐藏页的模糊材质。
                alpha = if (translationX <= -size.width) 0f else 1f
                clip = true
            }.pageInput(baseActive)) { content() }
        }
        if (visible != null) key(contentKey(visible)) {
            val active = parentActive && target != null
            CompositionLocalProvider(LocalPageActive provides active) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    translationX = pageTranslationX(progress.value, size.width, detail = true)
                    alpha = if (translationX >= size.width) 0f else 1f
                    clip = true
                }.background(MeloraAppearance.canvas).pageInput(active)) { detail(visible) }
            }
        }
    }
    // 返回途中重复Back只消费本次退场，不穿透到底页退出Activity。
    PageBackHandler(enabled = pageReturnInProgress(target != null, visible != null)) { }
}
