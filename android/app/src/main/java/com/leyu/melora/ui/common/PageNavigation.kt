package com.leyu.melora.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.leyu.melora.ui.theme.MeloraAppearance

/** 离场页与被覆盖的底页保留绘制/列表位置，但不能争抢返回或点击。 */
internal val LocalPageActive = compositionLocalOf { true }
private val PageEnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private object BasePageKey
private val PageReturnEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** 底页视差和前页位移采用同一方向规格，快速反向时不能一边280ms、一边300ms。 */
internal fun pageMotionSpec(entering: Boolean) = tween<Float>(
    durationMillis = if (entering) 280 else 300,
    easing = if (entering) PageEnterEasing else PageReturnEasing,
)

internal fun pageReturnInProgress(currentOpen: Boolean, targetOpen: Boolean, running: Boolean): Boolean =
    !targetOpen && (currentOpen || running)

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

/** 层级导航：只保留返回底页，详情在退出动画完成后释放；所有位移只更新绘制图层。 */
@Composable
internal fun <T : Any> DetailPageHost(
    target: T?,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any = { it },
    detail: @Composable (T) -> Unit,
    content: @Composable () -> Unit,
) {
    val parentActive = LocalPageActive.current
    val page = updateTransition(target, label = "detailPage")
    val baseShift = page.animateFloat(
        transitionSpec = { pageMotionSpec(entering = targetState != null) }, label = "pageParallax",
    ) { if (it == null) 0f else -0.25f }
    Box(modifier.fillMaxSize().clipToBounds()) {
        val baseActive = parentActive && target == null
        CompositionLocalProvider(LocalPageActive provides baseActive) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = if (page.currentState == null) size.width * baseShift.value else 0f
                    alpha = if (target != null && page.currentState == page.targetState && !page.isRunning) 0f else 1f
                }.pageInput(baseActive),
            ) { content() }
        }
        page.AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            contentKey = { it?.let(contentKey) ?: BasePageKey },
            transitionSpec = {
                (EnterTransition.None togetherWith ExitTransition.None)
                    .apply { targetContentZIndex = if (targetState != null) 1f else -1f }.using(null)
            },
        ) { visible ->
            if (visible == null) return@AnimatedContent
            val offset = transition.animateFloat(
                transitionSpec = { pageMotionSpec(entering = targetState == EnterExitState.Visible) },
                label = "detailOffset",
            ) { if (it == EnterExitState.Visible) 0f else 1f }
            val active = parentActive && target != null && contentKey(visible) == contentKey(target)
            CompositionLocalProvider(LocalPageActive provides active) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer { translationX = size.width * offset.value }
                        .background(MeloraAppearance.canvas).pageInput(active),
                ) { detail(visible) }
            }
        }
    }
    // 放在底页/详情之后登记，避免返回尾段被底页的返回处理抢先消费。
    PageBackHandler(enabled = pageReturnInProgress(page.currentState != null, target != null, page.isRunning)) { }
}
