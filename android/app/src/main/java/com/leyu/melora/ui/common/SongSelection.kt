package com.leyu.melora.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.Downloader
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.theme.MeloraAppearance

/** 按歌曲身份选择；分页重叠或列表更新时，操作仅作用于当前仍存在的歌曲。 */
@Stable
internal class SongSelectionState {
    var active by mutableStateOf(false)
        private set
    var selectedUids by mutableStateOf<Set<String>>(emptySet())
        private set

    fun start() { selectedUids = emptySet(); active = true }
    fun finish() { active = false; selectedUids = emptySet() }
    fun toggle(uid: String) {
        selectedUids = if (uid in selectedUids) selectedUids - uid else selectedUids + uid
    }
    fun allSelected(songs: List<OnlineSong>): Boolean = songs.isNotEmpty() && songs.all { it.uid in selectedUids }

    fun toggleAll(songs: List<OnlineSong>) {
        val available = songs.mapTo(linkedSetOf()) { it.uid }
        selectedUids = if (selectedUids.containsAll(available)) emptySet() else available
    }
    fun selectedSongs(songs: List<OnlineSong>): List<OnlineSong> =
        songs.filter { it.uid in selectedUids }.distinctBy { it.uid }
}

@Composable
internal fun SongSelectionTopBar(
    selection: SongSelectionState,
    songs: List<OnlineSong>,
    normal: @Composable () -> Unit,
) {
    Crossfade(targetState = selection.active, animationSpec = tween(180), label = "batchTopBar") { batch ->
        if (!batch) {
            normal()
        } else {
            // 三槽位布局：左「全选」与列表内容左缘对齐，标题绝对居中，右关闭与列表内容右缘对齐
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp)
                        .sizeIn(minWidth = 88.dp, minHeight = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            indication = null, interactionSource = remember { MutableInteractionSource() },
                        ) { selection.toggleAll(songs) }
                        .padding(end = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        if (selection.allSelected(songs)) "取消全选" else "全选",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = BrandBlue,
                        maxLines = 1, softWrap = false,
                    )
                }
                Text(
                    "已选中 ${selection.selectedSongs(songs).size} 项",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextMain,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.5.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(
                            indication = null, interactionSource = remember { MutableInteractionSource() },
                        ) { selection.finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(MeloraAppearance.softFill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Close, "退出批量管理", tint = TextSub, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SongSelectionButton(onClick: () -> Unit, enabled: Boolean = true) {
    ChromeActionSurface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(17.dp), modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Checklist, "批量管理", tint = if (enabled) TextSub else TextMuted, modifier = Modifier.size(18.dp))
        }
    }
}

/** 歌单与收藏共用操作条；收藏页只改变第一项的明确操作，不使用 toggle 批量反转。 */
@Composable
internal fun SongBatchActionsBar(
    selection: SongSelectionState,
    songs: List<OnlineSong>,
    removeFavorites: Boolean = false,
) {
    val context = LocalContext.current
    var addToPlaylist by remember { mutableStateOf<List<OnlineSong>?>(null) }
    AnimatedVisibility(
        visible = selection.active,
        enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 2 },
    ) {
        val selected = selection.selectedSongs(songs)
        val enabled = selected.isNotEmpty()
        Column(Modifier.fillMaxWidth().background(chromeHeaderColor())) {
            HorizontalDivider(color = DividerSoft, thickness = 0.6.dp)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                BatchAction(
                    if (removeFavorites) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                    if (removeFavorites) "取消收藏" else "收藏", enabled,
                ) {
                    val changed = UserLibrary.setFavorites(selected, favorite = !removeFavorites)
                    PlaybackController.postMessage(context, when {
                        removeFavorites -> "已取消收藏 $changed 首"
                        changed == 0 -> "所选歌曲已在收藏中"
                        else -> "已收藏 $changed 首"
                    })
                    selection.finish()
                }
                BatchAction(Icons.AutoMirrored.Outlined.PlaylistAdd, "添加到歌单", enabled) { addToPlaylist = selected }
                BatchAction(Icons.AutoMirrored.Rounded.QueueMusic, "加入播放队列", enabled) {
                    selected.forEach { PlaybackController.addToQueue(context, UiTrack.fromOnline(it)) }
                    PlaybackController.postMessage(context, "已加入播放队列 ${selected.size} 首")
                    selection.finish()
                }
                BatchAction(Icons.Outlined.Download, "下载", enabled) {
                    val count = Downloader.enqueue(context, selected)
                    PlaybackController.postMessage(context, "已提交 $count 首下载请求")
                    selection.finish()
                }
            }
        }
    }
    addToPlaylist?.let { selected ->
        AddToPlaylistSheet(selected) {
            addToPlaylist = null
            selection.finish()
        }
    }
}

@Composable
private fun RowScope.BatchAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(
            enabled = enabled, indication = null, interactionSource = remember { MutableInteractionSource() },
        ) { onClick() }.padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (enabled) BrandBlue else TextMuted.copy(alpha = 0.45f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(
            label, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = if (enabled) TextMain else TextMuted.copy(alpha = 0.55f),
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}
