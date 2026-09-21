package com.leyu.melora.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.CoverLoader
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.Recommender
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.common.CardPlayButton
import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.DetailPageHost
import com.leyu.melora.ui.common.FastScrollToTopEffect
import com.leyu.melora.ui.common.chromeContentPadding
import com.leyu.melora.ui.common.OnlineSongsPage
import com.leyu.melora.ui.common.PlaylistDetailContent
import com.leyu.melora.ui.common.PullRefreshContainer
import com.leyu.melora.ui.common.ShimmerBox
import com.leyu.melora.ui.common.SkeletonCrossfade
import com.leyu.melora.ui.common.SongArtwork
import com.leyu.melora.ui.common.playOnlinePlaylist
import com.leyu.melora.ui.common.rememberOnlineSongCover
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.common.toUiTracks
import com.leyu.melora.ui.theme.MeloraAppearance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val CACHE_TTL = 15 * 60 * 1000L

internal data class DiscoverDateInfo(
    val day: String,
    val month: String,
)

/** 日/月必须从同一个时间快照生成，避免午夜边界出现跨日组合。 */
internal fun discoverDateInfo(
    now: Date = Date(),
    timeZone: TimeZone = TimeZone.getDefault(),
): DiscoverDateInfo {
    val formatted = SimpleDateFormat("dd|MMM", Locale.ENGLISH).apply {
        this.timeZone = timeZone
    }.format(now)
    val separator = formatted.indexOf('|')
    return DiscoverDateInfo(
        day = formatted.substring(0, separator),
        month = formatted.substring(separator + 1),
    )
}

// 发现页架构（100% 对齐用户设计参考图）：
// 1. 顶部 1+1+2+2 卡片流设计：
//    - [1] 每日推荐 (高 175dp，宽 138dp，左上角醒目明黄日历标签 "13 / Sep"，封面底图，右下白三角播放钮)
//    - [1] 猜你喜欢 (高 175dp，宽 138dp，封面底图，标题 + 歌曲副标，右下白三角播放钮)
//    - [2] 上为「百万收藏」珊瑚红背景带爱心浮印，下为「新歌推荐」暖橙背景带音符浮印
//    - [2] 左滑完整可见：上为「歌单广场」淡紫渐变带歌单浮印，下为「排行榜」鲜绿渐变带榜单浮印
// 2. 推荐精选歌单（横滑大方卡）
// 3. 畅销听书专区（一体化白卡）
@Composable
fun DiscoverScreen(
    modifier: Modifier = Modifier,
    onNavigateToTab: (Int) -> Unit = {},
    scrollToTopRequest: Int = 0,
    primaryHeader: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    FastScrollToTopEffect(scrollToTopRequest, listState)

    // 入页只读取一次日期快照，日/月一致；异步内容重组不重复格式化时间。
    val dateInfo = remember { discoverDateInfo() }

    // 秒开优先：先拿已有缓存（哪怕过期）渲染，过期内容由后台静默替换，进页不闪空
    var dailySong by remember { mutableStateOf(OnlineCache.peek<OnlineSong>("discover.daily")) }
    var guessSongs by remember { mutableStateOf(OnlineCache.peek<List<OnlineSong>>("discover.guess").orEmpty()) }
    var newSongs by remember { mutableStateOf(OnlineCache.peek<List<OnlineSong>>("discover.new").orEmpty()) }
    var playlists by remember { mutableStateOf(OnlineCache.peek<List<OnlinePlaylist>>("discover.playlists").orEmpty()) }
    var audiobooks by remember { mutableStateOf(OnlineCache.peek<List<OnlinePlaylist>>("discover.books").orEmpty()) }
    var dailyLoading by remember { mutableStateOf(dailySong == null) }
    var guessLoading by remember { mutableStateOf(guessSongs.isEmpty()) }
    var dailyFailed by remember { mutableStateOf(false) }
    var guessFailed by remember { mutableStateOf(false) }
    var playlistsLoading by remember { mutableStateOf(playlists.isEmpty()) }
    var audiobooksLoading by remember { mutableStateOf(audiobooks.isEmpty()) }
    var openedPlaylist by remember { mutableStateOf<OnlinePlaylist?>(null) }
    var openedSongsPage by remember { mutableStateOf<DiscoverSongs?>(null) }
    var millionDetail by remember { mutableStateOf<Unit?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val pullEnabled by MeloraSettings.pullToRefresh.collectAsStateWithLifecycle()
    // 推荐位例外：无论单曲封面开关如何，始终加载卡片封面。
    val dailyArtwork = rememberOnlineSongCover(dailySong, enabled = true)
    // 旧快照/分区先后返回时也避免两张主卡出现同一首；完整播放池仍以推荐域为准。
    val visibleGuessSongs = guessSongs.filterNot { it.uid == dailySong?.uid }.take(6)
    val guessArtwork = rememberOnlineSongCover(visibleGuessSongs.firstOrNull(), enabled = true)

    // 每块独立启动/显示；猜你喜欢的每日池去重依赖由推荐域处理，不在页面串行等待。
    LaunchedEffect(refreshTick) {
        val round = refreshTick
        val force = round > 0
        if (force) Recommender.beginDiscoveryRefresh()
        try {
            coroutineScope {
                launch {
                    val fresh = OnlineCache.get<OnlineSong>("discover.daily", CACHE_TTL)
                    if (!force && fresh != null) {
                        dailySong = fresh
                        dailyLoading = false
                    } else {
                        dailyLoading = dailySong == null
                        dailyFailed = false
                        try {
                            runCatchingCancellable { Recommender.daily(context, refresh = force) }
                                .onSuccess { list ->
                                    list.firstOrNull()?.let { first ->
                                        dailySong = first
                                        OnlineCache.put("discover.daily", first)
                                        OnlineCache.put("discover.daily.list", list)
                                    }
                                }
                                .onFailure { dailyFailed = true }
                        } finally { if (refreshTick == round) dailyLoading = false }
                    }
                }
                launch {
                    val fresh = OnlineCache.get<List<OnlineSong>>("discover.guess", CACHE_TTL)
                    if (!force && !fresh.isNullOrEmpty()) {
                        guessSongs = fresh
                        guessLoading = false
                    } else {
                        guessLoading = guessSongs.isEmpty()
                        guessFailed = false
                        try {
                            runCatchingCancellable { Recommender.guess(context, refresh = force) }
                                .onSuccess { list ->
                                    if (list.isNotEmpty()) {
                                        // 推荐域已排除本轮每日完整池，不依赖可能仍未回填的页面字段。
                                        guessSongs = list.take(6)
                                        OnlineCache.put("discover.guess", guessSongs)
                                        OnlineCache.put("discover.guess.list", list)
                                    }
                                }
                                .onFailure { guessFailed = true }
                        } finally { if (refreshTick == round) guessLoading = false }
                    }
                }
                launch {
                    val fresh = OnlineCache.get<List<OnlineSong>>("discover.new", CACHE_TTL)
                    if (!force && !fresh.isNullOrEmpty()) {
                        newSongs = fresh
                    } else {
                        runCatchingCancellable { Recommender.newSongs(context, refresh = force) }
                            .onSuccess { list ->
                                if (list.isNotEmpty()) {
                                    newSongs = list
                                    OnlineCache.put("discover.new", newSongs)
                                    OnlineCache.put("discover.new.list", newSongs)
                                }
                            }
                    }
                }
                launch {
                    val fresh = OnlineCache.get<List<OnlinePlaylist>>("discover.playlists", CACHE_TTL)
                    if (!force && !fresh.isNullOrEmpty()) {
                        playlists = fresh
                        playlistsLoading = false
                    } else {
                        playlistsLoading = playlists.isEmpty()
                        try {
                            runCatchingCancellable { Recommender.discoverPlaylists(context, refresh = force) }
                                .onSuccess { list ->
                                    if (list.isNotEmpty()) {
                                        playlists = list
                                        OnlineCache.put("discover.playlists", list)
                                    }
                                }
                        } finally { if (refreshTick == round) playlistsLoading = false }
                    }
                }
                launch {
                    val fresh = OnlineCache.get<List<OnlinePlaylist>>("discover.books", CACHE_TTL)
                    if (!force && !fresh.isNullOrEmpty()) {
                        audiobooks = fresh
                        audiobooksLoading = false
                    } else {
                        audiobooksLoading = audiobooks.isEmpty()
                        try {
                            runCatchingCancellable { Recommender.discoverAudiobooks(refresh = force) }
                                .onSuccess { list ->
                                    if (list.isNotEmpty()) {
                                        audiobooks = list
                                        OnlineCache.put("discover.books", list)
                                    }
                                }
                        } finally { if (refreshTick == round) audiobooksLoading = false }
                    }
                }
            }
        } finally { if (refreshTick == round) refreshing = false }
    }

    // 推荐池/榜单统一取数：rec.daily、rec.guess 走个性化推荐引擎，其余为酷我榜单 id
    suspend fun discoverSongs(source: String, page: Int): SongPage = when (source) {
        "rec.daily" -> Recommender.daily(context).let { SongPage(it, it.size, 1, 1) }
        "rec.guess" -> Recommender.guess(context).let { SongPage(it, it.size, 1, 1) }
        else -> OnlineRepository.boardSongs(context, "kw", source, page)
    }

    // 卡片播放按钮：优先缓存秒播，未缓存则拉第一页后再播
    fun playDiscoverSongs(source: String, queueId: String, cacheKey: String, containerName: String, containerKind: String) {
        val cached = OnlineCache.peek<List<OnlineSong>>(cacheKey)
        UserLibrary.markContainerPlayed(
            UserLibrary.PlayContainer(
                kind = containerKind,
                id = queueId,
                name = containerName,
                img = cached?.firstOrNull()?.let { CoverLoader.cachedUrl(it) } ?: cached?.firstOrNull()?.img,
                source = "kw",
                queueId = queueId,
            ),
        )
        PlaybackController.requestQueue(context, queueId, cacheKey, songs = { it }) { discoverSongs(source, 1).list }
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
        DetailPageHost(
            target = openedSongsPage,
            modifier = Modifier.fillMaxSize(),
            contentKey = { songs -> songs.cacheKey },
            detail = { target ->
                val containerKind = when (target.queueId) {
                    "discover.daily" -> "daily"
                    "discover.guess" -> "guess"
                    else -> "new"
                }
                OnlineSongsPage(
                    title = target.title,
                    subtitle = target.subtitle,
                    queueId = target.queueId,
                    cacheKey = target.cacheKey,
                    onBack = { openedSongsPage = null },
                    container = UserLibrary.PlayContainer(
                        kind = containerKind,
                        id = target.queueId,
                        name = target.title,
                        img = null,
                        source = "kw",
                        queueId = target.queueId,
                    ),
                    fetchPage = { page -> discoverSongs(target.source, page) },
                )
            },
        ) {
            DetailPageHost(
                target = millionDetail,
                modifier = Modifier.fillMaxSize(),
                detail = { _ ->
                    MillionPlaylistsPage(
                        onBack = { millionDetail = null },
                        onOpen = { openedPlaylist = it },
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
                        canPull = !listState.canScrollBackward,
                        onRefresh = {
                            refreshing = true
                            refreshTick++
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                contentPadding = chromeContentPadding(PaddingValues(bottom = 36.dp)),
                            ) {
                                // --- 1. 顶部 1 + 1 + 2 + 2 矩阵横滑卡片流 (严格对齐参考图) ---
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(DISCOVER_TOP_HORIZONTAL_GAP_DP.dp),
                                    ) {
                                        // [1] 每日推荐 (高 175dp, 宽 138dp)
                                        DailyRecommendCard(
                                            day = dateInfo.day,
                                            month = dateInfo.month,
                                            subtitle = dailySong?.let { "${it.name} · ${it.singer}" }
                                                ?: if (dailyFailed) "加载失败，点开重试" else "暂无推荐，点开重试",
                                            loading = dailyLoading,
                                            artwork = dailyArtwork,
                                            seed = dailySong?.uid ?: "recommend_seed_1",
                                            queueId = "discover.daily",
                                            onPlay = { playDiscoverSongs("rec.daily", "discover.daily", "discover.daily.list", "每日推荐", "daily") },
                                            onClick = {
                                                openedSongsPage = DiscoverSongs("每日推荐", "根据口味每日精选 30 首", "rec.daily", "discover.daily", "discover.daily.list")
                                            },
                                        )

                                        // [1] 猜你喜欢 (高 175dp, 宽 138dp)
                                        GuessYouLikeCard(
                                            subtitle = visibleGuessSongs.firstOrNull()?.let { "${it.name} · ${it.singer}" }
                                                ?: if (guessFailed) "加载失败，点开重试" else "暂无推荐，点开重试",
                                            loading = guessLoading,
                                            artwork = guessArtwork,
                                            seed = visibleGuessSongs.firstOrNull()?.uid ?: "recommend_seed_2",
                                            queueId = "discover.guess",
                                            onPlay = { playDiscoverSongs("rec.guess", "discover.guess", "discover.guess.list", "猜你喜欢", "guess") },
                                            onClick = {
                                                openedSongsPage = DiscoverSongs("猜你喜欢", "根据你的最近播放推荐 30 首", "rec.guess", "discover.guess", "discover.guess.list")
                                            },
                                        )

                                        // [2] 百万收藏 + 新歌推荐 (两张小方卡上下堆叠，高 175dp, 宽 116dp)
                                        Column(
                                            modifier = Modifier
                                                .width(DISCOVER_TOP_STACK_WIDTH_DP.dp)
                                                .height(DISCOVER_TOP_CARD_HEIGHT_DP.dp),
                                            verticalArrangement = Arrangement.spacedBy(DISCOVER_TOP_STACK_GAP_DP.dp),
                                        ) {
                                            // 上卡：百万热播 (珊瑚红 + 火焰浮印，真实播放量 ≥ 100 万的歌单页)
                                            WatermarkGradientCard(
                                                title = "百万热播",
                                                icon = Icons.Outlined.Whatshot,
                                                gradient = Brush.linearGradient(
                                                    listOf(Color(0xFFFF6456), Color(0xFFFF5252)),
                                                ),
                                                onClick = { millionDetail = Unit },
                                                modifier = Modifier.weight(1f),
                                            )

                                            // 下卡：新歌推荐 (明快暖橙 + 音符浮水印)
                                            WatermarkGradientCard(
                                                title = "新歌推荐",
                                                icon = Icons.Outlined.MusicNote,
                                                gradient = Brush.linearGradient(
                                                    listOf(Color(0xFFFFA726), Color(0xFFFF9100)),
                                                ),
                                                onClick = {
                                                    openedSongsPage = DiscoverSongs("新歌推荐", "近期新歌精选 30 首", "17", "discover.new", "discover.new.list")
                                                },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }

                                        // [2] 歌单广场 + 排行榜 (剩余的 2，左滑完整可见)
                                        Column(
                                            modifier = Modifier
                                                .width(DISCOVER_TOP_STACK_WIDTH_DP.dp)
                                                .height(DISCOVER_TOP_CARD_HEIGHT_DP.dp),
                                            verticalArrangement = Arrangement.spacedBy(DISCOVER_TOP_STACK_GAP_DP.dp),
                                        ) {
                                            // 上卡：歌单广场 (淡紫渐变 + 歌单浮水印)
                                            WatermarkGradientCard(
                                                title = "歌单广场",
                                                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                                                gradient = Brush.linearGradient(
                                                    listOf(Color(0xFFBA68C8), Color(0xFF9C27B0)),
                                                ),
                                                onClick = { onNavigateToTab(3) }, // 跳转歌单
                                                modifier = Modifier.weight(1f),
                                            )

                                            // 下卡：官方榜单 (鲜绿渐变 + 奖杯/榜单浮水印)
                                            WatermarkGradientCard(
                                                title = "官方榜单",
                                                icon = Icons.Outlined.Leaderboard,
                                                gradient = Brush.linearGradient(
                                                    listOf(Color(0xFF00E676), Color(0xFF00C853)),
                                                ),
                                                onClick = { onNavigateToTab(1) }, // 跳转排行榜
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }

                                // --- 2. 推荐精选歌单 (横滑大方卡) ---
                                item {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = "推荐精选歌单",
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = TextMain,
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable { onNavigateToTab(3) }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                            ) {
                                                Text("查看更多", fontSize = 12.sp, color = TextSub)
                                                Icon(
                                                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                                    contentDescription = null,
                                                    tint = TextSub,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(12.dp))

                                        SkeletonCrossfade(
                                            visible = playlists.isEmpty() && playlistsLoading,
                                            skeleton = {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    repeat(3) { SkeletonDiscoverPlaylistCard() }
                                                }
                                            },
                                        ) {
                                        if (playlists.isEmpty()) {
                                            Box(Modifier.fillMaxWidth().height(136.dp), contentAlignment = Alignment.Center) {
                                                Text("暂未获取精选歌单，可下拉重试", fontSize = 12.sp, color = TextSub)
                                            }
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            playlists.forEach { pl ->
                                                Surface(
                                                    onClick = { openedPlaylist = pl },
                                                    shape = RoundedCornerShape(18.dp),
                                                    color = CardBg,
                                                    shadowElevation = 0.dp,
                                                    border = MeloraAppearance.cardBorder,
                                                    modifier = Modifier.width(136.dp),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .aspectRatio(1f)
                                                            .clip(RoundedCornerShape(18.dp)),
                                                    ) {
                                                        SongArtwork(pl.img, "${pl.source}_${pl.id}", Modifier.fillMaxSize(), cornerRadius = 18)

                                                        // 底部渐变遮罩：保证白色文字可读
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .fillMaxHeight(0.62f)
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

                                                        // 播放量角标
                                                        Surface(
                                                            shape = RoundedCornerShape(bottomStart = 8.dp),
                                                            color = Color.Black.copy(alpha = 0.45f),
                                                            modifier = Modifier.align(Alignment.TopEnd),
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                            ) {
                                                                Icon(
                                                                    Icons.Outlined.Headphones,
                                                                    contentDescription = null,
                                                                    tint = Color.White,
                                                                    modifier = Modifier.size(10.dp),
                                                                )
                                                                Spacer(Modifier.width(3.dp))
                                                                Text(
                                                                    text = pl.playCountLabel.ifBlank { "—" },
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = Color.White,
                                                                )
                                                            }
                                                        }

                                                        // 播放钮：直放歌单（与歌单页卡片一致，播放态跟随）
                                                        Box(
                                                            modifier = Modifier
                                                                .padding(6.dp)
                                                                .align(Alignment.BottomEnd),
                                                        ) {
                                                            CardPlayButton(
                                                                queueId = "playlist.${pl.source}.${pl.id}",
                                                                onPlay = { playOnlinePlaylist(context, pl) },
                                                                size = 26.dp,
                                                                iconSize = 16.dp,
                                                                background = Color.Black.copy(alpha = 0.45f),
                                                            )
                                                        }

                                                        // 歌单名叠在封面底部
                                                        Text(
                                                            text = pl.name,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color.White,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                            lineHeight = 16.sp,
                                                            modifier = Modifier
                                                                .align(Alignment.BottomStart)
                                                                .fillMaxWidth()
                                                                .padding(start = 8.dp, end = 36.dp, bottom = 8.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        }
                                    }
                                }

                                // --- 3. 畅销听书专区 (一体化白卡) ---
                                item {
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = "精选听书专区",
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = TextMain,
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable { onNavigateToTab(4) }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                            ) {
                                                Text("听书广场", fontSize = 12.sp, color = TextSub)
                                                Icon(
                                                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                                    contentDescription = null,
                                                    tint = TextSub,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(12.dp))

                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = CardBg,
                                            shadowElevation = 0.dp,
                                            border = MeloraAppearance.cardBorder,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            SkeletonCrossfade(
                                                visible = audiobooks.isEmpty() && audiobooksLoading,
                                                skeleton = {
                                                    Column {
                                                        repeat(6) { index ->
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                            ) {
                                                                ShimmerBox(Modifier.size(48.dp), cornerRadius = 8.dp)
                                                                Column(
                                                                    modifier = Modifier
                                                                        .weight(1f)
                                                                        .padding(horizontal = 12.dp),
                                                                ) {
                                                                    ShimmerBox(Modifier.fillMaxWidth(0.52f).height(13.dp), cornerRadius = 6.dp)
                                                                    Spacer(Modifier.height(7.dp))
                                                                    ShimmerBox(Modifier.fillMaxWidth(0.34f).height(11.dp), cornerRadius = 5.dp)
                                                                }
                                                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                                                    ShimmerBox(Modifier.size(18.dp), cornerRadius = 9.dp)
                                                                }
                                                            }
                                                            if (index < 5) {
                                                                HorizontalDivider(
                                                                    color = DividerSoft,
                                                                    thickness = 0.6.dp,
                                                                    modifier = Modifier.padding(horizontal = 14.dp),
                                                                )
                                                            }
                                                        }
                                                    }
                                                },
                                            ) {
                                            Column {
                                                audiobooks.forEachIndexed { index, book ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { openedPlaylist = book }
                                                            .padding(horizontal = 14.dp, vertical = 11.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        SongArtwork(book.img, "${book.source}_${book.id}", Modifier.size(48.dp), cornerRadius = 8)
                                                        Column(
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .padding(horizontal = 12.dp),
                                                        ) {
                                                            Text(
                                                                text = book.name,
                                                                fontSize = 14.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = TextMain,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                            Text(
                                                                text = listOfNotNull(
                                                                    book.author.takeIf { it.isNotBlank() },
                                                                    book.total.takeIf { it > 0 }?.let { "$it 集" },
                                                                ).joinToString(" · ").ifBlank { "有声专辑" },
                                                                fontSize = 12.sp,
                                                                color = TextSub,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.padding(top = 2.dp),
                                                            )
                                                        }
                                                        CardPlayButton(
                                                            queueId = "playlist.${book.source}.${book.id}",
                                                            onPlay = { playOnlinePlaylist(context, book) },
                                                            size = 32.dp,
                                                            iconSize = 18.dp,
                                                            background = Color.Transparent,
                                                            tint = BrandBlue,
                                                        )
                                                    }
                                                    if (index < audiobooks.lastIndex) {
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
                        }
                    }
                }
            }
        }
    }
}
