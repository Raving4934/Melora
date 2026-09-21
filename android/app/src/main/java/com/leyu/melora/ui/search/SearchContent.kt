package com.leyu.melora.ui.search

import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.LocalChromeTopInset

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.common.CardWhite
import com.leyu.melora.ui.common.EmptyState
import com.leyu.melora.ui.common.ErrorState
import com.leyu.melora.ui.common.OnlineAudiobookCard
import com.leyu.melora.ui.common.OnlinePlaylistCard
import com.leyu.melora.ui.common.OnlineSongRow
import com.leyu.melora.ui.common.ShimmerBox
import com.leyu.melora.ui.common.sourceAliasDisplay
import com.leyu.melora.ui.common.ShimmerTextLine
import com.leyu.melora.ui.common.SkeletonGrid
import com.leyu.melora.ui.common.SkeletonSongList
import com.leyu.melora.ui.common.responsiveGridColumns
import com.leyu.melora.ui.common.SongArtwork
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextMuted
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.theme.MeloraAppearance


@Composable
internal fun SearchResultsContent(
    listState: LazyListState,
    category: SearchCategory,
    submitted: String,
    selectedPlatform: PlatformSource,
    loading: Boolean,
    error: String?,
    playlists: List<OnlinePlaylist>,
    songs: List<OnlineSong>,
    hasMore: Boolean,
    loadingMore: Boolean,
    favoriteUids: Set<String>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onMoreSong: (OnlineSong) -> Unit,
    onPlaySong: (OnlineSong) -> Unit,
) {
    when {
        loading -> SearchResultsSkeleton(category = category, modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
        error != null -> ErrorState(error, onRetry = { onRetry() }, modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
        category == SearchCategory.Playlist || category == SearchCategory.Audiobook -> {
            if (playlists.isEmpty()) {
                EmptyState(
                    if (category == SearchCategory.Audiobook) "没有找到「$submitted」相关有声专辑" else "没有找到「$submitted」相关歌单",
                    Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                )
            } else {
                val columns = responsiveGridColumns()
                val rows = playlists.chunked(columns)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                ) {
                    item {
                        Text(
                            text = if (category == SearchCategory.Audiobook) {
                                "有声专辑 · 来自【酷我】 · ${playlists.size} 个"
                            } else {
                                "歌单结果 · 来自【${sourceAliasDisplay(selectedPlatform.id, selectedPlatform.label)}】 · ${playlists.size} 个"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSub,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    itemsIndexed(rows) { rowIndex, rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowItems.forEach { playlist ->
                                if (category == SearchCategory.Audiobook) {
                                    OnlineAudiobookCard(
                                        playlist = playlist,
                                        onClick = { onOpenPlaylist(playlist) },
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    OnlinePlaylistCard(
                                        playlist = playlist,
                                        onClick = { onOpenPlaylist(playlist) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            repeat(columns - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                        if (rowIndex == rows.lastIndex && hasMore) {
                            LoadMoreFooter(loadingMore) { onLoadMore() }
                        }
                    }
                }
            }
        }
        else -> {
            if (songs.isEmpty()) {
                EmptyState("没有找到「$submitted」相关内容", Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                ) {
                    item {
                        Text(
                            text = "歌曲结果 · 来自【${sourceAliasDisplay(selectedPlatform.id, selectedPlatform.label)}】 · ${songs.size} 首",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSub,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    itemsIndexed(songs, key = { _, song -> song.uid }) { index, song ->
                        OnlineSongRow(
                            song = song,
                            showAlbum = true,
                            isFavorite = song.uid in favoriteUids,
                            onMore = { onMoreSong(song) },
                            onClick = { onPlaySong(song) },
                            platformDotColor = if (selectedPlatform == PlatformSource.All) {
                                PlatformSource.entries.firstOrNull { it.id == song.source }?.color
                            } else null,
                        )
                        if (index == songs.lastIndex && hasMore) {
                            LoadMoreFooter(loadingMore) { onLoadMore() }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun SearchResultsSkeleton(
    category: SearchCategory,
    modifier: Modifier = Modifier,
) {
    if (category == SearchCategory.Song) {
        Column(modifier = modifier.fillMaxWidth()) {
            SearchResultHeaderSkeleton(verticalPadding = 6.dp)
            SkeletonSongList()
        }
    } else {
        val columns = responsiveGridColumns()
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchResultHeaderSkeleton(verticalPadding = 4.dp)
            SkeletonGrid(columns = columns, cards = columns * 2, spacing = 10.dp)
        }
    }
}

@Composable
private fun SearchResultHeaderSkeleton(verticalPadding: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
            .clearAndSetSemantics {},
    ) {
        ShimmerTextLine(
            placeholder = "搜索结果占位",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            shimmerHeight = 12.dp,
            cornerRadius = 5.dp,
            modifier = Modifier.width(188.dp),
        )
    }
}

@Composable
private fun LoadMoreFooter(loading: Boolean, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading, onClick = onLoadMore)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (loading) "正在加载…" else "加载更多",
            fontSize = 13.sp,
            color = TextSub,
        )
    }
}


// 双列热搜单行条目
@Composable
internal fun HotSearchRowItem(
    rank: Int,
    title: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = when (rank) {
                1 -> Color(0xFFDC2626)
                2 -> Color(0xFFD97706)
                3 -> Color(0xFF2563EB)
                else -> TextMuted
            },
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (tag.isNotEmpty()) {
            // 正方形小徽标（固定 16×16，文字居中）
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFFFFE4E6),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(16.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = tag,
                        fontSize = 9.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE11D48),
                        // 去掉字体额外行距，让"热"字在方框里真正居中
                        style = LocalTextStyle.current.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.None,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

// 歌单分类猜你喜欢：长条横滑卡（封面 + 标题 + 作者/曲数/播放量）
@Composable
internal fun PlaylistStripCard(
    playlist: OnlinePlaylist,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = CardWhite,
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier.width(236.dp),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongArtwork(playlist.img, "${playlist.source}_${playlist.id}", Modifier.size(56.dp), cornerRadius = 12)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp,
                )
                Text(
                    text = listOfNotNull(
                        playlist.author.takeIf { it.isNotBlank() },
                        playlist.total.takeIf { it > 0 }?.let { "$it 首" },
                        playlist.playCountLabel.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { "歌单" },
                    fontSize = 11.sp,
                    color = TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

// 热搜行骨架：与 HotSearchRowItem 同构（名次位 20dp + 词条条）
@Composable
internal fun SkeletonHotRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShimmerTextLine(
            placeholder = "0",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            shimmerFraction = 0.6f,
            shimmerHeight = 11.dp,
            cornerRadius = 4.dp,
            modifier = Modifier.width(20.dp),
        )
        ShimmerTextLine(
            placeholder = "热搜词占位",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            shimmerFraction = 0.72f,
            shimmerHeight = 12.dp,
            modifier = Modifier.weight(1f),
        )
    }
}
