package com.leyu.melora.ui.local

import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.MeloraSheetHandle
import com.leyu.melora.ui.common.rememberMeloraBottomSheetState
import com.leyu.melora.ui.common.rememberSheetDismiss
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack


import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.local.LocalSongsContent
import com.leyu.melora.playback.local.LocalSortField
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.SongSelectionState
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextMuted
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.chromeHeaderColor
import com.leyu.melora.ui.theme.DarkGlass
import com.leyu.melora.ui.theme.LightSheetFrost
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.theme.currentMeloraSheetPalette

/** 本地搜索页：搜索框 + 结果列表；排序与列表页共用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalSearchPage(
    content: LocalSongsContent?,
    queryState: TextFieldState,
    onCancel: () -> Unit,
    selection: SongSelectionState,
    onOpenSortSheet: () -> Unit,
    onMore: (LocalSong) -> Unit,
    onDeleteSelection: (List<LocalSong>) -> Unit,
    onAddToPlaylist: (List<OnlineSong>) -> Unit,
) {
    val songs = content?.songs.orEmpty()
    val context = LocalContext.current
    val playingLocalId = rememberPlayingLocalId()
    val listState = rememberLazyListState()
    val query = queryState.text.toString()
    val filtered = remember(songs, query) { if (query.isBlank()) songs else songs.filter { it.matches(query) } }

    val hasActions = query.isNotBlank() && filtered.isNotEmpty()
    ChromeScaffold(
        expectedTopBarHeight = if (hasActions) 110.dp else 64.dp,
        hasSecondaryRow = hasActions,
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
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = TextSub, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        val inputStyle = LocalTextStyle.current.copy(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = TextMain,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        )
                        BasicTextField(
                            state = queryState,
                            lineLimits = TextFieldLineLimits.SingleLine,
                            textStyle = inputStyle,
                            cursorBrush = SolidColor(BrandBlue),
                            modifier = Modifier.weight(1f),
                            decorator = { field ->
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty()) {
                                        Text(
                                            if (content == null) "搜索本地歌曲" else "在 ${songs.size} 首歌曲中搜索",
                                            style = inputStyle.copy(color = TextSub),
                                            maxLines = 1,
                                        )
                                    }
                                    field()
                                }
                            },
                        )
                        if (query.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(MeloraAppearance.chipBorderColor)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { queryState.clearText() },
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
                if (hasActions) {
                    LocalListHeader(
                        count = filtered.size,
                        songs = filtered,
                        selection = selection,
                        onPlayShuffle = {
                            PlaybackController.playQueue(
                                context,
                                filtered.shuffled().map { it.toOnlineSong() }.toUiTracks(),
                                0,
                                "local.search",
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
            content == null -> Box(Modifier.fillMaxSize())
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



private val LightSortSecondaryText = Color(0xFF334155)

/** 本地歌曲排序抽屉。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalSortSheet(
    field: LocalSortField,
    ascending: Boolean,
    onFieldChange: (LocalSortField) -> Unit,
    onDirectionChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberMeloraBottomSheetState(skipPartiallyExpanded = true)
    val closeSheet = rememberSheetDismiss(sheetState)
    val palette = currentMeloraSheetPalette()
    val eInk = palette.isEInk
    val dark = !eInk && palette.isDark
    val lightSelectedText = MaterialTheme.colorScheme.onPrimaryContainer
    MeloraBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { MeloraSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
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
                    color = palette.textMain,
                )
                Surface(
                    shape = CircleShape,
                    color = when {
                        eInk -> Color(0xFFF2F2F2)
                        dark -> DarkGlass.chip
                        else -> LightSheetFrost.chip
                    },
                    border = when {
                        eInk -> BorderStroke(1.dp, Color.Black)
                        dark -> BorderStroke(0.5.dp, DarkGlass.border)
                        else -> LightSheetFrost.chipBorder
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(3.dp),
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
            }

            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LocalSortField.entries.forEach { option ->
                    val selected = option == field
                    val (icon, subtitle) = when (option) {
                        LocalSortField.FileName -> Icons.Outlined.TextFields to "按名称字母与拼音顺序排布"
                        LocalSortField.Artist -> Icons.Outlined.Person to "按歌手/艺术家名称排布"
                        LocalSortField.Year -> Icons.Outlined.CalendarToday to "按音频标签记录的发行年份排布"
                        LocalSortField.Size -> Icons.Outlined.Storage to "按文件占用磁盘大小排布"
                        LocalSortField.ModifiedAt -> Icons.Outlined.History to "按音频文件最后修改时间排布"
                        LocalSortField.AddedAt -> Icons.Outlined.AccessTime to "按设备收录/扫描发现时间排布"
                    }
                    Surface(
                        onClick = {
                            closeSheet {
                                onFieldChange(option)
                                onDismiss()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = when {
                            !selected -> Color.Transparent
                            eInk -> Color(0xFFE8E8E8)
                            dark -> DarkGlass.selectedFill
                            else -> LightSheetFrost.activeCard
                        },
                        border = if (eInk && selected) BorderStroke(1.dp, Color.Black) else null,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { this.selected = selected },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        when {
                                            eInk -> if (selected) Color.Black else Color(0xFFF2F2F2)
                                            dark -> if (selected) DarkGlass.selectedEdge.copy(alpha = 0.22f) else DarkGlass.icon
                                            selected -> LightSheetFrost.blue.copy(alpha = 0.14f)
                                            else -> LightSheetFrost.chip
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = when {
                                        eInk -> if (selected) Color.White else Color.Black
                                        dark -> if (selected) DarkGlass.selectedText else palette.textMain
                                        selected -> LightSheetFrost.blue
                                        else -> palette.textSub
                                    },
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.label,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = when {
                                        eInk -> Color.Black
                                        dark && selected -> DarkGlass.selectedText
                                        selected -> lightSelectedText
                                        else -> palette.textMain
                                    },
                                )
                                Text(
                                    text = subtitle,
                                    fontSize = 11.5.sp,
                                    color = when {
                                        eInk -> Color(0xFF444444)
                                        dark -> if (selected) DarkGlass.selectedText.copy(alpha = 0.82f) else palette.textSub
                                        selected -> lightSelectedText
                                        else -> LightSortSecondaryText
                                    },
                                    modifier = Modifier.padding(top = 1.dp),
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = "已选",
                                    tint = when {
                                        eInk -> Color.Black
                                        dark -> DarkGlass.selectedText
                                        else -> LightSheetFrost.blue
                                    },
                                    modifier = Modifier.size(18.dp),
                                )
                            }
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
    val palette = currentMeloraSheetPalette()
    val eInk = palette.isEInk
    val dark = !eInk && palette.isDark
    Surface(
        shape = CircleShape,
        color = when {
            !selected -> Color.Transparent
            eInk -> Color.Black
            dark -> DarkGlass.selectedFill
            else -> Color.White.copy(alpha = 0.92f)
        },
        border = when {
            !selected || eInk -> null
            dark -> BorderStroke(0.5.dp, DarkGlass.selectedEdge)
            else -> LightSheetFrost.cardBorder
        },
        shadowElevation = 0.dp,
        modifier = Modifier
            .clip(CircleShape)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        val tint = when {
            !selected -> if (eInk || dark) palette.textSub else LightSortSecondaryText
            eInk -> Color.White
            dark -> DarkGlass.selectedText
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        }
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = tint,
            )
        }
    }
}
