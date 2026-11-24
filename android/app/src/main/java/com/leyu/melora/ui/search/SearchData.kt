package com.leyu.melora.ui.search

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.common.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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
) {
    val isEmpty: Boolean get() = songs.isEmpty() && playlists.isEmpty()

    fun withPage(next: SearchResults): SearchResults = if (next.page == 1) next else next.copy(
        songs = (songs + next.songs).distinctBy(OnlineSong::uid),
        playlists = (playlists + next.playlists).distinctBy { it.source to it.id },
    )
}

internal val platformIds = PlatformSource.entries.filter { it != PlatformSource.All }.map(PlatformSource::id)
private const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L

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

internal suspend fun searchResultPage(
    context: Context,
    category: SearchCategory,
    platform: PlatformSource,
    keyword: String,
    page: Int,
    onUpdate: ((SearchResults) -> Unit)? = null,
): SearchResults = when (category) {
    SearchCategory.Song -> searchSongs(context, platform, keyword, page, onUpdate)
    SearchCategory.Playlist -> searchPlaylists(context, platform, keyword, page)
    SearchCategory.Audiobook -> KwBookApi.search(keyword, page).let {
        SearchResults(page, playlists = it.items, hasMore = it.hasMore)
    }
}

/** 首屏渐进返回与后续分页共用一条聚合链路；同来源刷新替换快照，不追加过时歌曲。 */
private suspend fun searchSongs(
    context: Context,
    platform: PlatformSource,
    keyword: String,
    page: Int,
    onUpdate: ((SearchResults) -> Unit)?,
): SearchResults = coroutineScope {
    val targets = if (platform == PlatformSource.All) platformIds else listOf(platform.id)
    val pages = linkedMapOf<String, SongPage>()
    fun outcome(): SearchResults {
        val available = if (onUpdate == null) targets.mapNotNull(pages::get) else pages.values
        val songs = buildList {
            val uids = hashSetOf<String>()
            val titlesAndSingers = hashSetOf<String>()
            for (song in available.asSequence().flatMap { it.entries }) {
                val uid = song.uid
                // 只有进入结果的歌曲才占用两个键，跳过的同名记录仍可能有可展示的别名。
                if (uid in uids || !titlesAndSingers.add("${song.name}|${song.singer}")) continue
                uids.add(uid)
                add(song)
            }
        }
        return SearchResults(
            page = page,
            songs = songs,
            hasMore = available.any { it.hasMore(loadedCount = (page - 1) * it.pageSize + it.rawCount) },
        )
    }
    fun accept(source: String, result: SongPage) {
        // 页码由请求决定；部分平台不返回page，不能让后续页被误认为第一页。
        pages[source] = result.copy(page = page)
        onUpdate?.invoke(outcome())
    }
    val results = targets.map { source ->
        async {
            if (onUpdate != null) {
                OnlineCache.peek<SongPage>(songSearchCacheKey(source, keyword, page))?.let { accept(source, it) }
            }
            runCatchingCancellable {
                cachedSongSearch(context, source, keyword, page, timeoutMs = if (onUpdate == null) 25_000L else 6_000L)
            }.onSuccess { fresh ->
                ensureActive()
                if (fresh != pages[source]) accept(source, fresh)
            }
        }
    }.awaitAll()
    // 缓存预览或任一来源成功时保留可用结果；全部失败要暴露真实错误，不能推进空页游标。
    if (pages.isEmpty()) results.first().getOrThrow()
    outcome()
}

private suspend fun searchPlaylists(
    context: Context,
    platform: PlatformSource,
    keyword: String,
    page: Int,
): SearchResults = coroutineScope {
    val targets = if (platform == PlatformSource.All) platformIds else listOf(platform.id)
    val results = targets.map { source ->
        async { runCatchingCancellable { OnlineRepository.songlistSearch(context, source, keyword, page) } }
    }.awaitAll()
    val pages = results.mapNotNull { it.getOrNull() }
    if (pages.isEmpty()) results.first().getOrThrow()
    SearchResults(
        page = page,
        playlists = pages.flatMap { it.list }.distinctBy { it.source to it.id },
        hasMore = pages.any { it.list.isNotEmpty() },
    )
}
