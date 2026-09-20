package com.leyu.melora.ui.player

import com.leyu.melora.ui.theme.SystemBarsVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.PlayMode
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.ui.common.SongArtwork

// 表头和单曲行共享右侧操作槽，清除文字与移除图标中心保持同轴。
private val QueueContentInset = 14.dp
private val QueueActionSize = 48.dp

// VerticalPager Page 1: 播放队列页
@Composable
fun QueuePageContent(
    state: PlayerUiState,
    onBackToPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 18.dp,
) {
    val current = state.current
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = horizontalPadding),
    ) {
        // 顶部返回提示
        Text(
            text = "此处向下轻扫以返回播放界面",
            style = MaterialTheme.typography.labelSmall,
            color = FullPlayerTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onBackToPlayer,
                )
                .padding(vertical = 12.dp),
        )

        // 当前播放曲目卡片
        if (current != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = FullPlayerCardSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AnimatedContent(
                    targetState = current,
                    contentKey = { it.uid },
                    transitionSpec = { (fadeIn(tween(240)) togetherWith fadeOut(tween(160))).using(null) },
                    label = "queueCurrentTrack",
                ) { displayed ->
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SongArtwork(displayed.artwork, displayed.uid, Modifier.size(46.dp), cornerRadius = 8)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        ) {
                            Text(
                                text = displayed.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = FullPlayerTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(displayed.artist, displayed.album.takeIf { !it.isNullOrBlank() })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = FullPlayerTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 两侧等宽，队列数量变化时标题不会横向移动。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QueueContentInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.queue.isEmpty()) "0 / 0" else "${(state.currentIndex + 1).coerceAtLeast(1)} / ${state.queue.size}",
                style = MaterialTheme.typography.bodySmall,
                color = FullPlayerTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "播放队列",
                style = MaterialTheme.typography.titleMedium,
                color = FullPlayerTextPrimary,
                maxLines = 1,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                TextButton(
                    onClick = { showClearConfirm = true },
                    enabled = state.queue.isNotEmpty(),
                    modifier = Modifier.size(QueueActionSize),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = "清除",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.queue.isEmpty()) FullPlayerTextMuted.copy(alpha = 0.4f) else FullPlayerTextMuted,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 队列列表或空状态展示（清空后留在页面，绝不误闪退）
        if (state.queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "播放队列为空",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = FullPlayerTextMuted,
                    )
                    Text(
                        text = "去发现更多好音乐吧",
                        fontSize = 13.sp,
                        color = FullPlayerTextMuted.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.queue, key = { index, item -> "${item.uid}:$index" }) { index, track ->
                    val isPlaying = index == state.currentIndex
                    Surface(
                        onClick = { PlaybackController.jumpTo(index) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isPlaying) LocalPlayerColors.current.queueSelected else Color.Transparent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = QueueContentInset, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = FullPlayerTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FullPlayerTextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { PlaybackController.removeFromQueue(index) },
                                modifier = Modifier.size(QueueActionSize),
                            ) {
                                Icon(
                                    Icons.Rounded.Remove,
                                    contentDescription = "移除",
                                    tint = FullPlayerTextMuted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 底部循环模式胶囊
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Surface(
                onClick = { PlaybackController.cycleMode() },
                shape = RoundedCornerShape(20.dp),
                color = FullPlayerCardSurface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when (state.mode) {
                            PlayMode.List -> Icons.Rounded.Repeat
                            PlayMode.Single -> Icons.Rounded.RepeatOne
                            PlayMode.Shuffle -> Icons.Rounded.Shuffle
                        },
                        contentDescription = null,
                        tint = FullPlayerTextPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = when (state.mode) {
                            PlayMode.List -> "列表循环模式"
                            PlayMode.Single -> "单曲循环模式"
                            PlayMode.Shuffle -> "随机播放模式"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = FullPlayerTextPrimary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }

    // 清空播放队列二次确认弹窗
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = {
                SystemBarsVisibility()
                Text(
                    text = "清空播放队列",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = FullPlayerSheetTextPrimary,
                )
            },
            text = {
                Text(
                    text = "是否清空当前播放队列？",
                    fontSize = 14.sp,
                    color = FullPlayerSheetTextMuted,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        PlaybackController.clearQueue()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FullPlayerSheetPrimaryBlue),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("确定", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirm = false },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("取消", color = FullPlayerSheetTextMuted)
                }
            },
            containerColor = FullPlayerSheetBackground,
            shape = RoundedCornerShape(20.dp),
        )
    }
}
