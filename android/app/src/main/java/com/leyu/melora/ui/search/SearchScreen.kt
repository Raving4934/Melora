package com.leyu.melora.ui.search

import com.leyu.melora.ui.common.ChromeScaffold
import com.leyu.melora.ui.common.DetailPageHost
import com.leyu.melora.ui.common.rememberFastScrollToTop
import com.leyu.melora.ui.common.chromeHeaderColor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.Recommender
import com.leyu.melora.ui.common.PlaylistDetailContent
import com.leyu.melora.ui.common.SongMoreSheet
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.playback.UiTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal val SearchChromeHeaderHeight =
    SearchTopBarHeight +
        SearchInputSectionHeight +
        SearchInputSectionBottomSpacing +
        SearchInputContainerBottomPadding

internal fun searchExpectedTopBarHeight(suggestionsVisible: Boolean) =
    if (suggestionsVisible) null else SearchChromeHeaderHeight

private data class SearchPageRequest(val page: Int, val error: String? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    category: SearchCategory,
    onCategoryChange: (SearchCategory) -> Unit,
    navigationIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val platformId by MeloraSettings.searchPlatform.collectAsStateWithLifecycle()
    val selectedPlatform = if (category == SearchCategory.Audiobook) PlatformSource.Kuwo
        else PlatformSource.entries.firstOrNull { it.id == platformId } ?: PlatformSource.All
    var platformMenuExpanded by remember { mutableStateOf(false) }

    val queryState = rememberTextFieldState()
    var submitted by rememberSaveable { mutableStateOf("") }
    val searchHistory by UserLibrary.searchHistory.collectAsStateWithLifecycle()

    val suggestionText = queryState.text.toString().trim().takeUnless { it == submitted }.orEmpty()
    var suggestions by remember(suggestionText, selectedPlatform, category) { mutableStateOf(emptyList<String>()) }
    val recommendationSources = selectedPlatform.searchSources
    val sourceKey = selectedPlatform.id
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
    var recommendBooks by remember { mutableStateOf(OnlineCache.peek<List<OnlinePlaylist>>("searchRecommend.books").orEmpty()) }
    var hotPlaylists by remember(hotPlaylistKey) {
        mutableStateOf(OnlineCache.peek<List<OnlinePlaylist>>(hotPlaylistKey).orEmpty())
    }
    var hotPlaylistsLoading by remember(hotPlaylistKey) { mutableStateOf(hotPlaylists.isEmpty()) }
    var openedPlaylist by remember { mutableStateOf<OnlinePlaylist?>(null) }
    var moreSong by remember { mutableStateOf<OnlineSong?>(null) }

    val searching = submitted.isNotBlank()
    val searchKey = Triple(submitted, category, selectedPlatform)
    var results by remember(searchKey) { mutableStateOf(SearchResults()) }
    var request by remember(searchKey) { mutableStateOf<SearchPageRequest?>(SearchPageRequest(1)) }
    val idleListState = key(category, selectedPlatform) { rememberLazyListState() }
    val resultListState = key(searchKey) { rememberLazyListState() }
    val scrollToTop = rememberFastScrollToTop(if (searching) resultListState else idleListState)
    val favoriteUids by UserLibrary.favoriteUids.collectAsStateWithLifecycle()

    // 换词、换类和换平台会取消旧请求，失败请求保留页码供原位重试。
    val current = request
    LaunchedEffect(searchKey, current) {
        if (!searching || current == null || current.error != null) return@LaunchedEffect
        try {
            val onUpdate: ((SearchResults) -> Unit)? =
                if (category == SearchCategory.Song && selectedPlatform == PlatformSource.All && current.page == 1) {
                    { partial ->
                        if (request === current && !partial.isEmpty) results = partial.copy(hasMore = false)
                    }
                } else null
            val next = searchResultPage(context, category, selectedPlatform, submitted, current.page, onUpdate,
                sourcePages = results.nextSourcePages.takeIf { current.page > 1 })
            ensureActive()
            if (request !== current) return@LaunchedEffect
            results = results.withPage(next)
            request = null
        } catch (failure: Exception) {
            ensureActive()
            if (request === current) request = current.copy(error = failure.message ?: "搜索失败，请稍后重试")
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
            return@LaunchedEffect
        }
        runCatchingCancellable { Recommender.searchPlaylists(context, recommendationSources) }
            .onSuccess {
                if (it.isNotEmpty()) {
                    recommendPlaylists = it
                    OnlineCache.put(playlistRecommendKey, it)
                }
            }
    }

    // 听书分类猜你喜欢：最近收听专辑相似项，专区目录负责冷启动。
    LaunchedEffect(category, searching) {
        if (searching || category != SearchCategory.Audiobook) return@LaunchedEffect
        val key = "searchRecommend.books"
        val ttl = 15 * 60 * 1000L
        OnlineCache.get<List<OnlinePlaylist>>(key, ttl)?.let {
            recommendBooks = it
            return@LaunchedEffect
        }
        runCatchingCancellable { Recommender.searchAudiobooks() }
            .onSuccess {
                if (it.isNotEmpty()) {
                    recommendBooks = it
                    OnlineCache.put(key, it)
                }
            }
    }

    // 歌单分类空闲态：拉平台真实热门歌单，替代"热词换标题"的伪热门
    LaunchedEffect(category, selectedPlatform.id, searching) {
        if (searching || category != SearchCategory.Playlist) return@LaunchedEffect
        OnlineCache.get<List<OnlinePlaylist>>(hotPlaylistKey, 15 * 60 * 1000L)?.let {
            hotPlaylists = it
            hotPlaylistsLoading = false
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

    // 与输入、分类和来源绑定，切换后马上移除旧联想。
    LaunchedEffect(suggestionText, selectedPlatform, category) {
        if (suggestionText.isEmpty()) return@LaunchedEffect
        delay(280)
        suggestions = runCatchingCancellable {
            searchSuggestions(context, category, selectedPlatform, suggestionText) { suggestions = it }
        }.getOrDefault(emptyList())
    }

    fun submit(keyword: String) {
        val text = keyword.trim()
        if (text.isEmpty()) return
        UserLibrary.addSearchKeyword(text)
        if (queryState.text.toString() != text) queryState.setTextAndPlaceCursorAtEnd(text)
        if (submitted == text) request = SearchPageRequest(1) else submitted = text
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    DetailPageHost(
        target = openedPlaylist,
        modifier = modifier.fillMaxSize(),
        contentKey = { playlist -> "${playlist.source}_${playlist.id}_${playlist.isBookAlbum}" },
        detail = { playlist ->
            PlaylistDetailContent(
                playlist = playlist,
                onBack = { openedPlaylist = null },
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            )
        },
        content = {
            ChromeScaffold(
                modifier = Modifier.fillMaxSize(),
                expectedTopBarHeight = searchExpectedTopBarHeight(suggestions.isNotEmpty()),
                topBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(chromeHeaderColor()),
                    ) {
                        SearchTopBar(
                            navigationIcon = navigationIcon,
                            selectedCategory = category,
                            onCategoryChange = onCategoryChange,
                            onTitleClick = scrollToTop,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = SearchInputContainerBottomPadding),
                        ) {
                            SearchInputSection(
                                category = category,
                                queryState = queryState,
                                selectedPlatform = selectedPlatform,
                                platformMenuExpanded = platformMenuExpanded,
                                suggestions = suggestions,
                                onSubmit = { submit(it) },
                                onClear = {
                                    queryState.clearText()
                                    submitted = ""
                                },
                                onPlatformMenuExpandedChange = { platformMenuExpanded = it },
                                onPlatformChange = { platform ->
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
                            listState = idleListState,
                            category = category,
                            selectedPlatform = selectedPlatform,
                            searchHistory = searchHistory,
                            hotWords = hotWords,
                            hotLoading = hotLoading,
                            hotPlaylists = hotPlaylists,
                            hotPlaylistsLoading = hotPlaylistsLoading,
                            recommendSongs = recommendSongs,
                            recommendLoading = recommendLoading,
                            recommendedCollections = if (category == SearchCategory.Audiobook) recommendBooks else recommendPlaylists,
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
                            listState = resultListState,
                            category = category,
                            submitted = submitted,
                            selectedPlatform = selectedPlatform,
                            results = results,
                            loading = request != null && request?.error == null,
                            error = request?.error,
                            favoriteUids = favoriteUids,
                            onLoadPage = {
                                val pending = request
                                if (pending?.error != null) request = pending.copy(error = null)
                                else if (pending == null && results.hasMore) request = SearchPageRequest(results.page + 1)
                            },
                            onOpenPlaylist = { openedPlaylist = it },
                            onMoreSong = { moreSong = it },
                            onPlaySong = { song ->
                                PlaybackController.playTrack(context, UiTrack.fromOnline(song))
                            },
                        )
                    }
                }
            }
        },
    )

    moreSong?.let { song -> SongMoreSheet(song) { moreSong = null } }
}
