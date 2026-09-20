package com.leyu.melora.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.PlaybackController

/**
 * 卡片播放按钮：
 * - 仅首次请求队列时显示等尺寸进度环，并禁用重复网络请求。
 * - 活动队列始终按播放意图显示播放/暂停，解析或缓冲不能锁住暂停按钮。
 */
@Composable
fun CardPlayButton(
    queueId: String,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    iconSize: Dp = 18.dp,
    background: Color = Color.Black.copy(alpha = 0.45f),
    tint: Color = Color.White,
) {
    val state by PlaybackController.state.collectAsStateWithLifecycle()
    val active = state.queueId == queueId
    val loading = !active && state.pendingQueueId == queueId
    val playing = active && state.playing
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(
                enabled = !loading,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                if (active) PlaybackController.toggle() else onPlay()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize),
                color = tint,
                strokeWidth = 1.8.dp,
            )
        } else {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
