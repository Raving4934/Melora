package com.leyu.melora.ui.search

import com.leyu.melora.ui.common.chromeContentPadding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.CardWhite
import com.leyu.melora.ui.common.DividerSoft
import com.leyu.melora.ui.common.OnlineAudiobookCard
import com.leyu.melora.ui.common.OnlineSongRow
import com.leyu.melora.ui.common.ShimmerBox
import com.leyu.melora.ui.common.SkeletonCrossfade
import com.leyu.melora.ui.common.SkeletonGrid
import com.leyu.melora.ui.common.SkeletonSongList
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextMuted
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.common.sourceAliasDisplay
import com.leyu.melora.ui.theme.MeloraAppearance


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchIdleContent(
    category: SearchCategory,
    selectedPlatform: PlatformSource,
    searchHistory: List<String>,
    hotWords: List<String>,
    hotLoading: Boolean,
    hotPlaylists: List<OnlinePlaylist>,
    hotPlaylistsLoading: Boolean,
    recommendSongs: List<OnlineSong>,
    recommendLoading: Boolean,
    recommendPlaylists: List<OnlinePlaylist>,
    recommendPlaylistsLoading: Boolean,
    recommendBooks: List<OnlinePlaylist>,
    recommendBooksLoading: Boolean,
    favoriteUids: Set<String>,
    onSubmit: (String) -> Unit,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onMoreSong: (OnlineSong) -> Unit,
    onPlaySong: (OnlineSong) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
    ) {
        if (searchHistory.isNotEmpty()) {
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.History,
                                contentDescription = null,
                                tint = TextSub,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "搜索历史",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextMain,
                            )
                        }
                        Text(
                            text = "清空",
                            fontSize = 12.sp,
                            color = TextSub,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { UserLibrary.clearSearchHistory() }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        searchHistory.forEach { tag ->
                            Surface(
                                onClick = { onSubmit(tag) },
                                shape = RoundedCornerShape(14.dp),
                                color = CardWhite,
                                shadowElevation = 0.dp,
                                border = MeloraAppearance.chipBorder,
                            ) {
                                Text(
                                    text = tag,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextMain,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (hotWords.isNotEmpty() || hotLoading ||
            (category == SearchCategory.Playlist && (hotPlaylists.isNotEmpty() || hotPlaylistsLoading))
        ) {
            item {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (category == SearchCategory.Audiobook) Icons.Outlined.Search else Icons.Outlined.Whatshot,
                            contentDescription = null,
                            tint = if (category == SearchCategory.Audiobook) BrandBlue else Color(0xFFDC2626),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when (category) {
                                SearchCategory.Song -> if (selectedPlatform == PlatformSource.All) {
                                    "多平台实时热搜"
                                } else {
                                    "${sourceAliasDisplay(selectedPlatform.id, selectedPlatform.label)}实时热搜"
                                }
                                SearchCategory.Playlist -> "热门歌单探索"
                                SearchCategory.Audiobook -> "听书分类导航"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMain,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when (category) {
                                SearchCategory.Playlist -> "精选 6"
                                SearchCategory.Audiobook -> "分类"
                                SearchCategory.Song -> if (selectedPlatform == PlatformSource.All) "聚合 6" else "TOP 6"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                        )
                    }

                    if (category == SearchCategory.Playlist) {
                        // 真实热门歌单（平台推荐位直出），横滑长条卡与"猜你喜欢"同语言
                        when {
                            hotPlaylists.isEmpty() && hotPlaylistsLoading -> {
                                PlaylistStripSkeletonRow()
                            }
                            hotPlaylists.isEmpty() -> {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = CardWhite,
                                    shadowElevation = 0.dp,
                                    border = MeloraAppearance.cardBorder,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "平台热门歌单暂时不可用",
                                        fontSize = 12.sp,
                                        color = TextMuted,
                                        modifier = Modifier.padding(14.dp),
                                    )
                                }
                            }
                            else -> {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(hotPlaylists.take(6), key = { "${it.source}_${it.id}" }) { playlist ->
                                        PlaylistStripCard(
                                            playlist = playlist,
                                            onClick = { onOpenPlaylist(playlist) },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = CardWhite,
                        shadowElevation = 0.dp,
                        border = MeloraAppearance.cardBorder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SkeletonCrossfade(
                            visible = hotWords.isEmpty() && hotLoading,
                            skeleton = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        repeat(3) { SkeletonHotRow() }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        repeat(3) { SkeletonHotRow() }
                                    }
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                            ) {
                            Column(modifier = Modifier.weight(1f)) {
                                hotWords.take(3).forEachIndexed { index, word ->
                                    HotSearchRowItem(
                                        rank = index + 1,
                                        title = word,
                                        tag = if (index == 0) "热" else "",
                                        onClick = { onSubmit(word) },
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                hotWords.drop(3).take(3).forEachIndexed { index, word ->
                                    HotSearchRowItem(
                                        rank = index + 4,
                                        title = word,
                                        tag = "",
                                        onClick = { onSubmit(word) },
                                    )
                                }
                            }
                        }
                        }
                    }
                    }
                }
            }
        }

        // 猜你喜欢：歌曲分类推歌、歌单分类推长条歌单、听书分类推有声专辑
        when (category) {
            SearchCategory.Song -> if (recommendSongs.isNotEmpty() || recommendLoading) {
                item {
                    Column {
                        Text(
                            text = "猜你喜欢",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMain,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = CardWhite,
                            shadowElevation = 0.dp,
                            border = MeloraAppearance.cardBorder,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SkeletonCrossfade(
                                visible = recommendSongs.isEmpty() && recommendLoading,
                                skeleton = {
                                    SkeletonSongList(rows = 6, showMore = true, showDividers = true)
                                },
                            ) {
                            Column {
                                recommendSongs.forEachIndexed { index, song ->
                                    OnlineSongRow(
                                        song = song,
                                        onClick = {
                                            onPlaySong(song)
                                        },
                                        onMore = { onMoreSong(song) },
                                        isFavorite = song.uid in favoriteUids,
                                    )
                                    if (index < recommendSongs.lastIndex) {
                                        HorizontalDivider(
                                            color = DividerSoft,
                                            thickness = 0.6.dp,
                                            modifier = Modifier.padding(horizontal = 14.dp),
                                        )
                                    }
                                }
                            }
                            }
                        }
                    }
                }
            }

            SearchCategory.Playlist -> if (recommendPlaylists.isNotEmpty() || recommendPlaylistsLoading) {
                item {
                    Column {
                        Text(
                            text = "猜你喜欢",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMain,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        SkeletonCrossfade(
                            visible = recommendPlaylists.isEmpty() && recommendPlaylistsLoading,
                            skeleton = { PlaylistStripSkeletonRow() },
                        ) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(recommendPlaylists, key = { "${it.source}_${it.id}" }) { playlist ->
                                    PlaylistStripCard(
                                        playlist = playlist,
                                        onClick = { onOpenPlaylist(playlist) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SearchCategory.Audiobook -> if (recommendBooks.isNotEmpty() || recommendBooksLoading) {
                item {
                    Column {
                        Text(
                            text = "猜你喜欢",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextMain,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        val columns = com.leyu.melora.ui.common.responsiveGridColumns()
                        SkeletonCrossfade(
                            visible = recommendBooks.isEmpty() && recommendBooksLoading,
                            skeleton = { SkeletonGrid(columns = columns, cards = columns * 2, spacing = 10.dp, modifier = Modifier.fillMaxWidth()) },
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                recommendBooks.chunked(columns).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        rowItems.forEach { book ->
                                            OnlineAudiobookCard(
                                                playlist = book,
                                                onClick = { onOpenPlaylist(book) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistStripSkeletonRow() {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(6) {
            ShimmerBox(
                modifier = Modifier
                    .width(236.dp)
                    .height(72.dp),
                cornerRadius = 16.dp,
            )
        }
    }
}
