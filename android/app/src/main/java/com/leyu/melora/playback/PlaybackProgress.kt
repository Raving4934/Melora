package com.leyu.melora.playback

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.leyu.melora.playback.sdk.OnlineSong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 进度只由服务内的实际播放器读写。所有控制入口共享Media3事件，不使用UI轮询快照。
 * UID键沿用原有Long记录；额外保存实际时长，目录缺少时长时也能在冷启动前判断长音频。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackProgress(
    private val player: Player,
    private val prefs: SharedPreferences,
    private val trackFor: (String) -> UiTrack? = TrackRegistry::get,
    private val isEnabled: () -> Boolean = { MeloraSettings.rememberProgress.value },
) : Player.Listener {
    private var pendingUid: String? = null
    private var explicitStartUid: String? = null
    private var durationUid: String? = null
    private var durationMs = 0L

    init { player.addListener(this) }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val changedWindow = oldPosition.windowUid != newPosition.windowUid
        val repeated = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
            newPosition.positionMs < oldPosition.positionMs
        if (changedWindow || repeated) {
            // 回调保留的旧曲身份/位置才是离场值；此时player.currentPosition已经属于新曲。
            oldPosition.mediaItem?.let { item ->
                if (oldPosition.positionMs > 0 || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    persist(item, oldPosition.positionMs, knownDuration(item),
                        finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                }
            }
            explicitStartUid = newPosition.mediaItem?.mediaId?.takeIf {
                reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION && newPosition.positionMs > 0
            }
        } else if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            // 包括拖动到0秒；显式seek优先于尚未获得时长的历史恢复。
            pendingUid = null
            newPosition.mediaItem?.let { item ->
                val duration = knownDuration(item)
                persist(item, newPosition.positionMs, duration)
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItem?.let(::selectBook)
        pendingUid = mediaItem?.mediaId?.takeIf {
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && it != explicitStartUid
        }
        explicitStartUid = null
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val item = player.currentMediaItem ?: return
        selectBook(item)
        if (player.duration > 0) {
            durationUid = item.mediaId
            durationMs = player.duration
        }
        restore(item)
        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
            events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
        ) checkpoint()
        explicitStartUid = null
    }

    private fun restore(item: MediaItem) {
        if (pendingUid != item.mediaId) return
        if (!isEnabled()) {
            pendingUid = null
            return
        }
        val saved = prefs.getLong(item.mediaId, 0L)
        val duration = knownDuration(item)
        val book = OnlineSong.from(trackFor(item.mediaId)?.raw)?.isBookChapter == true
        // 未知目录时长先等实际时间线，不先覆盖断点，也不对每次READY/缓冲重复seek。
        if (saved > 5_000L && !book && duration <= 0 && player.playbackState != Player.STATE_ENDED) return
        pendingUid = null
        if (player.playbackState != Player.STATE_ENDED &&
            shouldRestoreProgress(book, duration, saved) &&
            (player.playbackState != Player.STATE_READY || player.isCurrentMediaItemSeekable)
        ) player.seekTo(saved)
    }

    /** 服务每5秒与暂停/结束等关键事件调用；相同记录不重复写盘，不叠加第二层节流。 */
    fun checkpoint() {
        if (!isEnabled()) return
        val item = player.currentMediaItem ?: return
        if (pendingUid == item.mediaId) return
        val position = player.currentPosition.coerceAtLeast(0L)
        val finished = player.playbackState == Player.STATE_ENDED
        // 仅装载到0秒并不代表重新收听；明确从头播放/seek由对应入口清除完成标记。
        if (position == 0L && !finished) return
        val duration = player.duration.takeIf { it > 0 } ?: knownDuration(item)
        if (duration > 0) {
            durationUid = item.mediaId
            durationMs = duration
        }
        persist(item, position, duration, finished)
    }

    private fun selectBook(item: MediaItem) {
        if (!isEnabled()) return
        bookSong(item)?.let { BookListeningProgress.select(prefs, it) }
    }

    /** 只有 raw UID 与真实 Player mediaId 一致时，才允许进入按书索引/完成标记路径。 */
    private fun bookSong(item: MediaItem): OnlineSong? =
        OnlineSong.from(trackFor(item.mediaId)?.raw)
            ?.takeIf { it.uid == item.mediaId && it.isBookChapter }

    private fun knownDuration(item: MediaItem): Long =
        durationMs.takeIf { durationUid == item.mediaId && it > 0 }
            ?: prefs.getLong("duration:${item.mediaId}", 0L).takeIf { it > 0 }
            ?: ((OnlineSong.from(trackFor(item.mediaId)?.raw)?.intervalSeconds ?: 0) * 1000L)

    private fun persist(item: MediaItem, position: Long, duration: Long, finished: Boolean = false) {
        if (!isEnabled() || item.mediaId.isBlank()) return
        val song = bookSong(item)
        if (song != null) {
            BookListeningProgress.persist(prefs, song, position, duration, finished)
            return
        }
        val value = if (finished) 0L else persistedProgressMs(position.coerceAtLeast(0L), duration)
        val durationKey = "duration:${item.mediaId}"
        if (prefs.getLong(item.mediaId, 0L) == value &&
            (duration <= 0 || prefs.getLong(durationKey, 0L) == duration)
        ) return
        prefs.edit {
            if (value > 0) putLong(item.mediaId, value) else remove(item.mediaId)
            if (duration > 0) putLong(durationKey, duration)
        }
    }

    fun close() {
        checkpoint()
        player.removeListener(this)
    }
}

internal fun shouldRestoreProgress(
    isBookChapter: Boolean,
    durationMs: Long,
    savedMs: Long,
    nearEndMs: Long = 10_000L,
    longTrackMs: Long = 10 * 60 * 1000L,
): Boolean {
    if (isBookChapter) return savedMs > 0L && (durationMs <= 0L || savedMs < durationMs)
    if (savedMs <= 5_000L) return false
    if (durationMs > 0 && savedMs >= durationMs - nearEndMs) return false
    return durationMs >= longTrackMs
}

internal fun persistedProgressMs(positionMs: Long, durationMs: Long, nearEndMs: Long = 2_000L): Long =
    if (durationMs > 0 && positionMs >= durationMs - nearEndMs) 0L else positionMs

internal data class BookResumePoint(
    val song: OnlineSong,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
)

/** 以章节序号和本章断点计算整书进度；目录或断点信息不足时不显示百分比。 */
internal fun bookAlbumProgressPercent(point: BookResumePoint, total: Int = 0): Int? {
    val ordinal = point.song.raw.optInt("bookOrdinal", 0)
    val storedTotal = point.song.raw.optInt("bookTotal", 0)
    val bookTotal = storedTotal.takeIf { it > 0 } ?: total.takeIf { it > 0 } ?: return null
    if (ordinal !in 1..bookTotal) return null

    val chapterProgress = when {
        point.completed -> 1.0
        point.durationMs > 0L -> point.positionMs.coerceIn(0L, point.durationMs).toDouble() / point.durationMs
        else -> 0.0
    }
    return (((ordinal - 1 + chapterProgress) * 100.0) / bookTotal).toInt().coerceIn(0, 100)
}

/** 统一听书专辑 ID 归一化，调用方仍须将 source 与结果一起作为身份。 */
internal fun canonicalBookId(source: String, id: String): String? {
    val rawId = id.trim().takeIf(String::isNotBlank) ?: return null
    val canonical = if (source == "kw") {
        rawId.removePrefix("kw:").removePrefix("book_album_")
    } else {
        rawId
    }
    return canonical.takeIf(String::isNotBlank)
}

/** 按书保存最后章节元数据指针；每章位置仍只存放在原有 UID Long 键中。 */
internal object BookListeningProgress {
    private const val PREFS = "melora-progress"
    private const val BOOK_PREFIX = "book:"
    private const val COMPLETED_PREFIX = "completed:"
    private val lock = Any()
    private val mutableUpdates = MutableStateFlow(0L)

    val updates: StateFlow<Long> = mutableUpdates

    fun read(context: android.content.Context, fallback: OnlineSong): BookResumePoint =
        read(context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE), fallback)

    /** 仅读取已有的同平台、同专辑有效断点；无记录时不构造替代章节。 */
    fun readAlbum(context: android.content.Context, source: String, albumId: String): BookResumePoint? =
        readAlbum(context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE), source, albumId)

    internal fun readAlbum(prefs: SharedPreferences, source: String, albumId: String): BookResumePoint? = synchronized(lock) {
        if (!MeloraSettings.rememberProgress.value || source.isBlank()) return@synchronized null
        val id = canonicalBookId(source, albumId) ?: return@synchronized null
        val song = prefs.getString(bookKey(source, id), null)
            ?.let(::parseBookPointer)
            ?.takeIf { bookIdentity(it) == (source to id) }
            ?: return@synchronized null
        val saved = point(prefs, song)
        saved.takeIf {
            it.completed || (it.positionMs > 0L && (it.durationMs <= 0L || it.positionMs < it.durationMs))
        }
    }

    internal fun read(prefs: SharedPreferences, fallback: OnlineSong): BookResumePoint = synchronized(lock) {
        if (!MeloraSettings.rememberProgress.value) {
            return@synchronized BookResumePoint(
                song = fallback,
                positionMs = 0L,
                durationMs = fallback.intervalSeconds * 1_000L,
                completed = false,
            )
        }
        val identity = bookIdentity(fallback)
        val saved = identity?.let { (source, id) ->
            prefs.getString(bookKey(source, id), null)?.let(::parseBookPointer)
        }?.takeIf { bookIdentity(it) == identity }
        point(prefs, saved ?: fallback)
    }

    fun restart(context: android.content.Context, song: OnlineSong) =
        restart(context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE), song)

    internal fun restart(prefs: SharedPreferences, song: OnlineSong) = synchronized(lock) {
        write(prefs, song, positionMs = 0L, durationMs = 0L, completed = false, restart = true)
    }

    /** 切换章节时只移动每书元数据指针；UID断点和完成标记留给恢复/有效播放事件处理。 */
    internal fun select(prefs: SharedPreferences, song: OnlineSong) = synchronized(lock) {
        val identity = bookIdentity(song) ?: return@synchronized
        val key = bookKey(identity.first, identity.second)
        val pointer = pointerJson(song)
        if (prefs.getString(key, null) == pointer) return@synchronized
        prefs.edit { putString(key, pointer) }
        mutableUpdates.value += 1L
    }

    /** PlaybackProgress 的既有 checkpoint/seek/切轨/结束入口复用此单次 prefs 事务。 */
    internal fun persist(
        prefs: SharedPreferences,
        song: OnlineSong,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ) = synchronized(lock) {
        write(prefs, song, positionMs, durationMs, completed, restart = false)
    }

    private fun write(
        prefs: SharedPreferences,
        song: OnlineSong,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
        restart: Boolean,
    ) {
        val position = if (completed || restart) 0L else positionMs.coerceAtLeast(0L)
        val durationKey = "duration:${song.uid}"
        val completedKey = "$COMPLETED_PREFIX${song.uid}"
        val identity = bookIdentity(song)
        val bookKey = identity?.let { (source, id) -> bookKey(source, id) }
        val pointer = identity?.let { pointerJson(song) }
        val changed = prefs.getLong(song.uid, 0L) != position ||
            (durationMs > 0L && prefs.getLong(durationKey, 0L) != durationMs) ||
            prefs.getBoolean(completedKey, false) != completed ||
            (bookKey != null && prefs.getString(bookKey, null) != pointer)
        if (!changed) return
        prefs.edit {
            if (position > 0L) putLong(song.uid, position) else remove(song.uid)
            if (durationMs > 0L) putLong(durationKey, durationMs)
            if (completed) putBoolean(completedKey, true) else remove(completedKey)
            if (bookKey != null && pointer != null) putString(bookKey, pointer)
        }
        mutableUpdates.value += 1L
    }

    private fun point(prefs: SharedPreferences, song: OnlineSong): BookResumePoint {
        val completed = prefs.getBoolean("$COMPLETED_PREFIX${song.uid}", false)
        val duration = prefs.getLong("duration:${song.uid}", 0L)
            .takeIf { it > 0L } ?: song.intervalSeconds * 1_000L
        return BookResumePoint(
            song = song,
            positionMs = if (completed) 0L else prefs.getLong(song.uid, 0L).coerceAtLeast(0L),
            durationMs = duration,
            completed = completed,
        )
    }

    private fun pointerJson(song: OnlineSong): String = JSONObject()
        .put("uid", song.uid)
        .put("raw", JSONObject(song.raw.toString()))
        .toString()

    private fun parseBookPointer(value: String): OnlineSong? = runCatching {
        val record = JSONObject(value)
        OnlineSong.from(record.optJSONObject("raw"))?.takeIf { it.uid == record.optString("uid") }
    }.getOrNull()

    internal fun bookIdentity(song: OnlineSong): Pair<String, String>? {
        if (!song.isBookChapter) return null
        val id = canonicalBookId(song.source, song.albumId) ?: return null
        return song.source to id
    }

    private fun bookKey(source: String, bookId: String): String =
        "$BOOK_PREFIX${source.length}:$source:$bookId"
}
