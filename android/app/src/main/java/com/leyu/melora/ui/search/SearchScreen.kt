package com.leyu.melora.ui.search

import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.chromeHeaderColor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlaybackController
import com.leyu.melora.playback.UserLibrary
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.Recommender
import com.leyu.melora.ui.common.PlaylistDetailContent
import com.leyu.melora.ui.common.SongMoreSheet
import com.leyu.melora.ui.common.rememberDetailState
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.playback.UiTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    category: SearchCategory,
    onCategoryChange: (SearchCategory) -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    var selectedPlatform by remember {
        mutableStateOf(
            PlatformSource.entries.firstOrNull { it.id == MeloraSettings.searchPlatform.value }
                ?: PlatformSource.All,
        )
    }
    var platformMenuExpanded by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val searchHistory by UserLibrary.searchHistory.collectAsStateWithLifecycle()

    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val recommendationSources = if (selectedPlatform == PlatformSource.All) platformIds else listOf(selectedPlatform.id)
    val sourceKey = if (selectedPlatform == PlatformSource.All) "all" else selectedPlatform.id
    val hotKey = "searchHot.${category.name}.$sourceKey"
    // 搜索意图改变后不能继续命中另一组热词/历史的推荐结果。
    val intentKey = searchHistory.take(3).hashCode()
    val songRecommendKey = "searchRecommend.songs.$sourceKey.$intentKey"
    val playlistRecommendKey = "searchRecommend.playlists.$sourceKey.$intentKey"
    val hotPlaylistKey = "searchHotPlaylists.$sourceKey"
    var hotWords by remember(hotKey) { mutableStateOf(OnlineCache.peek<List<String>>(hotKey).orEmpty()) }
    var hotLoading by remember(hotKey) { mutableStateOf(hotWords.isEmpty()) }
    var recommendSongs by remember(songRecommendKey) { mutableStateOf(OnlineCache.peek<List<OnlineSong>>(songRecommendKey).orEmpty()) }
    var recommendLoading by remember(songRecommendKey) { mutableStateOf(recommendSongs.isEmpty()) }
    var recommendPlaylists by remember(playlistRecommendKey) {
        mutableStateOf(OnlineCache.peek<List<OnlinePlaylist>>(playlistRecommendKey).orEmpty())
    }
    var recommendPlaylistsLoading by remember(playlistRecommendKey) { mutableStateOf(recommendPlaylists.isEmpty()) }
    var recommendBooks by remember { mutableStateOf(OnlineCache.peek<List<OnlinePlaylist>>("searchRecommend.books").orEmpty()) }
    var recommendBooksLoading by remember { mutableStateOf(recommendBooks.isEmpty()) }
    var hotPlaylists by remember(hotPlaylistKey) {
        mutableStateOf(OnlineCache.peek<List<OnlinePlaylist>>(hotPlaylistKey).orEmpty())
    }
    var hotPlaylistsLoading by remember(hotPlaylistKey) { mutableStateOf(hotPlaylists.isEmpty()) }
    var songs by remember { mutableStateOf<List<OnlineSong>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<OnlinePlaylist>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchRequestId by remember { mutableIntStateOf(0) }
    var openedPlaylist by rememberDetailState<OnlinePlaylist>()
    var moreSong by remember { mutableStateOf<OnlineSong?>(null) }

    val searching = submitted.isNotBlank()
    val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()

    fun cancelSearch() {
        searchRequestId++
        searchJob?.cancel()
        searchJob = null
        loading = false
        loadingMore = false
    }

    fun runSearch(keyword: String, targetPage: Int, append: Boolean) {
        if (keyword.isBlank() || (append && loadingMore)) return
        searchJob?.cancel()
        val requestId = ++searchRequestId
        val requestCategory = category
        val requestPlatform = selectedPlatform
        searchJob = scope.launch {
            if (append) loadingMore = true else loading = true
            error = null
            try {
                when (requestCategory) {
                    SearchCategory.Song -> {
                        if (requestPlatform == PlatformSource.All && !append) {
                            var firstBatch = true
                            var receivedBatch = false
                            hasMore = false
                            searchSongsProgressive(context, requestPlatform, keyword, targetPage) { batch, more ->
                                if (requestId != searchRequestId) return@searchSongsProgressive
                                receivedBatch = true
                                if (firstBatch) {
                                    songs = batch.distinctBy { "${it.name}|${it.singer}" }
                                    loading = false
                                    firstBatch = false
                                } else {
                                    songs = (songs + batch).distinctBy { "${it.name}|${it.singer}" }
                                }
                                if (more) hasMore = true
                            }
                            if (requestId == searchRequestId && !receivedBatch) {
                                songs = emptyList()
                                hasMore = false
                            }
                        } else {
                            val outcome = searchSongs(context, requestPlatform, keyword, targetPage)
                            if (requestId != searchRequestId) return@launch
                            songs = if (append) (songs + outcome.songs).distinctBy(OnlineSong::uid) else outcome.songs
                            hasMore = outcome.hasMore
                        }
                    }
                    SearchCategory.Playlist -> {
                        val outcome = searchPlaylists(context, requestPlatform, keyword, targetPage)
                        if (requestId != searchRequestId) return@launch
                        playlists = if (append) {
                            (playlists + outcome.playlists).distinctBy { "${it.source}_${it.id}" }
                        } else {
                            outcome.playlists
                        }
                        hasMore = outcome.hasMore
                    }
                    SearchCategory.Audiobook -> {
                        val outcome = KwBookApi.search(keyword, targetPage)
                        if (requestId != searchRequestId) return@launch
                        playlists = if (append) (playlists + outcome.items).distinctBy(OnlinePlaylist::id) else outcome.items
                        hasMore = outcome.hasMore
                    }
                }
                page = targetPage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (requestId == searchRequestId) error = failure.message ?: "搜索失败，请稍后重试"
            } finally {
                if (requestId == searchRequestId) {
                    loading = false
                    loadingMore = false
                    searchJob = null
                }
            }
        }
    }

    // 空闲态：热搜与推荐（内存缓存秒显 + 过期静默刷新，避免进页"折叠再展开"）
    LaunchedEffect(hotKey, searching) {
        if (searching) return@LaunchedEffect
        // 歌单分类不取热词：该区块直接展示真实热门歌单
        if (category == SearchCategory.Playlist) {
            hotLoading = false
            return@LaunchedEffect
        }
        val ttl = 15 * 60 * 1000L
        if (OnlineCache.get<List<String>>(hotKey, ttl) != null) {
            hotLoading = false
            return@LaunchedEffect
        }
        if (hotWords.isEmpty()) hotLoading = true
        runCatchingCancellable {
            when (category) {
                SearchCategory.Audiobook -> audiobookScenarioWords()
                else -> Recommender.hotSearch(context, recommendationSources)
            }
        }.onSuccess {
            hotWords = it
            if (it.isNotEmpty()) OnlineCache.put(hotKey, it)
        }
        hotLoading = false
    }

    LaunchedEffect(category, songRecommendKey, searching) {
        if (searching || category != SearchCategory.Song) return@LaunchedEffect
        val ttl = 15 * 60 * 1000L
        OnlineCache.get<List<OnlineSong>>(songRecommendKey, ttl)?.let {
            recommendSongs = it
            recommendLoading = false
            return@LaunchedEffect
        }
        recommendLoading = recommendSongs.isEmpty()
        runCatchingCancellable { Recommender.searchSongs(context, recommendationSources) }
            .onSuccess {
                if (it.isNotEmpty()) {
                    recommendSongs = it
                    OnlineCache.put(songRecommendKey, it)
                }
            }
        recommendLoading = false
    }

    // 歌单分类猜你喜欢：搜索意图模型，与发现页精选歌单独立。
    LaunchedEffect(category, playlistRecommendKey, searching) {
        if (searching || category != SearchCategory.Playlist) return@LaunchedEffect
        val ttl = 15 * 60 * 1000L
        OnlineCache.get<List<OnlinePlaylist>>(playlistRecommendKey, ttl)?.let {
            recommendPlaylists = it
            recommendPlaylistsLoading = false
            return@LaunchedEffect
        }
        recommendPlaylistsLoading = recommendPlaylists.isEmpty()
        runCatchingCancellable { Recommender.searchPlaylists(context, recommendationSources) }
            .onSuccess {
                if (it.isNotEmpty()) {
                    recommendPlaylists = it
                    OnlineCache.put(playlistRecommendKey, it)
                }
            }
        recommendPlaylistsLoading = false
    }

    // 听书分类猜你喜欢：最近收听专辑相似项，专区目录负责冷启动。
    LaunchedEffect(category, searching) {
        if (searching || category != SearchCategory.Audiobook) return@LaunchedEffect
        val key = "searchRecommend.books"
        val ttl = 15 * 60 * 1000L
        OnlineCache.get<List<OnlinePlaylist>>(key, ttl)?.let {
            recommendBooks = it
            recommendBooksLoading = false
            return@LaunchedEffect
        }
        recommendBooksLoading = recommendBooks.isEmpty()
        runCatchingCancellable { Recommender.searchAudiobooks() }
            .onSuccess {
                if (it.isNotEmpty()) {
                    recommendBooks = it
                    OnlineCache.put(key, it)
                }
            }
        recommendBooksLoading = false
    }

    // 歌单分类空闲态：拉平台真实热门歌单，替代"热词换标题"的伪热门
    LaunchedEffect(category, selectedPlatform.id, searching) {
        if (searching || category != SearchCategory.Playlist) return@LaunchedEffect
        OnlineCache.get<List<OnlinePlaylist>>(hotPlaylistKey, 15 * 60 * 1000L)?.let {
            hotPlaylists = it
            return@LaunchedEffect
        }
        hotPlaylistsLoading = true
        runCatchingCancellable { Recommender.hotPlaylists(context, recommendationSources) }
            .onSuccess {
                hotPlaylists = it
                if (it.isNotEmpty()) OnlineCache.put(hotPlaylistKey, it)
            }
        hotPlaylistsLoading = false
    }

    // 输入联想：防抖后请求平台搜索建议
    LaunchedEffect(query, selectedPlatform, category) {
        val text = query.trim()
        if (text.isEmpty() || text == submitted) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(280)
        suggestions = runCatchingCancellable {
            OnlineRepository.tipSearch(
                context,
                if (selectedPlatform == PlatformSource.All) "kw" else selectedPlatform.id,
                text,
            )
        }.getOrDefault(emptyList()).take(8)
    }

    // 关键词/分类/平台变化时刷新结果
    LaunchedEffect(submitted, category, selectedPlatform) {
        if (submitted.isBlank()) {
            cancelSearch()
        } else {
            songs = emptyList()
            playlists = emptyList()
            page = 1
            hasMore = false
            loadingMore = false
            runSearch(submitted, 1, append = false)
        }
    }

    fun submit(keyword: String) {
        val text = keyword.trim()
        if (text.isEmpty()) return
        UserLibrary.addSearchKeyword(text)
        query = text
        if (submitted == text) {
            runSearch(text, 1, append = false)
        } else {
            submitted = text
        }
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    if (openedPlaylist != null) {
        PlaylistDetailContent(
            playlist = openedPlaylist!!,
            onBack = { openedPlaylist = null },
            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        )
    } else {
        ChromeScaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(chromeHeaderColor()),
                ) {
                    SearchTopBar(
                        onOpenDrawer = onOpenDrawer,
                        selectedCategory = category,
                        onCategoryChange = onCategoryChange,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                    ) {
                        SearchInputSection(
                            category = category,
                            query = query,
                            submitted = submitted,
                            selectedPlatform = selectedPlatform,
                            platformMenuExpanded = platformMenuExpanded,
                            suggestions = suggestions,
                            onQueryChange = { query = it },
                            onSubmit = { submit(it) },
                            onClear = {
                                query = ""
                                submitted = ""
                                cancelSearch()
                                songs = emptyList()
                                playlists = emptyList()
                                error = null
                            },
                            onPlatformMenuExpandedChange = { platformMenuExpanded = it },
                            onPlatformChange = { platform ->
                                selectedPlatform = platform
                                MeloraSettings.updateSearchPlatform(platform.id)
                                platformMenuExpanded = false
                            },
                        )
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (!searching) {
                    SearchIdleContent(
                        category = category,
                        selectedPlatform = selectedPlatform,
                        searchHistory = searchHistory,
                        hotWords = hotWords,
                        hotLoading = hotLoading,
                        hotPlaylists = hotPlaylists,
                        hotPlaylistsLoading = hotPlaylistsLoading,
                        recommendSongs = recommendSongs,
                        recommendLoading = recommendLoading,
                        recommendPlaylists = recommendPlaylists,
                        recommendPlaylistsLoading = recommendPlaylistsLoading,
                        recommendBooks = recommendBooks,
                        recommendBooksLoading = recommendBooksLoading,
                        favoriteUids = favoriteUids,
                        onSubmit = { submit(it) },
                        onOpenPlaylist = { openedPlaylist = it },
                        onMoreSong = { moreSong = it },
                        onPlaySong = { song ->
                            PlaybackController.playTrack(context, UiTrack.fromOnline(song))
                        },
                    )
                } else {
                    SearchResultsContent(
                        category = category,
                        submitted = submitted,
                        selectedPlatform = selectedPlatform,
                        loading = loading,
                        error = error,
                        playlists = playlists,
                        songs = songs,
                        hasMore = hasMore,
                        loadingMore = loadingMore,
                        favoriteUids = favoriteUids,
                        onRetry = { runSearch(submitted, 1, append = false) },
                        onLoadMore = { runSearch(submitted, page + 1, append = true) },
                        onOpenPlaylist = { openedPlaylist = it },
                        onMoreSong = { moreSong = it },
                        onPlaySong = { song ->
                            PlaybackController.playTrack(context, UiTrack.fromOnline(song))
                        },
                    )
                }
            }
        }
    }

    moreSong?.let { song -> SongMoreSheet(song) { moreSong = null } }
}
