package com.leyu.melora.playback

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.leyu.melora.playback.sdk.OnlineSong

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
            newPosition.mediaItem?.let { persist(it, newPosition.positionMs, knownDuration(it)) }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        pendingUid = mediaItem?.mediaId?.takeIf {
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && it != explicitStartUid
        }
        explicitStartUid = null
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val item = player.currentMediaItem ?: return
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
        if (position == 0L && !finished) return
        val duration = player.duration.takeIf { it > 0 } ?: knownDuration(item)
        if (duration > 0) {
            durationUid = item.mediaId
            durationMs = duration
        }
        persist(item, position, duration, finished)
    }

    private fun knownDuration(item: MediaItem): Long =
        durationMs.takeIf { durationUid == item.mediaId && it > 0 }
            ?: prefs.getLong("duration:${item.mediaId}", 0L).takeIf { it > 0 }
            ?: ((OnlineSong.from(trackFor(item.mediaId)?.raw)?.intervalSeconds ?: 0) * 1000L)

    private fun persist(item: MediaItem, position: Long, duration: Long, finished: Boolean = false) {
        if (!isEnabled() || item.mediaId.isBlank()) return
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
    if (savedMs <= 5_000L) return false
    if (durationMs > 0 && savedMs >= durationMs - nearEndMs) return false
    return isBookChapter || durationMs >= longTrackMs
}

internal fun persistedProgressMs(positionMs: Long, durationMs: Long, nearEndMs: Long = 2_000L): Long =
    if (durationMs > 0 && positionMs >= durationMs - nearEndMs) 0L else positionMs
