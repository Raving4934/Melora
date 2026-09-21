package com.leyu.melora.ui.common

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.formatPlayCountLabel
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SongPage
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import org.json.JSONObject

internal val PlaylistControlsHeight = 50.6.dp

/** 使用自然流中的占位项定位；不再读取已被 native stickyHeader 平移后的坐标。 */
internal fun shouldPinPlaylistControls(firstVisibleIndex: Int, controlBarTop: Int?, headerBottom: Int): Boolean =
    controlBarTop?.let { it <= headerBottom } ?: (firstVisibleIndex > 1)

/**
 * 通用歌单/专辑详情：头部信息 + 播放全部 + 歌曲列表。
 * 由搜索、歌单、听书、发现页共用，避免多份实现。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun PlaylistDetailContent(
    playlist: OnlinePlaylist,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    emptyHint: String = "歌单暂无可播放歌曲",
) {
    // 同 ID 跨平台不是同一详情；同时重建状态并取消旧页面的分页协程。
    key(playlist.source, playlist.id, playlist.isBookAlbum) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        val scrollToTop = rememberFastScrollToTop(listState)
        val controlBarHazeState = rememberHazeState()
        val blurEnabled by MeloraSettings.blurTopBar.collectAsStateWithLifecycle()
        val bodyHazeModifier = if (blurEnabled) {
            Modifier.hazeSource(controlBarHazeState)
        } else {
            Modifier
        }
        val isBook = playlist.isBookAlbum
        val detailCacheKey = "playlistDetail.${if (isBook) "book" else playlist.source}.${playlist.id}"
        // 页面持有完整快照，分页不再反查可能被LRU淘汰/后台刷新替换的全局缓存。
        var bookSnapshot by remember(detailCacheKey) {
            mutableStateOf(if (isBook) OnlineCache.peek<KwBookApi.BookChapters>(detailCacheKey) else null)
        }
        var songSnapshot by remember(detailCacheKey) {
            mutableStateOf(if (!isBook) OnlineCache.peek<SongPage>(detailCacheKey) else null)
        }
        val songs = bookSnapshot?.items ?: songSnapshot?.list.orEmpty()
        val totalCount = (bookSnapshot?.total ?: songSnapshot?.total)?.takeIf { it > 0 } ?: songs.size
        val hasMore = bookSnapshot?.hasMore ?: songSnapshot?.hasMore() ?: false
        var loading by remember(detailCacheKey) { mutableStateOf(songs.isEmpty()) }
        var error by remember(detailCacheKey) { mutableStateOf<String?>(null) }
        var loadingMore by remember(detailCacheKey) { mutableStateOf(false) }
        val bookIntro = bookSnapshot?.metadata?.description?.takeIf(String::isNotBlank) ?: playlist.description
        val bookHeat = bookSnapshot?.metadata?.playCount?.takeIf { it > 0 }
            ?.let { formatPlayCountLabel(it.toString()) } ?: playlist.playCountLabel
        val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()
        var moreSong by remember { mutableStateOf<OnlineSong?>(null) }
        var refreshTick by remember { mutableIntStateOf(0) }

        LaunchedEffect(refreshTick) {
            if (songs.isEmpty()) loading = true
            error = null
            runCatchingCancellable {
                if (isBook) {
                    bookSnapshot = OnlineCache.refresh(detailCacheKey, OnlineCache.CATALOG_TTL_MS) {
                        KwBookApi.album(playlist.id, 1)
                    }
                } else {
                    songSnapshot = OnlineCache.refresh(detailCacheKey, OnlineCache.CATALOG_TTL_MS) {
                        OnlineRepository.playlistSongs(context, playlist.source, playlist.id, 1).forRequestedPage(1)
                    }
                }
            }.onFailure { if (songs.isEmpty()) error = it.message ?: "歌单加载失败" }
            loading = false
        }

        fun loadMore() {
            if (loadingMore) return
            val previousBook = bookSnapshot
            val previousSongs = songSnapshot
            if (!(previousBook?.hasMore ?: previousSongs?.hasMore() ?: false)) return
            loadingMore = true
            scope.launch {
                try {
                    if (previousBook != null) {
                        val nextPage = previousBook.page + 1
                        runCatchingCancellable { KwBookApi.album(playlist.id, nextPage) }
                            .onSuccess { result ->
                                if (bookSnapshot !== previousBook) return@onSuccess
                                bookSnapshot = previousBook.append(result, nextPage).also { OnlineCache.put(detailCacheKey, it) }
                            }
                            .onFailure { PlaybackController.postMessage(context, it.message ?: "加载更多失败") }
                    } else if (previousSongs != null) {
                        val nextPage = previousSongs.page + 1
                        runCatchingCancellable { OnlineRepository.playlistSongs(context, playlist.source, playlist.id, nextPage) }
                            .onSuccess { result ->
                                if (songSnapshot !== previousSongs) return@onSuccess
                                songSnapshot = previousSongs.append(result, nextPage).also { OnlineCache.put(detailCacheKey, it) }
                            }
                            .onFailure { PlaybackController.postMessage(context, it.message ?: "加载更多失败") }
                    }
                } finally {
                    loadingMore = false
                }
            }
        }

        // 批量管理模式：收藏右侧"批量管理"进入，顶栏/列表行/底部工具条联动
        val selection = remember { SongSelectionState() }
        BackHandler { if (selection.active) selection.finish() else onBack() }
        val favoritePlaylists by UserLibrary.favoritePlaylists.collectAsStateWithLifecycle()
        val detailPlaylist = remember(playlist, bookIntro, bookHeat) {
            if (!isBook) playlist else OnlinePlaylist(JSONObject(playlist.raw.toString()).apply {
                put("description", bookIntro)
                put("play_count", bookHeat)
            })
        }
        val isFav = favoritePlaylists.any { "${it.source}_${it.id}" == "${playlist.source}_${playlist.id}" }
        val playAll: () -> Unit = {
            UserLibrary.markContainerPlayed(
                UserLibrary.PlayContainer(
                    kind = if (playlist.isBookAlbum) "book" else "playlist",
                    id = playlist.id,
                    name = playlist.name,
                    img = playlist.img,
                    source = playlist.source,
                    queueId = "playlist.${playlist.source}.${playlist.id}",
                ),
            )
            PlaybackController.playQueue(context, songs.toUiTracks(), 0, "playlist.${playlist.source}.${playlist.id}")
        }

        ChromeScaffold(
            modifier = modifier,
            contentSource = controlBarHazeState,
            expectedTopBarHeight = 64.dp,
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(chromeHeaderColor()),
                ) {
                    // 顶栏：普通态（返回 + 标题）⇄ 编辑态（全选 / 已选中 X 项 / 关闭）平滑切换
                    SongSelectionTopBar(selection, songs) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = TextMain)
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .titleScrollToTop(scrollToTop),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    playlist.name,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = { SongBatchActionsBar(selection, songs) },
        ) {
            // 必须读取本页 ChromeScaffold 提供的累计 inset；函数顶部只能看到父级 inset。
            val chromeTopInset = LocalChromeTopInset.current
            val density = LocalDensity.current
            val chromeTopInsetPx = with(density) { chromeTopInset.roundToPx() }
            val controlBarPinned by remember(listState, chromeTopInsetPx) {
                derivedStateOf {
                    !selection.active && shouldPinPlaylistControls(
                        firstVisibleIndex = listState.firstVisibleItemIndex,
                        controlBarTop = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == "controlBar" }
                            ?.let { it.offset - listState.layoutInfo.viewportStartOffset },
                        headerBottom = chromeTopInsetPx,
                    )
                }
            }
            val dividerAlpha by animateFloatAsState(
                targetValue = if (controlBarPinned && !blurEnabled) 1f else 0f,
                animationSpec = tween(durationMillis = 200),
                label = "stuckDivider",
            )
            // 同一个操作栏实现只显示在一个位置：原位或覆盖层；原位始终等高占位。
            val controlBar: @Composable (Boolean) -> Unit = { pinned ->
                ChromeFloatingBar(
                    state = controlBarHazeState,
                    topOffset = chromeTopInset,
                    height = PlaylistControlsHeight,
                    pinned = pinned,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(chromeHeaderColor()),
                    ) {
                        CollectionActionsRow(
                            canPlay = songs.isNotEmpty(),
                            favorite = isFav,
                            onPlay = playAll,
                            onFavorite = { UserLibrary.toggleFavoritePlaylist(detailPlaylist) },
                            onSelect = selection::start,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.6.dp)
                                .alpha(dividerAlpha)
                                .background(DividerSoft),
                        )
                    }
                }
            }
            when {
                loading -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = LocalChromeTopInset.current),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 头部骨架：封面 + 两行文字
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(bodyHazeModifier)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShimmerBox(Modifier.size(64.dp), cornerRadius = 12.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            ShimmerBox(Modifier.fillMaxWidth(0.42f).height(13.dp), cornerRadius = 6.dp)
                            ShimmerBox(Modifier.fillMaxWidth(0.62f).height(11.dp), cornerRadius = 5.dp)
                        }
                    }
                    // 控制栏骨架与原位占位/真实操作栏使用同一高度。
                    Column(Modifier.height(PlaylistControlsHeight)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 11.5.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ShimmerBox(Modifier.width(104.dp).height(34.dp), cornerRadius = 17.dp)
                            Spacer(Modifier.weight(1f))
                            ShimmerBox(Modifier.size(34.dp), cornerRadius = 17.dp)
                            Spacer(Modifier.width(8.dp))
                            ShimmerBox(Modifier.size(34.dp), cornerRadius = 17.dp)
                        }
                        Spacer(Modifier.height(0.6.dp))
                    }
                    SkeletonSongList(
                        modifier = Modifier
                            .weight(1f)
                            .then(bodyHazeModifier),
                        rows = 7,
                        rowSpacing = 2.dp,
                    )
                }
                error != null -> ErrorState(
                    error!!,
                    onRetry = { refreshTick++ },
                    modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                )
                songs.isEmpty() -> EmptyState(
                    emptyHint,
                    Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                )
                else -> {
                    LoadMoreOnScroll(listState, hasMore, loadingMore, onLoadMore = ::loadMore)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (!selection.active) {
                            item(key = "detailHeader") {
                                val header = if (isBook) {
                                    bookHeaderCopy(bookIntro, playlist.author, bookHeat)
                                } else {
                                    CollectionHeaderCopy(
                                        primary = playlist.author.ifBlank { "乐屿精选" },
                                        secondary = "${maxOf(totalCount, songs.size)} 首歌曲 · 播放量 ${playlist.playCountLabel.ifBlank { "—" }}",
                                        primaryMaxLines = 1,
                                    )
                                }
                                CollectionInfoHeader(
                                    image = playlist.img,
                                    seed = playlist.id,
                                    primary = header.primary,
                                    secondary = header.secondary,
                                    primaryMaxLines = header.primaryMaxLines,
                                    modifier = bodyHazeModifier,
                                )
                            }
                            item(key = "controlBar", contentType = "controls") {
                                Box(Modifier.fillMaxWidth().height(PlaylistControlsHeight)) {
                                    if (!controlBarPinned) controlBar(false)
                                }
                            }
                        }
                        itemsIndexed(songs, key = { _, song -> song.uid }) { _, song ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(bodyHazeModifier),
                            ) {
                                OnlineSongRow(
                                    song = song,
                                    isFavorite = song.uid in favoriteUids,
                                    onMore = { moreSong = song },
                                    selectionMode = selection.active,
                                    selected = song.uid in selection.selectedUids,
                                    onClick = {
                                        if (selection.active) {
                                            selection.toggle(song.uid)
                                        } else {
                                            UserLibrary.markContainerPlayed(
                                                UserLibrary.PlayContainer(
                                                    kind = if (playlist.isBookAlbum) "book" else "playlist",
                                                    id = playlist.id,
                                                    name = playlist.name,
                                                    img = playlist.img,
                                                    source = playlist.source,
                                                    queueId = "playlist.${playlist.source}.${playlist.id}",
                                                ),
                                            )
                                            PlaybackController.playTrack(context, UiTrack.fromOnline(song))
                                        }
                                    },
                                )
                            }
                        }
                        if (hasMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(bodyHazeModifier)
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (loadingMore) {
                                        Text("正在加载…", fontSize = 13.sp, color = TextMuted)
                                    } else {
                                        Text(
                                            text = "上滑加载更多",
                                            fontSize = 13.sp,
                                            color = BrandBlue,
                                            modifier = Modifier
                                                .clickable { loadMore() }
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (controlBarPinned && !selection.active) {
                        Box(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = chromeTopInset)) {
                            controlBar(true)
                        }
                    }
                }
            }
        }

        moreSong?.let { song ->
            SongMoreSheet(song) { moreSong = null }
        }
    }
}
