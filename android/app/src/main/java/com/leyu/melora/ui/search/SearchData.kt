package com.leyu.melora.ui.search

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.common.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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

internal data class SongSearchOutcome(val songs: List<OnlineSong>, val hasMore: Boolean)
internal data class PlaylistSearchOutcome(val playlists: List<OnlinePlaylist>, val hasMore: Boolean)

internal val platformIds = listOf("kw", "kg", "wy", "tx", "mg")
private const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L

internal fun songSearchCacheKey(source: String, keyword: String, page: Int): String =
    "search:songs:$source:${keyword.trim().lowercase(Locale.ROOT)}:$page"

private suspend fun cachedSongSearch(
    context: android.content.Context,
    source: String,
    keyword: String,
    page: Int,
    timeoutMs: Long = 25_000L,
): SongPage = OnlineCache.refresh(songSearchCacheKey(source, keyword, page), SEARCH_CACHE_TTL_MS) {
    OnlineRepository.search(context, source, keyword, page, 30, timeoutMs = timeoutMs)
}

internal suspend fun searchSongsProgressive(
    context: android.content.Context,
    platform: PlatformSource,
    keyword: String,
    page: Int,
    onBatch: (List<OnlineSong>, Boolean) -> Unit,
) = supervisorScope {
    val targets = if (platform == PlatformSource.All) platformIds else listOf(platform.id)
    val jobs = targets.map { source ->
        launch {
            val key = songSearchCacheKey(source, keyword, page)
            val snapshot = OnlineCache.peek<SongPage>(key)
            if (!snapshot?.list.isNullOrEmpty()) {
                onBatch(snapshot.list, snapshot.list.size >= 30)
            }
            if (OnlineCache.get<SongPage>(key, SEARCH_CACHE_TTL_MS) != null) return@launch
            val fresh = recoverSearch { cachedSongSearch(context, source, keyword, page, timeoutMs = 6_000L) }
            if (fresh != null && fresh.list.isNotEmpty() && fresh.list.map(OnlineSong::uid) != snapshot?.list?.map(OnlineSong::uid)) {
                onBatch(fresh.list, fresh.list.size >= 30)
            }
        }
    }
    jobs.joinAll()
}

internal suspend fun searchSongs(
    context: android.content.Context,
    platform: PlatformSource,
    keyword: String,
    page: Int,
): SongSearchOutcome = supervisorScope {
    val targets = if (platform == PlatformSource.All) platformIds else listOf(platform.id)
    val pages = targets.map { source ->
        async { recoverSearch { cachedSongSearch(context, source, keyword, page) } }
    }.awaitAll().filterNotNull()
    SongSearchOutcome(
        songs = pages.flatMap { it.list }.distinctBy { "${it.name}|${it.singer}" },
        hasMore = pages.any { it.list.size >= 30 },
    )
}

internal suspend fun searchPlaylists(
    context: android.content.Context,
    platform: PlatformSource,
    keyword: String,
    page: Int,
): PlaylistSearchOutcome = supervisorScope {
    val targets = if (platform == PlatformSource.All) platformIds else listOf(platform.id)
    val pages = targets.map { source ->
        async { recoverSearch { OnlineRepository.songlistSearch(context, source, keyword, page) } }
    }.awaitAll().filterNotNull()
    PlaylistSearchOutcome(
        playlists = pages.flatMap { it.list }.distinctBy { "${it.source}_${it.id}" },
        hasMore = pages.any { it.list.isNotEmpty() },
    )
}

internal suspend fun <T> recoverSearch(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}
