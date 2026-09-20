package com.leyu.melora.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * 每次真实滚动手势最多请求一页，避免分页尾部仅因进入组合就连续拉取全部数据。
 */
@Composable
internal fun LoadMoreOnScroll(
    listState: LazyListState,
    enabled: Boolean,
    loading: Boolean,
    prefetchDistance: Int = 2,
    onLoadMore: () -> Unit,
) {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestLoading by rememberUpdatedState(loading)
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    var requestedInCurrentScroll by remember(listState) { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            LoadMoreSnapshot(
                isScrollInProgress = listState.isScrollInProgress,
                enabled = latestEnabled,
                loading = latestLoading,
                totalItems = layout.totalItemsCount,
                lastVisibleItem = layout.visibleItemsInfo.lastOrNull()?.index ?: -1,
            )
        }
            .distinctUntilChanged()
            .collect { snapshot ->
                if (!snapshot.isScrollInProgress) {
                    requestedInCurrentScroll = false
                } else if (!requestedInCurrentScroll && snapshot.shouldRequest(prefetchDistance)) {
                    requestedInCurrentScroll = true
                    latestLoadMore()
                }
            }
    }
}

private data class LoadMoreSnapshot(
    val isScrollInProgress: Boolean,
    val enabled: Boolean,
    val loading: Boolean,
    val totalItems: Int,
    val lastVisibleItem: Int,
) {
    fun shouldRequest(prefetchDistance: Int): Boolean = shouldRequestNextPage(
        isScrollInProgress = isScrollInProgress,
        enabled = enabled,
        loading = loading,
        totalItems = totalItems,
        lastVisibleItem = lastVisibleItem,
        prefetchDistance = prefetchDistance,
    )
}
