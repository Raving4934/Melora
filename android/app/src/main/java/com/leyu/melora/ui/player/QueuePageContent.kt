package com.leyu.melora.ui.player

import com.leyu.melora.ui.theme.SystemBarsVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 18.dp,
    isVisible: Boolean = true,
    motionEnabled: Boolean = true,
    onSelect: (Int) -> Unit = PlaybackController::jumpTo,
    onRemove: (Int) -> Unit = PlaybackController::removeFromQueue,
) {
    val current = state.current
    var showClearConfirm by remember { mutableStateOf(false) }
    val order = state.queueOrder
    val currentRow = remember(order, state.currentIndex) { order.indexOf(state.currentIndex) }
    // 身份来自原队列中的歌曲及重复次数，不随随机显示顺序/前面其他歌曲的删除改变。
    val rowKeys = remember(state.queue) {
        val occurrences = mutableMapOf<String, Int>()
        state.queue.map { track ->
            val occurrence = occurrences.getOrDefault(track.uid, 0)
            occurrences[track.uid] = occurrence + 1
            "${track.uid}:$occurrence"
        }
    }
    val currentKey by rememberUpdatedState(rowKeys.getOrNull(state.currentIndex))
    val listState = rememberLazyListState()
    var followCurrent by remember { mutableStateOf(true) }
    var browseAnchorKey by remember { mutableStateOf<String?>(null) }
    val anchorRow = if (followCurrent) currentRow else order.indexOf(rowKeys.indexOf(browseAnchorKey))
    // 只旋转引擎索引的展示起点，不重排播放器：当前项是真正的列表顶端，下滑仍能交回队列页。
    val displayOrder = remember(order, anchorRow) {
        if (anchorRow <= 0) order else order.drop(anchorRow) + order.take(anchorRow)
    }
    var alignedOrder by remember { mutableStateOf<List<Int>?>(null) }
    SideEffect {
        if (isVisible && followCurrent) {
            if (alignedOrder != displayOrder) {
                alignedOrder = displayOrder
                // 在下一次测量使用新起点，不让LazyColumn把旧的首行保持到队尾；位移动画由行key接续。
                listState.requestScrollToItem(0)
            }
        } else if (!isVisible) alignedOrder = null
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && followCurrent) {
                browseAnchorKey = currentKey
                followCurrent = false
            } else if ((interaction is DragInteraction.Stop || interaction is DragInteraction.Cancel) &&
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            ) {
                // 顶部下拉交给外层收起、不曾浏览列表的手势，不能误关歌曲跟随。
                followCurrent = true
            }
        }
    }
    LaunchedEffect(isVisible, state.queueId) {
        if (isVisible) followCurrent = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = horizontalPadding),
    ) {
        // 顶部返回提示
        Text(
            text = "收起播放队列",
            style = MaterialTheme.typography.labelSmall,
            color = FullPlayerTextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClose,
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
                    transitionSpec = {
                        (fadeIn(if (motionEnabled) tween(180) else snap()) togetherWith
                            fadeOut(if (motionEnabled) tween(120) else snap())).using(null)
                    },
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
                text = "${(currentRow + 1).coerceAtLeast(0)} / ${state.queue.size}",
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
                state = listState,
                modifier = Modifier.weight(1f).testTag("playback-queue-list"),
            ) {
                items(displayOrder, key = { rowKeys[it] }) { index ->
                    val track = state.queue[index]
                    val isPlaying = index == state.currentIndex
                    val selectionColor by animateColorAsState(
                        if (isPlaying) LocalPlayerColors.current.queueSelected else Color.Transparent,
                        if (motionEnabled) tween(160) else snap(), label = "queueSelection",
                    )
                    Surface(
                        onClick = { onSelect(index) },
                        shape = RoundedCornerShape(14.dp),
                        color = selectionColor,
                        modifier = Modifier.fillMaxWidth()
                            .animateItem(
                                fadeInSpec = if (motionEnabled) tween(140) else null,
                                placementSpec = if (motionEnabled) spring(dampingRatio = 1f, stiffness = 550f) else null,
                                fadeOutSpec = if (motionEnabled) tween(100) else null,
                            )
                            .testTag("queue-track-$index")
                            .semantics { selected = isPlaying },
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
                                onClick = { onRemove(index) },
                                modifier = Modifier.size(QueueActionSize).testTag("queue-remove-$index"),
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
