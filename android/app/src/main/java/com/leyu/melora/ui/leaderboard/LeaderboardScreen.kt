package com.leyu.melora.ui.leaderboard

import com.leyu.melora.ui.common.ChromeActionSurface
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.DetailPageHost
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.LocalChromeTopInset
import com.leyu.melora.ui.common.FastScrollToTopEffect
import com.leyu.melora.ui.common.rememberFastScrollToTop
import com.leyu.melora.ui.common.titleScrollToTop

import android.content.Context

import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UiTrack
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.ui.common.SongArtwork
import com.leyu.melora.ui.common.PullRefreshContainer
import com.leyu.melora.playback.sdk.BoardItem
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.theme.MeloraAppearance
import com.leyu.melora.ui.common.BrandBlue
import com.leyu.melora.ui.common.CardPlayButton
import com.leyu.melora.ui.common.CardWhite
import com.leyu.melora.ui.common.DividerSoft
import com.leyu.melora.ui.common.EmptyState
import com.leyu.melora.ui.common.ErrorState
import com.leyu.melora.ui.common.SkeletonCrossfade
import com.leyu.melora.ui.common.SkeletonLeaderboard
import com.leyu.melora.ui.common.SkeletonPreviewRows
import com.leyu.melora.ui.common.SkeletonSongList
import com.leyu.melora.ui.common.OnlineSongRow
import com.leyu.melora.ui.common.SongMoreSheet
import com.leyu.melora.ui.common.TextMain
import com.leyu.melora.ui.common.TextMuted
import com.leyu.melora.ui.common.TextSub
import com.leyu.melora.ui.common.sourceAliasDisplay
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.common.runCatchingCancellable
import kotlinx.coroutines.launch

data class BoardPlatform(
    val id: String,
    val name: String,
)

/** 榜单平台顺序：主界面顶栏的"平台筛选"也复用该列表。 */
val boardPlatforms = listOf(
    BoardPlatform("kw", "酷我"),
    BoardPlatform("kg", "酷狗"),
    BoardPlatform("tx", "企鹅"),
    BoardPlatform("wy", "网易云"),
    BoardPlatform("mg", "咪咕"),
)

private const val BOARD_SONGS_TTL_MS = 15 * 60 * 1000L
private const val BOARD_PREVIEW_LIMIT = 3
private fun boardPreviewCacheKey(platform: String, boardId: String) = "boardSongsPreview.$platform.$boardId"
private fun boardPageCacheKey(platform: String, boardId: String) = "boardSongsPage.$platform.$boardId"

/** 详情与播放只使用完整第一页快照，预览使用独立的三首快照，避免内容边界互相污染。 */
private suspend fun loadBoardFirstPage(context: Context, platform: String, boardId: String) =
    OnlineCache.refresh<SongPage>(
        boardPageCacheKey(platform, boardId),
        BOARD_SONGS_TTL_MS,
    ) {
        OnlineRepository.boardSongs(context, platform, boardId, 1)
    }

private suspend fun loadBoardPreview(context: Context, platform: String, boardId: String): SongPage {
    OnlineCache.get<SongPage>(boardPageCacheKey(platform, boardId), BOARD_SONGS_TTL_MS)
        ?.let { return it.copy(list = it.list.take(BOARD_PREVIEW_LIMIT)) }
    return OnlineCache.refresh<SongPage>(boardPreviewCacheKey(platform, boardId), BOARD_SONGS_TTL_MS) {
        OnlineRepository.boardSongs(context, platform, boardId, page = 1, limit = BOARD_PREVIEW_LIMIT)
            .let { it.copy(list = it.list.take(BOARD_PREVIEW_LIMIT)) }
    }
}

private fun playBoard(context: Context, platform: String, board: BoardItem) {
    val queueId = "board.$platform.${board.bangid}"
    val cacheKey = boardPageCacheKey(platform, board.bangid)
    val cached = OnlineCache.peek<SongPage>(cacheKey)
    UserLibrary.markContainerPlayed(
        UserLibrary.PlayContainer(
            kind = "board",
            id = board.bangid,
            name = board.name,
            img = board.img ?: cached?.list?.firstOrNull()?.img,
            source = platform,
            queueId = queueId,
        ),
    )
    PlaybackController.requestQueue(context, queueId, cacheKey, songs = SongPage::list) {
        OnlineRepository.boardSongs(context, platform, board.bangid, 1)
    }
}

private val peakGradients = listOf(
    Brush.linearGradient(listOf(Color(0xFF881337), Color(0xFFE11D48))),
    Brush.linearGradient(listOf(Color(0xFF78350F), Color(0xFFD97706))),
    Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF3B82F6))),
)

private val peakBadges = listOf(Color(0xFFE11D48), Color(0xFFD97706), Color(0xFF3B82F6))

private val genreTagColors = listOf(
    Color(0xFF059669), Color(0xFF7C3AED), Color(0xFFDB2777),
    Color(0xFF2563EB), Color(0xFFD97706), Color(0xFF4B5563),
)

@Composable
fun LeaderboardScreen(
    modifier: Modifier = Modifier,
    scrollToTopRequest: Int = 0,
    primaryHeader: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 平台切换完全由顶栏"平台筛选"驱动，页面即时跟随设置
    val platformId by MeloraSettings.leaderboardPlatform.collectAsStateWithLifecycle()
    val platform = boardPlatforms.firstOrNull { it.id == platformId } ?: boardPlatforms.first()
    var refreshing by remember { mutableStateOf(false) }
    val pullEnabled by MeloraSettings.pullToRefresh.collectAsStateWithLifecycle()

    val cacheTtl = 15 * 60 * 1000L
    val boardsKey = "boards.${platform.id}"
    // 秒开优先：先用已有缓存（哪怕过期）渲染，切换平台不再闪加载，过期数据由后台静默刷新
    var boards by remember(platform.id) {
        mutableStateOf(OnlineCache.peek<List<BoardItem>>(boardsKey).orEmpty())
    }
    var loading by remember(platform.id) { mutableStateOf(boards.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember(platform.id) { mutableIntStateOf(0) }
    var selectedBoard by remember { mutableStateOf<BoardItem?>(null) }
    val listState = key(platform.id) { rememberLazyListState() }
    FastScrollToTopEffect(scrollToTopRequest, listState)

    LaunchedEffect(platform.id, retryKey) {
        if (retryKey == 0 && boards.isEmpty()) {
            OnlineCache.hydrateBoardList(context, boardsKey)?.let {
                boards = it
                loading = false
            }
        }
        val fresh = OnlineCache.get<List<BoardItem>>(boardsKey, cacheTtl)
        if (fresh != null && retryKey == 0) {
            boards = fresh
            loading = false
            error = null
            return@LaunchedEffect
        }
        val hasContent = boards.isNotEmpty()
        if (!hasContent) loading = true
        error = null
        val requestToken = requireNotNull(OnlineCache.capturePageSnapshot(boardsKey))
        runCatchingCancellable { OnlineRepository.boards(context, platform.id, background = hasContent) }
            .onSuccess {
                val writtenAtMs = if (it.isNotEmpty()) {
                    OnlineCache.putPageIfCurrent(requestToken, boardsKey, it)
                } else {
                    if (OnlineCache.isPageSnapshotCurrent(requestToken, boardsKey)) 0L else null
                }
                if (writtenAtMs == null) return@onSuccess
                boards = it
                loading = false
                if (writtenAtMs > 0L) {
                    OnlineCache.persistBoardList(
                        context,
                        boardsKey,
                        it,
                        requestToken,
                        writtenAtMs,
                    )
                }
            }
            .onFailure { if (boards.isEmpty()) error = it.message ?: "榜单加载失败" }
        loading = false
    }

    DetailPageHost(
        target = selectedBoard,
        modifier = modifier,
        contentKey = { board -> "${platform.id}:${board.bangid}" },
        detail = { board ->
            BoardDetailContent(
                platform = platform,
                board = board,
                onBack = { selectedBoard = null },
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
                            val requestToken = requireNotNull(OnlineCache.capturePageSnapshot(boardsKey))
                            runCatchingCancellable { OnlineRepository.boards(context, platform.id) }
                                .onSuccess {
                                    val writtenAtMs = if (it.isNotEmpty()) {
                                        OnlineCache.putPageIfCurrent(requestToken, boardsKey, it)
                                    } else {
                                        if (OnlineCache.isPageSnapshotCurrent(requestToken, boardsKey)) 0L else null
                                    }
                                    if (writtenAtMs == null) return@onSuccess
                                    boards = it
                                    if (writtenAtMs > 0L) {
                                        OnlineCache.persistBoardList(
                                            context,
                                            boardsKey,
                                            it,
                                            requestToken,
                                            writtenAtMs,
                                        )
                                    }
                                    error = null
                                }
                                .onFailure { PlaybackController.postMessage(context, it.message ?: "榜单同步失败") }
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
                    SkeletonCrossfade(
                        visible = loading,
                        modifier = Modifier.fillMaxSize(),
                        skeleton = { SkeletonLeaderboard(modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current)) },
                    ) {
                        when {
                            error != null -> ErrorState(error!!, onRetry = { retryKey++ }, modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
                            boards.isEmpty() -> EmptyState("该平台暂无榜单", Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
                            else -> LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
                            ) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Outlined.Whatshot,
                                            contentDescription = null,
                                            tint = Color(0xFFDC2626),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = "${sourceAliasDisplay(platform.id, platform.name)}官方榜",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextMain,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "官方榜单 · 每日更新",
                                            fontSize = 11.sp,
                                            color = TextMuted,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }

                                // 主打三大榜（含实时 Top 3 直读）
                                items(boards.take(3), key = { "${platform.id}:${it.bangid}" }) { board ->
                                    val position = boards.take(3).indexOf(board)
                                    BoardPeekCard(
                                        platform = platform.id,
                                        board = board,
                                        gradient = peakGradients[position % peakGradients.size],
                                        badgeColor = peakBadges[position % peakBadges.size],
                                        onOpen = { selectedBoard = board },
                                    )
                                }

                                if (boards.size > 3) {
                                    item {
                                        Spacer(Modifier.height(4.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Outlined.Explore,
                                                contentDescription = null,
                                                tint = BrandBlue,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "更多垂类榜单",
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = TextMain,
                                            )
                                        }
                                    }

                                    items(
                                        items = boards.drop(3).chunked(3),
                                        key = { rowItems -> rowItems.joinToString("|") { it.id } },
                                    ) { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            rowItems.forEach { board ->
                                                val tagColor = genreTagColors[(boards.indexOf(board)) % genreTagColors.size]
                                                GenreBoardCard(
                                                    platform = platform.id,
                                                    board = board,
                                                    tagColor = tagColor,
                                                    modifier = Modifier.weight(1f),
                                                    onClick = { selectedBoard = board },
                                                )
                                            }
                                            repeat(3 - rowItems.size) {
                                                Spacer(Modifier.weight(1f))
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

// 榜单歌曲详情
@Composable
internal fun BoardDetailContent(
    platform: BoardPlatform,
    board: BoardItem,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val boardKey = "${platform.id}:${board.bangid}"
    val listState = key(boardKey) { rememberLazyListState() }
    val scrollToTop = rememberFastScrollToTop(listState)
    // 系统返回键先退出榜单详情回到榜单列表，而不是退出应用
    BackHandler(onBack = onBack)
    val detailCacheKey = boardPageCacheKey(platform.id, board.bangid)
    // 详情只读取完整第一页快照；预览快照不参与详情分页。
    val cachedPage = remember(boardKey) { OnlineCache.peek<SongPage>(detailCacheKey) }
    var songs by remember(boardKey) { mutableStateOf(cachedPage?.list.orEmpty()) }
    var loading by remember(boardKey) { mutableStateOf(songs.isEmpty()) }
    var error by remember(boardKey) { mutableStateOf<String?>(null) }
    var page by remember(boardKey) { mutableIntStateOf(1) }
    var hasMore by remember(boardKey) { mutableStateOf(cachedPage?.hasMore() ?: false) }
    var loadingMore by remember(boardKey) { mutableStateOf(false) }
    var moreSong by remember { mutableStateOf<OnlineSong?>(null) }
    val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()

    suspend fun load(targetPage: Int, append: Boolean) {
        if (loadingMore) return
        if (append) loadingMore = true
        if (!append && songs.isEmpty()) loading = true
        error = null
        try {
            runCatchingCancellable {
                if (append) OnlineRepository.boardSongs(context, platform.id, board.bangid, targetPage)
                else loadBoardFirstPage(context, platform.id, board.bangid)
            }.onSuccess { result ->
                val before = songs.size
                songs = if (append) (songs + result.list).distinctBy { it.uid } else result.list
                // 部分平台不回传 page（解析默认为 1），推进以实际请求页为准。
                hasMore = result.copy(page = targetPage).hasMore(songs.size) && (!append || songs.size > before)
                page = targetPage
            }.onFailure {
                if (songs.isEmpty()) error = it.message ?: "榜单歌曲加载失败"
                else if (append) PlaybackController.postMessage(context, it.message ?: "加载更多失败")
            }
        } finally {
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(boardKey) { load(1, append = false) }

    ChromeScaffold(expectedTopBarHeight = 64.dp, topBar = {
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .titleScrollToTop(scrollToTop),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(board.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (songs.isNotEmpty()) {
                ChromeActionSurface(
                    onClick = {
                        UserLibrary.markContainerPlayed(
                            UserLibrary.PlayContainer(
                                kind = "board",
                                id = board.bangid,
                                name = board.name,
                                img = board.img ?: songs.firstOrNull()?.img,
                                source = platform.id,
                                queueId = "board.${platform.id}.${board.bangid}",
                            ),
                        )
                        PlaybackController.playQueue(
                            context,
                            songs.toUiTracks(),
                            0,
                            "board.${platform.id}.${board.bangid}",
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = BrandBlue,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "播放全部",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BrandBlue,
                        )
                    }
                }
            }
        }
    }) {

        when {
            loading -> SkeletonSongList(modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
            error != null -> ErrorState(error!!, onRetry = { scope.launch { load(1, false) } }, modifier = Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
            songs.isEmpty() -> EmptyState("榜单暂无歌曲", Modifier.fillMaxSize().padding(top = LocalChromeTopInset.current))
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = chromeContentPadding(PaddingValues(bottom = 24.dp)),
            ) {
                itemsIndexed(songs, key = { _, song -> song.uid }) { index, song ->
                    OnlineSongRow(
                        song = song,
                        showAlbum = true,
                        isFavorite = song.uid in favoriteUids,
                        onMore = { moreSong = song },
                        onClick = {
                            UserLibrary.markContainerPlayed(
                                UserLibrary.PlayContainer(
                                    kind = "board",
                                    id = board.bangid,
                                    name = board.name,
                                    img = board.img ?: songs.firstOrNull()?.img,
                                    source = platform.id,
                                    queueId = "board.${platform.id}.${board.bangid}",
                                ),
                            )
                            PlaybackController.playTrack(context, UiTrack.fromOnline(song))
                        },
                    )
                }
                if (hasMore) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !loadingMore) { scope.launch { load(page + 1, true) } }
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(if (loadingMore) "加载中…" else "加载更多", fontSize = 13.sp, color = BrandBlue)
                        }
                    }
                }
            }
        }
    }

    moreSong?.let { song -> SongMoreSheet(song) { moreSong = null } }
}

/** 标准黑胶唱片：深色盘体、同心沟槽、唱标与轴孔。 */
@Composable
private fun VinylDisc(
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        drawCircle(color = Color(0xFF17191F), radius = radius)
        listOf(0.88f, 0.73f, 0.58f, 0.43f).forEach { ratio ->
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = radius * ratio,
                style = Stroke(width = 0.7.dp.toPx()),
            )
        }
        drawCircle(color = accent.copy(alpha = 0.9f), radius = radius * 0.22f)
        drawCircle(color = Color(0xFF101218), radius = radius * 0.055f)
    }
}

// 主打榜单卡：唱片套 + 半露黑胶 + 实时 Top3
@Composable
private fun BoardPeekCard(
    platform: String,
    board: BoardItem,
    gradient: Brush,
    badgeColor: Color,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    // 预览只读取三首快照；详情与播放使用另一条完整分页快照。
    val boardKey = "$platform:${board.bangid}"
    val peekCacheKey = boardPreviewCacheKey(platform, board.bangid)
    var songs by remember(boardKey) {
        mutableStateOf((OnlineCache.peek<SongPage>(peekCacheKey)
            ?: OnlineCache.peek<SongPage>(boardPageCacheKey(platform, board.bangid)))?.list?.take(BOARD_PREVIEW_LIMIT).orEmpty())
    }
    var failed by remember(boardKey) { mutableStateOf(false) }
    var attempt by remember(boardKey) { mutableIntStateOf(0) }

    LaunchedEffect(boardKey, attempt) {
        if (songs.isEmpty()) failed = false
        runCatchingCancellable { loadBoardPreview(context, platform, board.bangid) }
            .onSuccess { songs = it.list }
            .onFailure { if (songs.isEmpty()) failed = true }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .height(108.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(128.dp)
                    .fillMaxHeight()
                    .clickable(onClick = onOpen),
                contentAlignment = Alignment.CenterStart,
            ) {
                VinylDisc(
                    accent = badgeColor,
                    modifier = Modifier
                        .size(80.dp)
                        .align(Alignment.CenterEnd),
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .size(88.dp)
                        .align(Alignment.CenterStart),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 透明榜单封面需要渐变底衬；真实图片则保持更亮、更接近原始封面。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(gradient),
                        )
                        val cover = board.img ?: songs.firstOrNull()?.img
                        if (cover != null) {
                            SongArtwork(cover, "board-cover-${board.bangid}", Modifier.fillMaxSize(), cornerRadius = 16)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.16f)),
                            )
                        }
                        CardPlayButton(
                            queueId = "board.$platform.${board.bangid}",
                            onPlay = { playBoard(context, platform, board) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(7.dp),
                            size = 26.dp,
                            iconSize = 16.dp,
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Crossfade(
                    targetState = when {
                        songs.isNotEmpty() -> 1
                        failed -> 2
                        else -> 0
                    },
                    modifier = Modifier.fillMaxSize(),
                    animationSpec = tween(durationMillis = 200),
                    label = "peekSwap",
                ) { phase ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        when (phase) {
                            1 -> Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                songs.forEachIndexed { index, song ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                PlaybackController.playTrack(context, UiTrack.fromOnline(song))
                                            }
                                            .padding(vertical = 1.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${index + 1}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (index == 0) badgeColor else TextMuted,
                                            modifier = Modifier.width(18.dp),
                                        )
                                        Text(
                                            text = song.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = TextMain,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = " - ${song.singer}",
                                            fontSize = 11.sp,
                                            color = TextSub,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (index < songs.lastIndex) {
                                        HorizontalDivider(
                                            color = DividerSoft,
                                            thickness = 0.6.dp,
                                            modifier = Modifier.padding(start = 18.dp),
                                        )
                                    }
                                }
                            }
                            2 -> Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = { attempt++ },
                                    ),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = "榜单数据暂不可用，点击重试",
                                    fontSize = 12.sp,
                                    color = BrandBlue,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            else -> SkeletonPreviewRows(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

// 垂类榜单方卡
@Composable
private fun GenreBoardCard(
    platform: String,
    board: BoardItem,
    tagColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = CardWhite,
        shadowElevation = 0.dp,
        border = MeloraAppearance.cardBorder,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                // 渐变底常驻：透明榜单封面（酷我 logo）也能正常显示
                .background(
                    Brush.linearGradient(
                        listOf(tagColor.copy(alpha = 0.85f), tagColor.copy(alpha = 0.45f)),
                    ),
                ),
        ) {
            if (!board.img.isNullOrBlank()) {
                // 平台真实榜单封面；统一保留底部名称，避免透明或无字封面失去识别信息
                SongArtwork(board.img, "genre-board-${board.bangid}", Modifier.fillMaxSize(), cornerRadius = 16)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.18f)),
                )
            } else {
                Text(
                    text = board.name.take(2),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 底部渐变遮罩：三列紧凑布局下仍保证榜单名可读
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.38f),
                                Color.Black.copy(alpha = 0.78f),
                            ),
                        ),
                    ),
            )

            Surface(
                shape = RoundedCornerShape(topStart = 12.dp, bottomEnd = 9.dp),
                color = tagColor,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    text = "榜",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }

            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .align(Alignment.BottomEnd),
            ) {
                CardPlayButton(
                    queueId = "board.$platform.${board.bangid}",
                    onPlay = { playBoard(context, platform, board) },
                    size = 24.dp,
                    iconSize = 14.dp,
                )
            }

            // 三列卡片只保留必要的榜单名，去掉重复的“官方榜单”副标题
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 34.dp, bottom = 8.dp),
            ) {
                Text(
                    text = board.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}
