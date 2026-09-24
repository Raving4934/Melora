package com.leyu.melora.playback.sdk

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PlaylistImportProgress(val page: Int, val loaded: Int, val total: Int?)

internal data class PlaylistImportResult(
    val platform: String,
    val name: String,
    val cover: String?,
    val songs: List<OnlineSong>,
    val reportedTotal: Int?,
    val duplicates: Int,
    val warning: String?,
)

/** 只读取元数据，确认保存前不创建歌单，不解析播放地址。 */
internal object PlaylistImporter {
    internal const val MAX_SONGS = 10_000
    private const val MAX_PAGES = 500

    suspend fun load(
        context: Context,
        text: String,
        onProgress: (PlaylistImportProgress) -> Unit,
    ): PlaylistImportResult = withContext(Dispatchers.IO) {
        val link = PlaylistImportLink.parse(text)
        read(link, onProgress) { page ->
            OnlineRepository.playlistSongs(context, link.source, link.value, page, background = true)
        }
    }

    internal suspend fun read(
        link: PlaylistImportLink,
        onProgress: (PlaylistImportProgress) -> Unit = {},
        fetch: suspend (Int) -> SongPage,
    ): PlaylistImportResult {
        val songs = linkedMapOf<String, OnlineSong>()
        var name: String? = null
        var cover: String? = null
        var total = 0
        var received = 0
        var unavailable = 0
        var duplicates = 0
        var warning: String? = null
        for (page in 1..MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            onProgress(PlaylistImportProgress(page, songs.size, total.takeIf { it > 0 }))
            val result = try {
                fetch(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (songs.isEmpty()) throw error
                warning = "第 ${page} 页读取失败，尚未获取完整歌单。${error.message.orEmpty().take(120)}"
                break
            }
            currentCoroutineContext().ensureActive()
            name = name ?: result.playlistName?.takeIf(String::isNotBlank)
            cover = cover ?: result.playlistCover
            total = maxOf(total, result.total)
            val previousSize = songs.size
            for (song in result.list) {
                if (song.uid in songs) duplicates++
                else if (songs.size < MAX_SONGS) songs[song.uid] = song
                else { warning = "单次最多读取 $MAX_SONGS 首，尚未获取完整歌单。"; break }
            }
            received += result.rawCount
            unavailable += (result.rawCount - result.list.size).coerceAtLeast(0)
            onProgress(PlaylistImportProgress(page, songs.size, total.takeIf { it > 0 }))
            if (warning != null) break
            if (result.rawCount == 0) {
                if (total > received || result.allPage > page) warning = "平台提前返回空页，尚未获取完整歌单。"
                break
            }
            if (page > 1 && result.list.isNotEmpty() && songs.size == previousSize) {
                warning = "平台返回了重复页面，已停止继续读取，尚未确认完整歌单。"
                break
            }
            val hasMore = when {
                result.allPage > 0 -> page < result.allPage
                total > 0 -> received < total
                else -> result.rawCount >= result.pageSize
            }
            if (!hasMore) {
                if (total > received) warning = "平台声明 $total 首，但只返回了 $received 条歌曲信息。"
                break
            }
            if (songs.size >= MAX_SONGS || page == MAX_PAGES) {
                warning = "已达到单次读取上限，尚未获取完整歌单。"
                break
            }
        }
        currentCoroutineContext().ensureActive()
        check(songs.isNotEmpty()) { "未获取到歌曲。歌单可能为空、私密，或当前无法访问。" }
        if (unavailable > 0) {
            warning = listOfNotNull(warning, "有 $unavailable 条歌曲信息不可用，未包含在结果中。").joinToString("\n")
        }
        return PlaylistImportResult(
            link.platformName, name ?: "${link.platformName}导入歌单", cover,
            songs.values.toList(), total.takeIf { it > 0 }, duplicates, warning,
        )
    }
}
