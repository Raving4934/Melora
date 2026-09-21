package com.leyu.melora.ui.local

import com.leyu.melora.ui.common.MeloraBottomSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack


import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.local.LocalSortField
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.DividerSoft
import com.leyu.melora.ui.common.SongSelectionState
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextMuted
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.chromeHeaderColor
import com.leyu.melora.ui.theme.MeloraAppearance

import kotlinx.coroutines.launch


/** 本地搜索页：搜索框 + 结果列表；排序与列表页共用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalSearchPage(
    songs: List<LocalSong>,
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    selection: SongSelectionState,
    onOpenSortSheet: () -> Unit,
    onMore: (LocalSong) -> Unit,
    onDeleteSelection: (List<LocalSong>) -> Unit,
    onAddToPlaylist: (List<OnlineSong>) -> Unit,
) {
    val context = LocalContext.current
    val playingLocalId = rememberPlayingLocalId()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val filtered = remember(songs, query) { if (query.isBlank()) songs else songs.filter { it.matches(query) } }

    ChromeScaffold(
        expectedTopBarHeight = if (query.isNotBlank() && filtered.isNotEmpty()) 110.dp else 64.dp,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(chromeHeaderColor()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(21.dp))
                            .background(MeloraAppearance.softFill)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    "在 ${songs.size} 首歌曲中搜索",
                                    fontSize = 13.sp,
                                    color = TextMuted,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = onQueryChange,
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 13.sp, color = TextMain),
                                cursorBrush = SolidColor(BrandBlue),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (query.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(MeloraAppearance.chipBorderColor)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onQueryChange("") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "清空", tint = TextSub, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "取消",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BrandBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onCancel() }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
                if (query.isNotBlank() && filtered.isNotEmpty()) {
                    LocalListHeader(
                        count = filtered.size,
                        songs = filtered,
                        selection = selection,
                        onPlayShuffle = {
                            PlaybackController.playQueue(
                                context,
                                filtered.shuffled().map { it.toOnlineSong() }.toUiTracks(),
                                0,
                                "local.songs",
                            )
                        },
                        onOpenSortSheet = onOpenSortSheet,
                        onStartSelection = { selection.start() },
                    )
                }
            }
        },
        bottomBar = {
            LocalBatchActionsBar(
                selection = selection,
                songs = filtered,
                onDelete = onDeleteSelection,
                onAddToPlaylist = onAddToPlaylist,
            )
        },
    ) {
        when {
            query.isBlank() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Inbox,
                        contentDescription = null,
                        tint = TextMuted.copy(alpha = 0.7f),
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        "输入关键词搜索本地歌曲",
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            filtered.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("未找到与「$query」相关的歌曲", fontSize = 13.sp, color = TextSub)
                }
            }
            else -> {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // 与本地歌曲页完全一致的列表内边距；字母索引条悬浮覆盖，不挤占行宽
                        contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                    ) {
                        itemsIndexed(filtered, key = { _, song -> song.id }) { index, song ->
                            LocalSongRow(
                                song = song,
                                selectionMode = selection.active,
                                selected = song.localUid() in selection.selectedUids,
                                isCurrent = song.id == playingLocalId,
                                onClick = {
                                    if (selection.active) {
                                        selection.toggle(song.localUid())
                                    } else {
                                        PlaybackController.playTrack(context, UiTrack.fromOnline(song.toOnlineSong()))
                                    }
                                },
                                onMore = { onMore(song) },
                            )
                        }
                    }
                }
            }
        }
    }
}



/** 升降排序抽屉：顶栏升降序双胶囊 + 6 档排序卡片行，高质感图标与清晰说明。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalSortSheet(
    field: LocalSortField,
    ascending: Boolean,
    onFieldChange: (LocalSortField) -> Unit,
    onDirectionChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    MeloraBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MeloraAppearance.card,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MeloraAppearance.divider),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "排序方式",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMain,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MeloraAppearance.softFill)
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SortDirectionSegment(
                        icon = Icons.Outlined.ArrowUpward,
                        label = "升序",
                        selected = ascending,
                        onClick = { onDirectionChange(true) },
                    )
                    SortDirectionSegment(
                        icon = Icons.Outlined.ArrowDownward,
                        label = "降序",
                        selected = !ascending,
                        onClick = { onDirectionChange(false) },
                    )
                }
            }

            HorizontalDivider(
                color = MeloraAppearance.divider,
                thickness = 0.6.dp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LocalSortField.entries.forEach { option ->
                    val selected = option == field
                    val (icon, subtitle) = when (option) {
                        LocalSortField.FileName -> Icons.Outlined.TextFields to "按文件名称字母与笔画顺序排布"
                        LocalSortField.Artist -> Icons.Outlined.Person to "按歌手/艺术家名称排布"
                        LocalSortField.Year -> Icons.Outlined.CalendarToday to "按音频标签记录的发行年份排布"
                        LocalSortField.Size -> Icons.Outlined.Storage to "按文件占用磁盘大小排布"
                        LocalSortField.ModifiedAt -> Icons.Outlined.History to "按音频文件最后修改时间排布"
                        LocalSortField.AddedAt -> Icons.Outlined.AccessTime to "按设备收录/扫描发现时间排布"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) MeloraAppearance.tintBlue else Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                onFieldChange(option)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) BrandBlue.copy(alpha = 0.14f) else MeloraAppearance.softFill),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = if (selected) BrandBlue else TextSub,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) BrandBlue else TextMain,
                            )
                            Text(
                                text = subtitle,
                                fontSize = 11.5.sp,
                                color = if (selected) BrandBlue.copy(alpha = 0.75f) else TextSub,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "已选",
                                tint = BrandBlue,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortDirectionSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) MeloraAppearance.card else Color.Transparent)
            .border(
                width = if (selected) 0.8.dp else 0.dp,
                color = if (selected) MeloraAppearance.chipBorderColor else Color.Transparent,
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) BrandBlue else TextSub,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) BrandBlue else TextSub,
            )
        }
    }
}
