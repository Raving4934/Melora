package com.leyu.melora.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 章节坐标随曲目保存，裁剪持久化队列后仍从窗口尾章继续，而不是跳过未保存的章节。 */
internal fun OnlineSong.bookId(): String? = albumId
    .removePrefix("kw:").removePrefix("book_album_")
    .takeIf { source == "kw" && isBookChapter && it.isNotBlank() }

internal fun bookQueueId(id: String): String = "playlist.kw.book_album_$id"

/** 旧记录没有页码时才逐页定位；有坐标时最多请求当前页余下章节或下一页。 */
internal suspend fun followingBookChapters(
    tail: OnlineSong,
    load: suspend (String, Int) -> KwBookApi.BookChapters,
): List<OnlineSong> {
    val id = checkNotNull(tail.bookId())
    val knownPage = tail.raw.optInt("bookPage", 0)
    val lastInPage = tail.raw.optBoolean("bookPageEnd")
    if (knownPage > 0 && lastInPage && !tail.raw.optBoolean("bookHasMore")) return emptyList()
    var page = if (knownPage > 0) knownPage + if (lastInPage) 1 else 0 else 1
    var located = knownPage > 0 && lastInPage
    val visited = mutableSetOf<String>()
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = load(id, page)
        check(result.items.isNotEmpty()) { "后续章节加载失败，请点击播放或下一集重试" }
        check(result.items.all { it.bookId() == id }) { "章节目录不匹配，请重试" }
        check(visited.add(result.items.last().uid)) { "章节目录重复，请重新打开目录后重试" }
        val index = if (located) -1 else result.items.indexOfFirst { it.uid == tail.uid }
        if (located || index >= 0) {
            val following = result.items.drop(index + 1)
            if (following.isNotEmpty()) return following
            if (!result.hasMore) return emptyList()
            located = true
        } else {
            check(knownPage <= 0 && result.hasMore) { "未找到当前章节，请重新打开目录选择章节" }
        }
        page++
    }
}

/** 仅完整属于同一专辑的章节队列才采用听书规则；缺少登记信息或混合曲目不能猜测。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun Player.bookAlbumId(trackFor: (String) -> UiTrack?): String? {
    val albumId = currentMediaItem?.mediaId?.let(trackFor)?.raw?.let(OnlineSong::from)?.bookId() ?: return null
    return albumId.takeIf { id -> (0 until mediaItemCount).all { index ->
        trackFor(getMediaItemAt(index).mediaId)?.raw?.let(OnlineSong::from)?.bookId() == id
    } }
}

/** 仅管理明确选择的整书队列；目录请求不阻塞当前章节开播，也不依赖详情页面存活。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class BookPlaybackQueue(
    private val player: Player,
    private val scope: CoroutineScope,
    private val trackFor: (String) -> UiTrack?,
    private val itemFor: (UiTrack) -> MediaItem,
    private val loadPage: suspend (String, Int) -> KwBookApi.BookChapters,
    private val changed: () -> Unit,
    private val reportError: (String) -> Unit,
) {
    var albumId: String? = null
        private set
    private var tail: OnlineSong? = null
    private var job: Job? = null
    private var failed = false
    private var exhausted = false
    private var advanceFrom: String? = null
    private var musicRepeat = Player.REPEAT_MODE_ALL
    private var musicShuffle = false

    fun applyMusicMode(mode: PlayMode) {
        if (albumId == null) {
            player.applyPlayMode(mode)
        } else {
            musicRepeat = mode.repeat
            musicShuffle = mode.shuffled
        }
    }

    fun start(id: String) {
        stop()
        albumId = id
        musicRepeat = player.repeatMode
        musicShuffle = player.shuffleModeEnabled
        tail = player.takeIf { it.mediaItemCount > 0 }
            ?.getMediaItemAt(player.mediaItemCount - 1)?.mediaId
            ?.let(trackFor)?.raw?.let(OnlineSong::from)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        check()
    }

    fun stop() {
        job?.cancel()
        job = null
        val wasActive = albumId != null
        albumId = null
        tail = null
        failed = false
        exhausted = false
        advanceFrom = null
        if (wasActive) {
            player.repeatMode = musicRepeat
            player.shuffleModeEnabled = musicShuffle
        }
    }

    fun retry() {
        failed = false
        check()
    }

    /** 末尾目录尚未回来时保留“下一集”意图，不从当前章节开头重播。 */
    fun next(): Boolean {
        if (albumId == null || player.hasNextMediaItem()) return false
        if (!exhausted) {
            advanceFrom = player.currentMediaItem?.mediaId
            retry()
        }
        return true
    }

    fun check() {
        val id = albumId ?: return
        if (player.mediaItemCount == 0) {
            stop()
            return
        }
        val current = player.currentMediaItem?.mediaId
        if (advanceFrom != null && advanceFrom != current) advanceFrom = null
        if (player.playWhenReady && player.hasNextMediaItem() &&
            (player.playbackState == Player.STATE_ENDED || advanceFrom == current)
        ) {
            // MediaController追加目录后，“下一集”命令可用性会晚于本地时间线同步；
            // 已知是顺序队列，直接定位真实下标，避免请求被静默忽略。
            player.seekToDefaultPosition(player.currentMediaItemIndex + 1)
            advanceFrom = null
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
        if (failed || exhausted || job != null || player.mediaItemCount - player.currentMediaItemIndex > 4) return
        val from = tail ?: return
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val following = withContext(Dispatchers.IO) { followingBookChapters(from, loadPage) }
                if (job !== currentCoroutineContext()[Job] || albumId != id) return@launch
                if (following.isEmpty()) {
                    exhausted = true
                    advanceFrom = null
                    return@launch
                }
                val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                val additions = following.distinctBy(OnlineSong::uid).filterNot { it.uid in existing }
                check(additions.isNotEmpty()) { "章节目录重复，请重新打开目录后重试" }
                tail = following.last()
                val tracks = additions.map(UiTrack::fromOnline)
                TrackRegistry.registerAll(tracks)
                player.addMediaItems(tracks.map(itemFor))
                changed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (job === currentCoroutineContext()[Job]) {
                    failed = true
                    reportError(error.message ?: "后续章节加载失败，请重试")
                }
            } finally {
                if (job === currentCoroutineContext()[Job]) {
                    job = null
                    // 若这一页只有少量章节，继续补到安全余量；失败不自动循环请求。
                    check()
                }
            }
        }
        job?.start()
    }
}
