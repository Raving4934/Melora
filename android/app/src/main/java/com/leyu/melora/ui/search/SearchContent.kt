package com.leyu.melora.ui.search

import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.LocalChromeTopInset

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.leyu.melora.ui.common.LoadMoreOnScroll
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
    results: SearchResults,
    loading: Boolean,
    error: String?,
    favoriteUids: Set<String>,
    onLoadPage: () -> Unit,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onMoreSong: (OnlineSong) -> Unit,
    onPlaySong: (OnlineSong) -> Unit,
) {
    val viewport = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current)
    if (results.isEmpty && (!results.hasMore || error != null)) {
        when {
            error != null -> ErrorState(error, modifier = viewport, onRetry = onLoadPage)
            loading -> BoxWithConstraints(viewport) {
                SearchResultsSkeleton(category, maxWidth, Modifier.fillMaxSize())
            }
            else -> {
                val emptyMessage = when (category) {
                    SearchCategory.Song -> "没有找到「$submitted」相关内容"
                    SearchCategory.Playlist -> "没有找到「$submitted」相关歌单"
                    SearchCategory.Audiobook -> "没有找到「$submitted」相关有声专辑"
                }
                EmptyState(emptyMessage, viewport)
            }
        }
        return
    }

    val songResults = category == SearchCategory.Song
    val source = sourceAliasDisplay(selectedPlatform.id, selectedPlatform.label)
    val footerKey = if (songResults) results.songs.size else results.playlists.size
    LoadMoreOnScroll(
        listState, enabled = results.hasMore && error == null, loading = loading,
        fillViewportFooterKey = footerKey, onLoadMore = onLoadPage,
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (songResults) 1 else responsiveGridColumns(maxWidth, horizontalSpacing = 10.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = if (songResults) Arrangement.Top else Arrangement.spacedBy(10.dp),
            contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
        ) {
            if (!results.isEmpty) item("count") {
                Text(
                    text = when (category) {
                        SearchCategory.Song -> "歌曲结果 · 来自【$source】 · ${results.songs.size} 首"
                        SearchCategory.Playlist -> "歌单结果 · 来自【$source】 · ${results.playlists.size} 个"
                        SearchCategory.Audiobook -> "有声专辑 · 来自【酷我】 · ${results.playlists.size} 个"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSub,
                    modifier = Modifier.padding(vertical = if (songResults) 6.dp else 4.dp),
                )
            }
            if (songResults) {
                items(results.songs, key = OnlineSong::uid) { song ->
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
                }
            } else {
                items(results.playlists.chunked(columns), key = { row ->
                    val first = row.first()
                    "playlist:${first.source}:${first.id}"
                }) { row ->
                    SearchPlaylistRow(row, columns, category, onOpenPlaylist)
                }
            }
            if (results.hasMore || loading || error != null) {
                item(footerKey) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .then(if (error != null) Modifier.clickable(onClick = onLoadPage) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        val status = when {
                            error != null -> "加载失败，点击重试"
                            loading -> "正在加载…"
                            results.isEmpty -> "当前页暂无匹配内容，上滑继续查找"
                            else -> null
                        }
                        if (status != null) Text(status, fontSize = 13.sp, color = TextSub)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchPlaylistRow(
    playlists: List<OnlinePlaylist>,
    columns: Int,
    category: SearchCategory,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        playlists.forEach { playlist ->
            if (category == SearchCategory.Audiobook) {
                OnlineAudiobookCard(playlist, { onOpenPlaylist(playlist) }, Modifier.weight(1f))
            } else {
                OnlinePlaylistCard(playlist, { onOpenPlaylist(playlist) }, Modifier.weight(1f))
            }
        }
        repeat(columns - playlists.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun SearchResultsSkeleton(
    category: SearchCategory,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    if (category == SearchCategory.Song) {
        Column(modifier = modifier.fillMaxWidth()) {
            SearchResultHeaderSkeleton(verticalPadding = 6.dp)
            SkeletonSongList()
        }
    } else {
        val columns = responsiveGridColumns(availableWidth, horizontalSpacing = 10.dp)
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
            modifier = Modifier.weight(1f, fill = false),
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

internal val PlaylistStripMinHeight = 74.dp

// 热门歌单横滑卡
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
        modifier = Modifier
            .width(236.dp)
            .heightIn(min = PlaylistStripMinHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
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
                        playlist.total.takeIf { it > 0 }?.let { "$it 首" },
                        playlist.playCountLabel.takeIf { it.isNotBlank() },
                        playlist.author.takeIf { it.isNotBlank() },
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
