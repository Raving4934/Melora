package com.leyu.melora.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl

/** 只观察已经出声后的重缓冲；不测速、不参与首播，不把暂停/拖动进度算作慢源。 */
internal class RebufferRecovery {
    enum class Action { NONE, YIELD_PREFETCH, FIND_ALTERNATIVE }

    private var uid: String? = null
    private var hasPlayed = false
    private var bufferStartedAt: Long? = null
    private var recoveryAttempted = false
    private var windowStartedAt = 0L
    private var stalls = 0
    private var waitedMs = 0L
    private var waitForPlayback = false

    fun reset(trackUid: String? = null) {
        uid = trackUid
        hasPlayed = false
        bufferStartedAt = null
        recoveryAttempted = false
        waitForPlayback = false
        stalls = 0
        waitedMs = 0L
    }

    fun attemptStarted() { recoveryAttempted = true }

    fun interrupted() {
        bufferStartedAt = null
        waitForPlayback = true
        stalls = 0
        waitedMs = 0L
    }

    fun update(
        trackUid: String?,
        isPlaying: Boolean,
        playWhenReady: Boolean,
        buffering: Boolean,
        networkResource: Boolean,
        allowSwitch: Boolean,
        nowMs: Long,
    ): Action {
        if (uid != trackUid) reset(trackUid)
        if (isPlaying) {
            hasPlayed = true
            waitForPlayback = false
        }
        if (trackUid == null || !networkResource || !playWhenReady) {
            interrupted()
            return Action.NONE
        }
        if (!buffering || !hasPlayed || waitForPlayback) {
            bufferStartedAt?.let { waitedMs += (nowMs - it).coerceAtLeast(0) }
            bufferStartedAt = null
            return Action.NONE
        }
        val started = bufferStartedAt
        if (started == null) {
            if (stalls == 0 || nowMs - windowStartedAt > 30_000) {
                windowStartedAt = nowMs
                stalls = 0
                waitedMs = 0L
            }
            stalls++
            bufferStartedAt = nowMs
            return Action.YIELD_PREFETCH
        }
        // 单首最多一次；连续8秒，或30秒内至少3次且累计等待6秒才尝试，不追逐短抖动。
        val ongoing = (nowMs - started).coerceAtLeast(0)
        val repeated = nowMs - windowStartedAt <= 30_000 && stalls >= 3 && waitedMs + ongoing >= 6_000
        if (!recoveryAttempted && allowSwitch && (ongoing >= 8_000 || repeated)) {
            return Action.FIND_ALTERNATIVE
        }
        return Action.NONE
    }
}

/** 换资源而非换歌曲：保留队列/当前位置，不调用play覆盖暂停；只在仍重缓冲时接管。 */
internal fun restartBufferedMediaItem(player: Player, expected: MediaItem): Boolean {
    if (!player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE ||
        player.playbackState != Player.STATE_BUFFERING ||
        player.currentMediaItem != expected) return false
    val index = player.currentMediaItemIndex
    val position = player.currentPosition.coerceAtLeast(0)
    player.stop() // 清掉旧读取/缓冲，但保留队列与playWhenReady；不在URI中塞入第二条资源路由。
    player.seekTo(index, position)
    player.prepare()
    return true
}

/** 保留首播/拖动门槛与缓存上限，仅网络耗尽后的恢复门槛从默认2秒增到3秒。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun playbackLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMsForStreaming(
        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        3_000,
    ).build()
