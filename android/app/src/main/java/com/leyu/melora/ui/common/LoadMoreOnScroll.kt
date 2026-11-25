package com.leyu.melora.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

internal fun shouldRequestNextPage(
    isScrollInProgress: Boolean,
    enabled: Boolean,
    loading: Boolean,
    totalItems: Int,
    lastVisibleItem: Int,
    prefetchDistance: Int,
): Boolean = isScrollInProgress &&
    enabled &&
    !loading &&
    totalItems > 0 &&
    lastVisibleItem >= totalItems - 1 - prefetchDistance

/**
 * 每次滚动最多预取一页。指定页脚键时，内容不足一屏也会补页，同一批结果只自动补一次。
 */
@Composable
internal fun LoadMoreOnScroll(
    listState: LazyListState,
    enabled: Boolean,
    loading: Boolean,
    prefetchDistance: Int = 2,
    fillViewportFooterKey: Any? = null,
    onLoadMore: () -> Unit,
) {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestLoading by rememberUpdatedState(loading)
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    val latestFooterKey by rememberUpdatedState(fillViewportFooterKey)

    LaunchedEffect(listState) {
        var requestedInCurrentScroll = false
        var lastFilledKey: Any? = null
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            val scrolling = listState.isScrollInProgress
            LoadMoreSnapshot(
                scrolling = scrolling,
                nearEndDuringScroll = shouldRequestNextPage(
                    scrolling, latestEnabled, latestLoading, layout.totalItemsCount, lastVisible?.index ?: -1, prefetchDistance,
                ),
                shortPageKey = latestFooterKey?.takeIf {
                    latestEnabled && !latestLoading && it == lastVisible?.key &&
                        !listState.canScrollForward && !listState.canScrollBackward
                },
            )
        }.collect { snapshot ->
            if (!snapshot.scrolling) requestedInCurrentScroll = false
            val fillViewport = snapshot.shortPageKey != null && snapshot.shortPageKey != lastFilledKey
            if (fillViewport || (snapshot.nearEndDuringScroll && !requestedInCurrentScroll)) {
                if (fillViewport) lastFilledKey = snapshot.shortPageKey
                requestedInCurrentScroll = snapshot.scrolling
                latestLoadMore()
            }
        }
    }
}

private data class LoadMoreSnapshot(
    val scrolling: Boolean,
    val nearEndDuringScroll: Boolean,
    val shortPageKey: Any?,
)
