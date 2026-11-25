package com.leyu.melora.ui.search

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.PLAYLIST_SEARCH_PAGE_SIZE
import com.leyu.melora.playback.sdk.PlaylistPage
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.common.runCatchingCancellable
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.Locale


// 搜索分类：歌曲、歌单、听书
enum class SearchCategory(val label: String) {
    Song("歌曲"),
    Playlist("歌单"),
    Audiobook("听书"),
}

// 平台筛选：酷狗、酷我、网易、企鹅、咪咕、聚合
enum class PlatformSource(val id: String, val label: String, val color: Color) {
    Kuwo("kw", "酷我", Color(0xFFFFB300)),
    Kugou("kg", "酷狗", Color(0xFF0091EA)),
    Netease("wy", "网易", Color(0xFFE53935)),
    Tencent("tx", "企鹅", Color(0xFF00C853)),
    Migu("mg", "咪咕", Color(0xFF8E24AA)),
    All("all", "聚合", Color(0xFF2E7D32)),
}

// 听书场景关键词兜底（真词优先取酷我有声小说子榜名）
private val audiobookKeywords = listOf("有声书", "广播剧", "相声", "评书", "助眠", "儿童故事")

internal suspend fun audiobookScenarioWords(): List<String> {
    val tabs = runCatchingCancellable { KwBookApi.ranks() }.getOrNull().orEmpty()
    val tab = tabs.firstOrNull { it.name.contains("有声小说") } ?: tabs.firstOrNull()
    val words = tab?.tags.orEmpty()
        .map { it.name.removeSuffix("榜") }
        .filter { it.isNotBlank() && it != "总榜" }
    return if (words.isNotEmpty()) words.take(6) else audiobookKeywords
}

internal data class SearchResults(
    val page: Int = 0,
    val songs: List<OnlineSong> = emptyList(),
    val playlists: List<OnlinePlaylist> = emptyList(),
    val hasMore: Boolean = false,
    val nextSourcePages: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = songs.isEmpty() && playlists.isEmpty()

    fun withPage(next: SearchResults): SearchResults = if (next.page == 1) next else next.copy(
        songs = (songs + next.songs).distinctBy(OnlineSong::uid),
        playlists = (playlists + next.playlists).distinctBy { it.source to it.id },
    )
}

internal val platformIds = PlatformSource.entries.filter { it != PlatformSource.All }.map(PlatformSource::id)
private const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L
private const val SEARCH_PREVIEW_TTL_MS = 60_000L
private const val SUGGESTION_LIMIT = 8

internal val PlatformSource.searchSources: List<String>
    get() = if (this == PlatformSource.All) platformIds else listOf(id)

internal fun songSearchCacheKey(source: String, keyword: String, page: Int): String =
    "search:songs:$source:${keyword.trim().lowercase(Locale.ROOT)}:$page"

private suspend fun cachedSongSearch(
    context: Context,
    source: String,
    keyword: String,
    page: Int,
    timeoutMs: Long = 25_000L,
): SongPage = OnlineCache.refresh(
    songSearchCacheKey(source, keyword, page), SEARCH_CACHE_TTL_MS, cancelWhenUnobserved = true,
) {
    OnlineRepository.search(context, source, keyword, page, 30, timeoutMs = timeoutMs)
}

/** 仅成功的来源前进，失败来源保留本页。首页与续页使用相同聚合逻辑。 */
internal suspend fun searchResultPage(
    context: Context,
    category: SearchCategory,
    platform: PlatformSource,
    keyword: String,
    page: Int,
    onUpdate: ((SearchResults) -> Unit)? = null,
    sourcePages: Map<String, Int>? = null,
): SearchResults {
    val sources = if (category == SearchCategory.Audiobook) listOf(PlatformSource.Kuwo.id) else platform.searchSources
    val requested = sourcePages ?: sources.associateWith { page }
    val remaining = requested.toMutableMap()
    val available = linkedMapOf<String, SearchResults>()
    val failures = mutableMapOf<String, Throwable>()

    fun outcome(): SearchResults {
        val pages = if (onUpdate != null) available.values else requested.keys.mapNotNull(available::get)
        val uids = hashSetOf<String>()
        val titlesAndSingers = hashSetOf<Pair<String, String>>()
        val songs = buildList {
            for (song in pages.asSequence().flatMap { it.songs }) {
                // 只有进入结果的歌曲才占用两个键，跳过的同名记录仍可能有可展示的别名。
                if (song.uid in uids || !titlesAndSingers.add(song.name to song.singer)) continue
                uids.add(song.uid)
                add(song)
            }
        }
        return SearchResults(
            page = page,
            songs = songs,
            playlists = pages.flatMap { it.playlists }.distinctBy { it.source to it.id },
            hasMore = remaining.isNotEmpty(),
            nextSourcePages = remaining.toMap(),
        )
    }

    searchSourcePages(context, category, keyword, requested, previewSongs = onUpdate != null)
        .collect { (source, result, preview) ->
            result.onSuccess { response ->
                if (!preview) {
                    if (response.hasMore) remaining[source] = requested.getValue(source) + 1 else remaining.remove(source)
                }
                if (available.put(source, response) != response) onUpdate?.invoke(outcome())
            }.onFailure { failures[source] = it }
        }
    if (available.isEmpty()) requested.keys.firstNotNullOfOrNull(failures::get)?.let { throw it }
    return outcome()
}

/** 输入联想与正式搜索共用目录首页；继续翻页时直接读取音源。 */
private suspend fun <T : Any> cachedCatalogPage(
    catalog: String, keyword: String, page: Int, load: suspend () -> T,
): T = if (page == 1) OnlineCache.refresh(
    "search:$catalog:${keyword.trim().lowercase(Locale.ROOT)}:1", SEARCH_PREVIEW_TTL_MS,
    cancelWhenUnobserved = true, load = load,
) else load()

internal suspend fun searchSuggestions(
    context: Context,
    category: SearchCategory,
    platform: PlatformSource,
    keyword: String,
    onUpdate: ((List<String>) -> Unit)? = null,
): List<String> {
    if (keyword.isBlank()) return emptyList()
    val labels = when (category) {
        SearchCategory.Song -> {
            val source = if (platform == PlatformSource.All) PlatformSource.Kuwo.id else platform.id
            OnlineCache.refresh(
                "search:tips:$source:${keyword.trim().lowercase(Locale.ROOT)}", SEARCH_PREVIEW_TTL_MS,
                cancelWhenUnobserved = true,
            ) { OnlineRepository.tipSearch(context, source, keyword) }.asSequence()
        }
        SearchCategory.Audiobook -> searchResultPage(context, category, platform, keyword, 1)
            .playlists.asSequence().map(OnlinePlaylist::name)
        SearchCategory.Playlist -> {
            // 聚合时按返回顺序追加名称，慢源不会拖住首批联想或挪动已显示的行。
            var titles = emptyList<String>()
            val requested = platform.searchSources.associateWith { 1 }
            searchSourcePages(context, category, keyword, requested).firstOrNull { (_, result) ->
                val items = result.getOrNull()?.playlists.orEmpty()
                val next = (titles.asSequence() + items.asSequence().map(OnlinePlaylist::name)).suggestionLabels()
                if (next.size > titles.size) {
                    titles = next
                    onUpdate?.invoke(titles)
                }
                titles.size == SUGGESTION_LIMIT
            }
            return titles
        }
    }
    return labels.suggestionLabels()
}

private fun Sequence<String>.suggestionLabels(): List<String> =
    map(String::trim).filter(String::isNotEmpty).distinct().take(SUGGESTION_LIMIT).toList()

private data class SourceSearchPage(
    val source: String,
    val result: Result<SearchResults>,
    val cached: Boolean = false,
)

private fun SongPage.toSearchPage(cursor: Int) = SearchResults(
    page = cursor,
    songs = entries,
    hasMore = copy(page = cursor).hasMore(loadedCount = (cursor - 1) * pageSize + rawCount),
)

private fun searchSourcePages(
    context: Context,
    category: SearchCategory,
    keyword: String,
    requested: Map<String, Int>,
    previewSongs: Boolean = false,
) = channelFlow {
    for ((source, cursor) in requested) launch {
        if (category == SearchCategory.Song && previewSongs) {
            OnlineCache.peek<SongPage>(songSearchCacheKey(source, keyword, cursor))?.let {
                send(SourceSearchPage(source, Result.success(it.toSearchPage(cursor)), cached = true))
            }
        }
        val result = runCatchingCancellable {
            when (category) {
                SearchCategory.Song -> cachedSongSearch(
                    context, source, keyword, cursor, timeoutMs = if (previewSongs) 6_000L else 25_000L,
                ).toSearchPage(cursor)
                SearchCategory.Playlist -> cachedCatalogPage("playlists:$source", keyword, cursor) {
                    OnlineRepository.songlistSearch(context, source, keyword, cursor)
                }.let { page ->
                    SearchResults(cursor, playlists = page.list, hasMore = page.hasMoreSearchResults(cursor))
                }
                SearchCategory.Audiobook -> cachedCatalogPage("books:kw", keyword, cursor) {
                    KwBookApi.search(keyword, cursor)
                }.let { page -> SearchResults(cursor, playlists = page.items, hasMore = page.hasMore) }
            }
        }
        send(SourceSearchPage(source, result))
    }
}

internal fun PlaylistPage.hasMoreSearchResults(requestedPage: Int): Boolean =
    list.isNotEmpty() && (total <= 0 || requestedPage.toLong() * PLAYLIST_SEARCH_PAGE_SIZE < total)
