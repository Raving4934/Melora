package com.leyu.melora.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.leyu.melora.ui.theme.MeloraAppearance

/** 通用下拉刷新容器：仅在列表处于最顶端时响应下拉，阈值清脆自然。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefreshContainer(
    enabled: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    canPull: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    if (!enabled) {
        Box(modifier = modifier.fillMaxSize(), content = content)
        return
    }
    val state = rememberPullToRefreshState()
    Box(
        modifier = modifier
            .fillMaxSize()
            // 只有当内容处于顶端且允许下拉时才激活刷新，避免滚动中或横滑时误触
            .pullToRefresh(
                isRefreshing = refreshing,
                state = state,
                enabled = canPull,
                threshold = 96.dp,
                onRefresh = onRefresh,
            ),
    ) {
        content()
        RefreshBadge(
            state = state,
            refreshing = refreshing,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = LocalChromeTopInset.current),
        )
    }
}

// 刷新徽标：白色小圆卡 + 品牌蓝细环，跟手滑动缩放淡入，替代默认的大圆指示器
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshBadge(
    state: PullToRefreshState,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fraction = (if (refreshing) 1f else state.distanceFraction).coerceIn(0f, 1f)
    val travelPx = with(density) { 52.dp.toPx() }
    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .graphicsLayer {
                translationY = fraction * travelPx
                alpha = fraction
                val scale = 0.72f + 0.28f * fraction
                scaleX = scale
                scaleY = scale
            }
            .size(38.dp)
            .shadow(elevation = 4.dp, shape = CircleShape, clip = false)
            .background(MeloraAppearance.card, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = MeloraAppearance.brand,
            strokeWidth = 2.2.dp,
            trackColor = MeloraAppearance.divider,
        )
    }
}
