package com.leyu.melora.ui.common

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val SmoothTopWindowItems = 3

/** 远距离回顶先瞬移到顶部附近，避免 animateScrollToItem 跨越大量 item 时低帧且耗时。 */
internal fun fastScrollAnchor(firstVisibleItemIndex: Int): Int? =
    if (firstVisibleItemIndex > SmoothTopWindowItems) SmoothTopWindowItems else null

internal suspend fun LazyListState.fastScrollToTop() {
    if (!canScrollBackward) return
    // 先以最高优先级抢占手势/fling，避免第一次点击只负责取消惯性、第二次才真正回顶。
    scroll(MutatePriority.PreventUserInput) { }
    fastScrollAnchor(firstVisibleItemIndex)?.let { scrollToItem(it) }
    animateScrollToItem(0)
}

private class ScrollToTopLaunchState(var job: Job? = null)

/** 双击后立即抢占当前 fling；重复触发只保留最后一个回顶任务。 */
@Composable
internal fun rememberFastScrollToTop(listState: LazyListState): () -> Unit {
    val scope = rememberCoroutineScope()
    val launchState = remember(listState) { ScrollToTopLaunchState() }
    return remember(listState, scope, launchState) {
        {
            launchState.job?.cancel()
            launchState.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                listState.fastScrollToTop()
            }
        }
    }
}

/** 外部标题栏通过单调递增 request 驱动当前页面回顶。 */
@Composable
internal fun FastScrollToTopEffect(request: Int, listState: LazyListState) {
    val active = LocalPageActive.current
    LaunchedEffect(request, listState) {
        if (active && request > 0) listState.fastScrollToTop()
    }
}

/** 标题区域消费单击以阻断穿透；只有双击才回到列表顶部。 */
@Composable
internal fun Modifier.titleScrollToTop(onDoubleClick: () -> Unit): Modifier = combinedClickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = {},
    onDoubleClick = onDoubleClick,
)
