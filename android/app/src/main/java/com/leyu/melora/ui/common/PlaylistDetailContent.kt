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
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.MyLocation
import com.leyu.melora.ui.theme.MeloraAppearance
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.canonicalBookId
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    loadBookPage: suspend (String, Int) -> KwBookApi.BookChapters = KwBookApi::album,
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
        var activeBookPage by rememberSaveable(detailCacheKey) { mutableIntStateOf(1) }
        var bookDescending by rememberSaveable(detailCacheKey) { mutableStateOf(false) }
        var bookPickerOpen by remember { mutableStateOf(false) }
        var pendingBookOrdinal by remember { mutableStateOf<Int?>(null) }
        var pendingBookUid by remember { mutableStateOf<String?>(null) }
        var bookPageLoading by remember { mutableStateOf(false) }
        var bookRequestJob by remember(detailCacheKey) { mutableStateOf<Job?>(null) }
        // 页窗口单独缓存；detailCacheKey 仍只存第 1 页，供播放队列按原契约续页。
        val initialBookWindow = remember(detailCacheKey) {
            if (!isBook) null else OnlineCache.peek<KwBookApi.BookChapters>(
                bookCatalogPageCacheKey(detailCacheKey, activeBookPage),
            ) ?: if (activeBookPage == 1) {
                OnlineCache.peek<KwBookApi.BookChapters>(detailCacheKey)?.takeIf { it.page == 1 }
            } else null
        }
        var bookWindow by remember(detailCacheKey) {
            mutableStateOf(BookCatalogWindowState(initialBookWindow?.page ?: activeBookPage, initialBookWindow))
        }
        val bookSnapshot = bookWindow.chapters
        var songSnapshot by remember(detailCacheKey) {
            mutableStateOf(if (!isBook) OnlineCache.peek<SongPage>(detailCacheKey) else null)
        }
        val songs = bookSnapshot?.items ?: songSnapshot?.list.orEmpty()
        val displaySongs = if (isBook && bookDescending) songs.asReversed() else songs
        val bookTotal = bookSnapshot?.total?.takeIf { isBook && it > 0 }
        val totalCount = if (isBook) bookTotal ?: songs.size
            else songSnapshot?.total?.takeIf { it > 0 } ?: songs.size
        val activePage = bookSnapshot?.page ?: activeBookPage
        val hasMore = songSnapshot?.hasMore() ?: false
        var loading by remember(detailCacheKey) { mutableStateOf(songs.isEmpty()) }
        var error by remember(detailCacheKey) { mutableStateOf<String?>(null) }
        var loadingMore by remember(detailCacheKey) { mutableStateOf(false) }
        val controlBarHeight = PlaylistControlsHeight
        val bookScrollInsetPx = with(LocalDensity.current) { (controlBarHeight + 8.dp).roundToPx() }
        val bookIntro = bookSnapshot?.metadata?.description?.takeIf(String::isNotBlank) ?: playlist.description
        val bookHeat = bookSnapshot?.metadata?.playCount?.takeIf { it > 0 }
            ?.let { formatPlayCountLabel(it.toString()) } ?: playlist.playCountLabel
        val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()
        val initialTrack = remember { PlaybackController.state.value.current }
        val currentTrack by remember {
            PlaybackController.state.map { it.current }
                .distinctUntilChanged { old, new ->
                    old?.uid == new?.uid && old?.source == new?.source &&
                        old?.raw?.optString("albumId") == new?.raw?.optString("albumId")
                }
        }.collectAsStateWithLifecycle(initialValue = initialTrack)
        val currentBookSong = remember(
            currentTrack?.uid,
            currentTrack?.source,
            currentTrack?.raw?.optString("albumId"),
            playlist.source,
            playlist.id,
        ) {
            OnlineSong.from(currentTrack?.raw)?.takeIf { song ->
                val currentAlbumId = canonicalBookId(song.source, song.albumId)
                val detailAlbumId = canonicalBookId(playlist.source, playlist.id)
                song.isBookChapter && song.source == playlist.source &&
                    currentAlbumId != null && currentAlbumId == detailAlbumId
            }
        }
        var moreSong by remember { mutableStateOf<OnlineSong?>(null) }

        fun applyBookResponse(
            generation: Long,
            requestedPage: Int,
            response: KwBookApi.BookChapters,
            targetOrdinal: Int?,
            targetUid: String?,
            preserveScroll: Boolean,
            directionOnAccept: Boolean?,
        ) {
            if (!bookWindow.isCurrentRequest(generation)) return
            if (response.items.isEmpty()) {
                if (bookSnapshot?.items.isNullOrEmpty()) error = "听书目录加载失败，请重试"
                else PlaybackController.postMessage(context, "目录加载失败，已保留当前章节")
                return
            }
            val previousMetadata = bookSnapshot?.metadata
                ?: OnlineCache.peek<KwBookApi.BookChapters>(detailCacheKey)?.metadata
            val advertisedTotal = playlist.total.takeIf { it > 0 }
            val metadata = response.metadata.copy(
                description = response.metadata.description.ifBlank { previousMetadata?.description.orEmpty() },
                playCount = response.metadata.playCount.takeIf { it > 0 } ?: previousMetadata?.playCount ?: 0,
                total = response.metadata.total.takeIf { it > 0 }
                    ?: previousMetadata?.total?.takeIf { it > 0 }
                    ?: advertisedTotal ?: 0,
                author = response.metadata.author.ifBlank { previousMetadata?.author.orEmpty() },
                artwork = response.metadata.artwork ?: previousMetadata?.artwork,
                releaseDate = response.metadata.releaseDate.ifBlank { previousMetadata?.releaseDate.orEmpty() },
                language = response.metadata.language.ifBlank { previousMetadata?.language.orEmpty() },
            )
            val enriched = response.copy(page = requestedPage, metadata = metadata)
            val nextWindow = bookWindow.accept(generation, requestedPage, enriched)
            if (nextWindow === bookWindow) return
            bookWindow = nextWindow
            val accepted = checkNotNull(nextWindow.chapters)
            activeBookPage = requestedPage
            directionOnAccept?.let { bookDescending = it }
            OnlineCache.put(bookCatalogPageCacheKey(detailCacheKey, requestedPage), accepted)
            if (requestedPage == 1) OnlineCache.put(detailCacheKey, accepted)
            error = null
            if (!preserveScroll) {
                pendingBookOrdinal = targetOrdinal
                pendingBookUid = targetUid
                if (targetOrdinal == null && targetUid == null) {
                    // 排序只替换目录。页头还可见时保留原位置；已吸顶时保持操作栏原位，
                    // 从新窗口首章开始。与数据一起交给下一次布局，避免先跳页再纠正的闪动。
                    val index = listState.firstVisibleItemIndex
                    listState.requestScrollToItem(
                        index = index.coerceAtMost(1),
                        scrollOffset = if (index <= 1) listState.firstVisibleItemScrollOffset else 0,
                    )
                }
            }
        }

        fun requestBookPage(
            requestedPage: Int,
            targetOrdinal: Int? = null,
            targetUid: String? = null,
            preserveScroll: Boolean = false,
            directionOnAccept: Boolean? = null,
        ) {
            if (!isBook) return
            val (requestState, generation) = bookWindow.beginRequest()
            bookWindow = requestState
            bookRequestJob?.cancel()
            bookPageLoading = true
            loading = bookSnapshot?.items.isNullOrEmpty()
            error = null
            bookRequestJob = scope.launch {
                var cachedWindowShown = false
                try {
                    val windowKey = bookCatalogPageCacheKey(detailCacheKey, requestedPage)
                    val cached = OnlineCache.peek<KwBookApi.BookChapters>(windowKey)
                        ?.takeIf { it.page == requestedPage && it.items.isNotEmpty() }
                        ?: if (requestedPage == 1) OnlineCache.peek<KwBookApi.BookChapters>(detailCacheKey)
                            ?.takeIf { it.page == 1 && it.items.isNotEmpty() } else null
                    if (cached != null) {
                        applyBookResponse(
                            generation,
                            requestedPage,
                            cached,
                            targetOrdinal,
                            targetUid,
                            preserveScroll,
                            directionOnAccept,
                        )
                        cachedWindowShown = bookWindow.page == requestedPage && bookSnapshot?.items?.isNotEmpty() == true
                    }
                    val fresh = loadBookPage(playlist.id, requestedPage)
                    if (!bookWindow.isCurrentRequest(generation)) return@launch
                    applyBookResponse(
                        generation,
                        requestedPage,
                        fresh,
                        targetOrdinal,
                        targetUid,
                        preserveScroll = preserveScroll || cachedWindowShown,
                        directionOnAccept = directionOnAccept,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (bookWindow.isCurrentRequest(generation)) {
                        if (bookSnapshot?.items.isNullOrEmpty()) error = failure.message ?: "听书目录加载失败"
                        else PlaybackController.postMessage(context, failure.message ?: "目录加载失败，已保留当前章节")
                    }
                } finally {
                    if (bookWindow.isCurrentRequest(generation)) {
                        loading = false
                        bookPageLoading = false
                        bookRequestJob = null
                    }
                }
            }
        }

        suspend fun loadFirstPage() {
            try {
                runCatchingCancellable {
                    val previous = songSnapshot
                    val fresh = OnlineCache.refresh(detailCacheKey, OnlineCache.CATALOG_TTL_MS) {
                        OnlineRepository.playlistSongs(context, playlist.source, playlist.id, 1).forRequestedPage(1)
                    }
                    if (previous?.list?.isNotEmpty() == true && fresh.list.isEmpty()) {
                        // 空响应不是有效的重载结果：保留正在展示的歌曲，避免内容闪空。
                        OnlineCache.put(detailCacheKey, previous)
                    } else {
                        songSnapshot = fresh
                    }
                }.onSuccess {
                    error = null
                }.onFailure {
                    if (songs.isEmpty()) error = it.message ?: "歌单加载失败"
                }
            } finally {
                loading = false
            }
        }

        LaunchedEffect(detailCacheKey) {
            if (isBook) requestBookPage(activeBookPage, preserveScroll = true) else loadFirstPage()
        }

        fun loadMore() {
            if (loadingMore) return
            val previousSongs = songSnapshot ?: return
            if (!previousSongs.hasMore()) return
            loadingMore = true
            scope.launch {
                try {
                    val nextPage = previousSongs.page + 1
                    runCatchingCancellable {
                        OnlineRepository.playlistSongs(context, playlist.source, playlist.id, nextPage)
                    }.onSuccess { result ->
                        if (songSnapshot !== previousSongs) return@onSuccess
                        songSnapshot = previousSongs.append(result, nextPage).also { OnlineCache.put(detailCacheKey, it) }
                    }.onFailure { PlaybackController.postMessage(context, it.message ?: "加载更多失败") }
                } finally {
                    loadingMore = false
                }
            }
        }

        // 批量管理模式：收藏右侧"批量管理"进入，顶栏/列表行/底部工具条联动
        val selection = remember { SongSelectionState() }
        LaunchedEffect(
            bookSnapshot?.page,
            pendingBookOrdinal,
            pendingBookUid,
            selection.active,
            bookDescending,
        ) {
            if (!isBook || (pendingBookOrdinal == null && pendingBookUid == null)) {
                return@LaunchedEffect
            }
            val window = bookSnapshot ?: return@LaunchedEffect
            fun ordinalIndex(ordinal: Int): Int? {
                val exact = window.items.indexOfFirst { it.raw.optInt("bookOrdinal") == ordinal }
                if (exact >= 0) return exact
                // 旧缓存完全没有 bookOrdinal 时，沿用 100 条页内偏移；新缓存缺项则明确报未找到。
                if (window.items.none { it.raw.optInt("bookOrdinal") > 0 }) {
                    val offset = ordinal - BookCatalogPaging.rangeStart(window.page)
                    return offset.takeIf { it in window.items.indices }
                }
                return null
            }
            val itemIndex = when {
                pendingBookUid != null -> window.items.indexOfFirst { it.uid == pendingBookUid }
                    .takeIf { it >= 0 }
                    ?: pendingBookOrdinal?.let(::ordinalIndex)
                pendingBookOrdinal != null -> ordinalIndex(pendingBookOrdinal!!)
                else -> 0
            }
            if (itemIndex == null || itemIndex !in window.items.indices) {
                val target = pendingBookOrdinal?.let { "第 $it 章" } ?: "当前章节"
                PlaybackController.postMessage(context, "目录中未找到$target")
                pendingBookOrdinal = null
                pendingBookUid = null
                return@LaunchedEffect
            }
            val shownIndex = if (bookDescending) window.items.lastIndex - itemIndex else itemIndex
            val headerItems = if (selection.active) 0 else 2
            // LazyColumn的顶部padding只包含标题栏；必须额外让开覆盖式吸顶操作栏。
            listState.scrollToItem((shownIndex + headerItems).coerceAtLeast(0),
                scrollOffset = if (selection.active) 0 else -bookScrollInsetPx)
            pendingBookOrdinal = null
            pendingBookUid = null
        }

        fun locateCurrentBookChapter() {
            val chapter = currentBookSong ?: return
            val currentIndex = songs.indexOfFirst { it.uid == chapter.uid }
            if (currentIndex >= 0) {
                pendingBookOrdinal = chapter.raw.optInt("bookOrdinal").takeIf { it > 0 }
                pendingBookUid = chapter.uid
                return
            }
            val ordinal = chapter.raw.optInt("bookOrdinal").takeIf { it > 0 }
            val page = ordinal?.let { BookCatalogPaging.pageForOrdinal(it) }
                ?: chapter.raw.optInt("bookPage").takeIf { it > 0 }
            if (page == null) {
                PlaybackController.postMessage(context, "无法定位这条旧章节记录")
                return
            }
            requestBookPage(page, targetOrdinal = ordinal, targetUid = chapter.uid)
        }

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
            if (isBook) {
                // 目录窗口可能在第1000章附近，播放全部仍从本书第一页开始，不能拿当前窗口冒充整本。
                PlaybackController.playBook(context, detailPlaylist)
            } else {
                UserLibrary.markContainerPlayed(UserLibrary.PlayContainer(
                    kind = "playlist", id = playlist.id, name = playlist.name, img = playlist.img,
                    source = playlist.source, queueId = "playlist.${playlist.source}.${playlist.id}",
                ))
                PlaybackController.playQueue(context, songs.toUiTracks(), 0, "playlist.${playlist.source}.${playlist.id}")
            }
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
            // 初次加载与真实内容使用同一行布局，避免维护另一套失配的操作栏骨架。
            val collectionActions: @Composable () -> Unit = {
                CollectionActionsRow(
                    canPlay = songs.isNotEmpty(),
                    favorite = isFav,
                    onPlay = playAll,
                    onFavorite = { UserLibrary.toggleFavoritePlaylist(detailPlaylist) },
                    onSelect = selection::start,
                    loading = loading && songs.isEmpty(),
                    modifier = if (isBook) Modifier.testTag("book-chapter-toolbar") else Modifier,
                    middleActions = if (isBook) {
                        {
                            BookChapterActions(
                                page = activePage,
                                total = bookTotal ?: playlist.total.takeIf { it > 0 },
                                descending = bookDescending,
                                loading = bookPageLoading,
                                placeholder = loading && songs.isEmpty(),
                                onPick = { bookPickerOpen = true },
                                onToggleDirection = {
                                    val descending = !bookDescending
                                    BookCatalogPaging.pageForDirection(1, bookTotal, descending)?.let { page ->
                                        requestBookPage(page, directionOnAccept = descending)
                                    }
                                },
                            )
                        }
                    } else null,
                )
            }
            // 同一个操作栏实现只显示在一个位置：原位或覆盖层；原位始终等高占位。
            val controlBar: @Composable (Boolean) -> Unit = { pinned ->
                ChromeFloatingBar(
                    state = controlBarHazeState,
                    topOffset = chromeTopInset,
                    height = controlBarHeight,
                    pinned = pinned,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(chromeHeaderColor()),
                    ) {
                        collectionActions()
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
                error != null -> ErrorState(
                    error!!,
                    onRetry = {
                        if (!loading) {
                            loading = true
                            if (isBook) requestBookPage(activeBookPage, preserveScroll = true)
                            else scope.launch { loadFirstPage() }
                        }
                    },
                    retrying = loading,
                    modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                )
                loading && songs.isEmpty() -> Column(
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
                    Column(Modifier.height(controlBarHeight)) {
                        collectionActions()
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
                songs.isEmpty() -> EmptyState(
                    emptyHint,
                    Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current),
                )
                else -> {
                    // 听书是单页替换窗口；滚动定位到页尾不得触发下一页覆盖目标章。
                    if (!isBook) LoadMoreOnScroll(listState, hasMore, loadingMore, onLoadMore = ::loadMore)
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
                                Box(Modifier.fillMaxWidth().height(controlBarHeight)) {
                                    if (!controlBarPinned) controlBar(false)
                                }
                            }
                        }
                        itemsIndexed(displaySongs, key = { _, song -> song.uid }) { _, song ->
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
                        if (hasMore && !isBook) {
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
                    if (isBook && !selection.active) {
                        Surface(
                            onClick = ::locateCurrentBookChapter,
                            enabled = currentBookSong != null && !bookPageLoading,
                            shape = CircleShape,
                            color = MeloraAppearance.card.copy(alpha = 0.82f),
                            border = MeloraAppearance.cardBorder,
                            shadowElevation = 0.dp,
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .padding(end = 6.5.dp, bottom = 26.dp).size(44.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.MyLocation, contentDescription = "定位当前播放",
                                    tint = if (currentBookSong != null && !bookPageLoading) TextSub else TextMuted,
                                    modifier = Modifier.size(19.dp))
                            }
                        }
                    }
                }
            }
        }

        if (bookPickerOpen && isBook) {
            BookChapterPicker(
                total = bookTotal,
                currentOrdinal = BookCatalogPaging.rangeStart(activePage),
                descending = bookDescending,
                onDismiss = { bookPickerOpen = false },
                onSelectOrdinal = { ordinal ->
                    BookCatalogPaging.pageForOrdinal(ordinal)?.let { page ->
                        requestBookPage(page, targetOrdinal = ordinal)
                    }
                },
            )
        }
        moreSong?.let { song ->
            SongMoreSheet(song) { moreSong = null }
        }
    }
}
