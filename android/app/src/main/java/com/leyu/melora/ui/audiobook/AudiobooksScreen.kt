package com.leyu.melora.ui.audiobook

import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.PageBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.ui.common.*
import com.leyu.melora.ui.discover.WatermarkGradientCard
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val RANK_TTL = 24 * 60 * 60 * 1000L
private const val LIST_TTL = 15 * 60 * 1000L
private val DefaultRanks = listOf(
    KwBookApi.BookRankTab("13", "热播榜", listOf(KwBookApi.BookTag("27", "总榜"))),
    KwBookApi.BookRankTab("20", "VIP会员榜", listOf(KwBookApi.BookTag("128", "总榜"))),
    KwBookApi.BookRankTab("1", "有声小说", listOf(KwBookApi.BookTag("30", "总榜"))),
    KwBookApi.BookRankTab("14", "口碑榜", listOf(KwBookApi.BookTag("28", "总榜"))),
    KwBookApi.BookRankTab("15", "畅销榜", listOf(KwBookApi.BookTag("29", "总榜"))),
)
private data class Shortcut(val tabId: String, val label: String, val icon: ImageVector, val colors: List<Color>)
private val Shortcuts = listOf(
    Shortcut("20", "VIP会员榜", Icons.Outlined.WorkspacePremium, listOf(Color(0xFFFFA726), Color(0xFFFF9100))),
    Shortcut("14", "口碑榜", Icons.Outlined.StarOutline, listOf(Color(0xFFBA68C8), Color(0xFF9C27B0))),
    Shortcut("1", "有声小说榜", Icons.Outlined.AutoStories, listOf(Color(0xFF42A5F5), Color(0xFF1E88E5))),
    Shortcut("15", "畅销榜", Icons.AutoMirrored.Outlined.TrendingUp, listOf(Color(0xFFFF6456), Color(0xFFFF5252))),
)

@Composable
fun AudiobooksScreen(
    modifier: Modifier = Modifier,
    scrollToTopRequest: Int = 0,
    primaryHeader: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val pullEnabled by MeloraSettings.pullToRefresh.collectAsStateWithLifecycle()
    val recentSongs by UserLibrary.recents.collectAsStateWithLifecycle()
    var ranks by remember {
        mutableStateOf(OnlineCache.peek<List<KwBookApi.BookRankTab>>("book.ranks")?.takeIf { it.isNotEmpty() } ?: DefaultRanks)
    }
    var rankPage by remember { mutableStateOf<KwBookApi.BookRankTab?>(null) }
    var openedPlaylist by remember { mutableStateOf<OnlinePlaylist?>(null) }
    val recentChapter = remember(recentSongs) { recentSongs.firstOrNull(OnlineSong::isBookChapter) }
    val recentPlaylist = remember(recentChapter) { recentChapter?.asPlaylist() }

    LaunchedEffect(Unit) {
        val cached = OnlineCache.get<List<KwBookApi.BookRankTab>>("book.ranks", RANK_TTL)
        if (!cached.isNullOrEmpty()) ranks = cached else runCatchingCancellable { KwBookApi.ranks() }.onSuccess {
            if (it.isNotEmpty()) { ranks = it; OnlineCache.put("book.ranks", it) }
        }
    }
    DetailPageHost(
        target = openedPlaylist,
        modifier = modifier,
        contentKey = { playlist -> "book:${playlist.source}:${playlist.id}" },
        detail = { playlist ->
            PlaylistDetailContent(
                playlist = playlist,
                onBack = { openedPlaylist = null },
                emptyHint = "该有声专辑暂无可播放音频",
            )
        },
    ) {
        DetailPageHost(
            target = rankPage,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it.id },
            detail = { rank ->
                RankPage(rank, onBack = { rankPage = null }, onOpen = { openedPlaylist = it })
            },
        ) {
            // 首页始终展示热播榜；打开其他榜单不能换掉仍参与过渡的首页数据。
            val homeTab = ranks.firstOrNull { it.id == "13" } ?: DefaultRanks.first()
            BookRankContent(
                tabId = homeTab.id,
                tagId = homeTab.tags.first().id,
                scrollToTopRequest = scrollToTopRequest,
                pullEnabled = pullEnabled,
                primaryHeader = { primaryHeader() },
                onOpen = { openedPlaylist = it },
            ) { count ->
                item("bento") {
                    BookBento(
                        recent = recentPlaylist,
                        chapter = recentChapter,
                        ranks = ranks,
                        onRecent = {
                            if (recentPlaylist == null) PlaybackController.postMessage(context, "暂无最近收听记录")
                            else openedPlaylist = recentPlaylist
                        },
                        onRecentPlay = { recentPlaylist?.let { playOnlinePlaylist(context, it) } },
                        onRank = { id -> rankPage = ranks.firstOrNull { it.id == id } ?: DefaultRanks.first { it.id == id } },
                    )
                }
                item("hot-title") { SectionTitle(count) }
            }
        }
    }
}

/** 缓存数据与分页游标一起恢复，不能把已翻页的列表当成第一页。 */
internal data class BookRankSnapshot(
    val items: List<OnlinePlaylist> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
) {
    fun withPage(result: KwBookApi.BookPage, number: Int) = BookRankSnapshot(
        items = (if (number == 1) result.items else items + result.items).distinctBy(OnlinePlaylist::id),
        page = number,
        hasMore = result.hasMore,
    )
}

/** 首页和各榜单各自持有状态，共用唯一的加载/分页/渲染链路。 */
@Composable
private fun BookRankContent(
    tabId: String,
    tagId: String,
    onOpen: (OnlinePlaylist) -> Unit,
    primaryHeader: @Composable (LazyListState) -> Unit,
    scrollToTopRequest: Int = 0,
    pullEnabled: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
    before: LazyListScope.(Int) -> Unit = {},
) = key(tabId, tagId) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val cacheKey = "book.rank.$tabId.$tagId"
    var snapshot by remember { mutableStateOf(OnlineCache.peek<BookRankSnapshot>(cacheKey) ?: BookRankSnapshot()) }
    var loading by remember { mutableStateOf(snapshot.items.isEmpty()) }
    var refreshing by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    FastScrollToTopEffect(scrollToTopRequest, listState)

    suspend fun load(number: Int) {
        error = null
        try {
            runCatchingCancellable { KwBookApi.rank(tabId, tagId, number) }
                .onSuccess {
                    snapshot = snapshot.withPage(it, number)
                    OnlineCache.put(cacheKey, snapshot)
                }
                .onFailure {
                    if (snapshot.items.isEmpty()) error = it.message ?: "听书内容加载失败"
                    else PlaybackController.postMessage(context, it.message ?: "听书内容同步失败")
                }
        } finally {
            loading = false
            refreshing = false
            loadingMore = false
        }
    }
    fun refresh() {
        if (loading || refreshing || loadingMore) return
        refreshing = true
        loading = snapshot.items.isEmpty()
        scope.launch { load(1) }
    }
    fun loadMore() {
        if (loading || refreshing || loadingMore || !snapshot.hasMore) return
        loadingMore = true
        scope.launch { load(snapshot.page + 1) }
    }
    LaunchedEffect(Unit) {
        val cached = OnlineCache.get<BookRankSnapshot>(cacheKey, LIST_TTL)
        if (cached != null) { snapshot = cached; loading = false } else {
            loading = true
            load(1)
        }
    }
    ChromeScaffold(
        modifier = Modifier.fillMaxSize(),
        expectedTopBarHeight = 64.dp,
        topBar = { primaryHeader(listState) },
    ) {
        PullRefreshContainer(enabled = pullEnabled, refreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
            BookGrid(listState, snapshot.items, loading, error, snapshot.hasMore, loadingMore,
                ::refresh, ::loadMore, onOpen, padding = padding) { before(snapshot.items.size) }
        }
    }
}

@Composable
private fun SectionTitle(count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text("热搜榜", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = TextMain)
        if (count > 0) Text("$count 部", fontSize = 11.sp, color = TextSub)
    }
}

@Composable
private fun RankPage(tab: KwBookApi.BookRankTab, onBack: () -> Unit, onOpen: (OnlinePlaylist) -> Unit) {
    BackHandler(onBack = onBack)
    var selectedTag by remember { mutableIntStateOf(0) }
    val tag = tab.tags.getOrElse(selectedTag) { tab.tags.first() }
    var showCategories by remember { mutableStateOf(false) }
    val hasCategories = tab.tags.size > 1
    BookRankContent(
        tabId = tab.id,
        tagId = tag.id,
        onOpen = onOpen,
        padding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        primaryHeader = { listState ->
            val scrollToTop = rememberFastScrollToTop(listState)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(chromeHeaderColor())
                    .height(64.dp)
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = TextMain) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .titleScrollToTop(scrollToTop),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(tab.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hasCategories) {
                    ChromeActionSurface(onClick = { showCategories = true }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(32.dp)) {
                        Row(Modifier.padding(start = 11.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tag.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.Rounded.KeyboardArrowDown, "选择${tab.name}分类", tint = TextSub, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        },
    )
    if (showCategories && hasCategories) {
        RankCategorySheet(tab, tag.id, { showCategories = false; selectedTag = it }, { showCategories = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RankCategorySheet(tab: KwBookApi.BookRankTab, selectedTag: String, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    MeloraBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MeloraAppearance.canvas, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(tab.name, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = TextMain)
            Text("选择${tab.name}分类", fontSize = 12.sp, color = TextSub, modifier = Modifier.padding(top = 3.dp, bottom = 18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tab.tags.forEachIndexed { tagIndex, item ->
                    val selected = item.id == selectedTag
                    Surface(onClick = { onSelect(tagIndex) }, shape = RoundedCornerShape(11.dp), color = if (selected) BrandBlue else MeloraAppearance.card, border = if (selected) null else MeloraAppearance.chipBorder) {
                        Text(item.name, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Color.White else TextSub, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BookBento(
    recent: OnlinePlaylist?, chapter: OnlineSong?, ranks: List<KwBookApi.BookRankTab>,
    onRecent: () -> Unit, onRecentPlay: () -> Unit, onRank: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RecentCard(recent, chapter, onRecent, onRecentPlay, Modifier.width(138.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Shortcuts.chunked(2).forEach { shortcuts ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    shortcuts.forEach { shortcut ->
                        WatermarkGradientCard(
                            title = shortcut.label,
                            icon = shortcut.icon,
                            gradient = Brush.linearGradient(shortcut.colors),
                            onClick = { onRank(ranks.firstOrNull { it.id == shortcut.tabId }?.id ?: shortcut.tabId) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(
    playlist: OnlinePlaylist?, chapter: OnlineSong?, onClick: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MeloraAppearance.card, border = MeloraAppearance.cardBorder, modifier = modifier.fillMaxHeight()) {
        Box(Modifier.fillMaxSize()) {
            if (playlist?.img != null) SongArtwork(playlist.img, "recent-book-${playlist.id}", Modifier.fillMaxSize(), 20)
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF5B4BCE), Color(0xFF312E81))))) {
                Icon(Icons.Outlined.Headphones, null, tint = Color.White.copy(alpha = .25f), modifier = Modifier.align(Alignment.Center).size(66.dp))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.18f), Color.Black.copy(.84f)))))
            Surface(shape = RoundedCornerShape(10.dp), color = Color.Black.copy(.42f), modifier = Modifier.align(Alignment.TopStart).padding(10.dp)) {
                Text("最近收听", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 48.dp, bottom = 12.dp)) {
                Text(playlist?.name ?: "暂无收听记录", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(chapter?.displayName() ?: if (playlist == null) "从热搜榜发现好节目" else "继续上次的节目", fontSize = 11.sp, color = Color.White.copy(.78f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
            if (playlist != null) {
                CardPlayButton(
                    queueId = "playlist.${playlist.source}.${playlist.id}",
                    onPlay = onPlay,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    size = 32.dp,
                    iconSize = 20.dp,
                )
            }
        }
    }
}

@Composable
private fun BookGrid(
    state: LazyListState, playlists: List<OnlinePlaylist>, loading: Boolean, error: String?, hasMore: Boolean, loadingMore: Boolean,
    onRetry: () -> Unit, onLoadMore: () -> Unit, onOpen: (OnlinePlaylist) -> Unit, modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), before: LazyListScope.() -> Unit = {},
) {
    val columns = responsiveGridColumns()
    if (playlists.isNotEmpty()) LoadMoreOnScroll(state, hasMore, loadingMore, onLoadMore = onLoadMore)
    LazyColumn(state = state, modifier = modifier.fillMaxSize(), contentPadding = chromeContentPadding(padding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        before()
        when {
            loading && playlists.isEmpty() -> item("loading") { SkeletonGrid(columns = columns, cards = columns * 2, spacing = 10.dp) }
            error != null && playlists.isEmpty() -> item("error") { Box(Modifier.fillMaxWidth().height(360.dp)) { ErrorState(error, modifier = Modifier.fillMaxSize(), onRetry = onRetry) } }
            playlists.isEmpty() -> item("empty") { Box(Modifier.fillMaxWidth().height(360.dp)) { EmptyState("暂无相关内容", Modifier.fillMaxSize()) } }
            else -> itemsIndexed(playlists.chunked(columns), key = { _, row -> row.joinToString(":") { it.id } }) { _, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { book -> OnlineAudiobookCard(book, { onOpen(book) }, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (hasMore) item("more") {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                if (loadingMore) CircularProgressIndicator(Modifier.size(18.dp), color = BrandBlue, strokeWidth = 1.7.dp)
                else Text("上滑加载更多", fontSize = 13.sp, color = BrandBlue, modifier = Modifier.clickable(onClick = onLoadMore).padding(8.dp))
            }
        }
    }
}

private fun OnlineSong.asPlaylist(): OnlinePlaylist? = albumId.takeIf(String::isNotBlank)?.let {
    OnlinePlaylist.from(
        JSONObject()
            .put("id", "book_album_$it")
            .put("name", albumName.ifBlank { name })
            .put("source", source)
            .put("kind", "book")
            .put("author", singer)
            .put("img", img ?: "")
            .put("description", raw.optString("description"))
            .put("play_count", raw.optString("play_count")),
    )
}
private fun OnlineSong.displayName(): String = name.removePrefix(albumName).trim().ifBlank { name }
