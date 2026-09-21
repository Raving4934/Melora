package com.leyu.melora.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.TagInfo
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.DetailPageHost
import com.leyu.melora.ui.common.CardPlayButton
import com.leyu.melora.ui.common.LocalChromeTopInset
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.CardWhite
import com.leyu.melora.ui.common.EmptyState
import com.leyu.melora.ui.common.ErrorState
import com.leyu.melora.ui.common.SkeletonCrossfade
import com.leyu.melora.ui.common.SkeletonGrid
import com.leyu.melora.ui.common.responsiveGridColumns
import com.leyu.melora.ui.common.PullRefreshContainer
import com.leyu.melora.ui.common.FastScrollToTopEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.ui.common.OnlinePlaylistCard
import com.leyu.melora.ui.common.PlaylistDetailContent
import com.leyu.melora.ui.common.LoadMoreOnScroll
import com.leyu.melora.ui.common.SongArtwork
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.common.runCatchingCancellable
import kotlinx.coroutines.launch
import com.leyu.melora.ui.theme.MeloraAppearance

private const val PAGE_CACHE_TTL = 15 * 60 * 1000L

data class PlaylistPlatform(val id: String, val label: String, val color: Color)

val playlistPlatforms = listOf(
    PlaylistPlatform("kw", "酷我", Color(0xFFFFB300)),
    PlaylistPlatform("kg", "酷狗", Color(0xFF0091EA)),
    PlaylistPlatform("wy", "网易", Color(0xFFE53935)),
    PlaylistPlatform("tx", "企鹅", Color(0xFF00C853)),
    PlaylistPlatform("mg", "咪咕", Color(0xFF8E24AA)),
)

// 歌单页：左侧最热/最新切换，右侧分类（底部弹窗）+ 平台筛选，主体双列歌单网格
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlaylistsScreen(
    modifier: Modifier = Modifier,
    scrollToTopRequest: Int = 0,
    primaryHeader: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 平台 / 分类 / 排序全部由顶栏筛选驱动（设置即状态）
    val platformId by MeloraSettings.playlistPlatform.collectAsStateWithLifecycle()
    val selectedPlatform = playlistPlatforms.firstOrNull { it.id == platformId } ?: playlistPlatforms.first()
    val selectedTagId by MeloraSettings.playlistTagId.collectAsStateWithLifecycle()
    val sortOrder by MeloraSettings.playlistSort.collectAsStateWithLifecycle()
    val pullEnabled by MeloraSettings.pullToRefresh.collectAsStateWithLifecycle()
    var openedPlaylist by remember { mutableStateOf<OnlinePlaylist?>(null) }

    // 仅酷我官方接口支持"最热/最新"；酷狗/企鹅两参数同榜、网易仅最热、咪咕仅推荐
    val sortId = if (selectedPlatform.id == "kw" && sortOrder == 1) "new" else "hot"
    val pageKey = "playlists.${selectedPlatform.id}.$sortId.$selectedTagId"
    val listState = key(pageKey) { rememberLazyListState() }
    FastScrollToTopEffect(scrollToTopRequest, listState)
    var refreshing by remember(pageKey) { mutableStateOf(false) }
    var loadingMore by remember(pageKey) { mutableStateOf(false) }
    // 秒开优先：切平台/分类先拿已有缓存渲染，过期由后台静默刷新，不再整页闪骨架
    var playlists by remember(pageKey) {
        mutableStateOf(OnlineCache.peek<List<OnlinePlaylist>>(pageKey).orEmpty())
    }
    var page by remember(pageKey) { mutableIntStateOf(1) }
    var hasMore by remember(pageKey) { mutableStateOf(playlists.size >= 30) }
    var loading by remember(pageKey) { mutableStateOf(playlists.isEmpty()) }
    var error by remember(pageKey) { mutableStateOf<String?>(null) }
    var retryKey by remember(pageKey) { mutableIntStateOf(0) }

    LaunchedEffect(pageKey, retryKey) {
        val fresh = OnlineCache.get<List<OnlinePlaylist>>(pageKey, PAGE_CACHE_TTL)
        if (fresh != null && retryKey == 0) {
            playlists = fresh
            page = 1
            hasMore = fresh.size >= 30
            loading = false
            error = null
            return@LaunchedEffect
        }
        val hasContent = playlists.isNotEmpty()
        if (!hasContent) loading = true
        error = null
        runCatchingCancellable { OnlineRepository.playlists(context, selectedPlatform.id, sortId, selectedTagId, 1) }
            .onSuccess {
                playlists = it.list
                page = 1
                hasMore = it.list.size >= 30
                if (it.list.isNotEmpty()) OnlineCache.put(pageKey, it.list)
            }
            .onFailure { if (playlists.isEmpty()) error = it.message ?: "歌单加载失败" }
        loading = false
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            runCatchingCancellable { OnlineRepository.playlists(context, selectedPlatform.id, sortId, selectedTagId, page + 1) }
                .onSuccess {
                    playlists = (playlists + it.list).distinctBy { item -> "${item.source}_${item.id}" }
                    page += 1
                    hasMore = it.list.size >= 30
                    OnlineCache.put(pageKey, playlists)
                }
            loadingMore = false
        }
    }

    DetailPageHost(
        target = openedPlaylist,
        modifier = modifier,
        contentKey = { playlist -> "${playlist.source}:${playlist.id}" },
        detail = { playlist ->
            PlaylistDetailContent(
                playlist = playlist,
                onBack = { openedPlaylist = null },
            )
        },
    ) {
        ChromeScaffold(
            modifier = Modifier.fillMaxSize(),
            expectedTopBarHeight = 64.dp,
            topBar = primaryHeader,
        ) {
            PullRefreshContainer(
                enabled = pullEnabled,
                refreshing = refreshing,
                onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    runCatchingCancellable { OnlineRepository.playlists(context, selectedPlatform.id, sortId, selectedTagId, 1) }
                        .onSuccess {
                            playlists = it.list
                            page = 1
                            hasMore = it.list.size >= 30
                            if (it.list.isNotEmpty()) OnlineCache.put(pageKey, it.list)
                            error = null
                        }
                        .onFailure { PlaybackController.postMessage(context, it.message ?: "歌单同步失败") }
                    refreshing = false
                }
            }
        },
                modifier = Modifier.fillMaxSize(),
            ) {
                        Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(8.dp))

                    val columns = responsiveGridColumns()
                    SkeletonCrossfade(
                        visible = loading,
                        modifier = Modifier.fillMaxSize(),
                        skeleton = {
                            SkeletonGrid(
                                columns = columns,
                                cards = columns * 2,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = LocalChromeTopInset.current),
                            )
                        },
                    ) {
                        when {
                            error != null -> ErrorState(
                                error!!,
                                onRetry = { retryKey++ },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = LocalChromeTopInset.current),
                            )
                            playlists.isEmpty() -> EmptyState(
                                "该分类暂无歌单",
                                Modifier
                                    .fillMaxSize()
                                    .padding(top = LocalChromeTopInset.current),
                            )
                            else -> {
                                LoadMoreOnScroll(listState, hasMore, loadingMore, onLoadMore = ::loadMore)
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                                ) {
                                    val rows = playlists.chunked(columns)
                                    items(
                                        count = rows.size,
                                        key = { rowIndex -> rows[rowIndex].joinToString("|") { "${it.source}:${it.id}" } },
                                    ) { rowIndex ->
                                        val rowItems = rows[rowIndex]
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            rowItems.forEach { playlist ->
                                                OnlinePlaylistCard(
                                                    playlist = playlist,
                                                    onClick = { openedPlaylist = playlist },
                                                    modifier = Modifier.weight(1f),
                                                )
                                            }
                                            repeat(columns - rowItems.size) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                if (hasMore) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 16.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                if (loadingMore) "正在加载…" else "上滑加载更多",
                                                fontSize = 13.sp,
                                                color = BrandBlue,
                                                modifier = Modifier.clickable(enabled = !loadingMore) { loadMore() },
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
        }
    }
}
