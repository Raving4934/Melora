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
    private var attemptInFlight = false
    private val slowResources = mutableSetOf<String>()
    val excludedResources: Set<String> get() = slowResources.toSet()
    private var finishedAttempts = 0
    private var retryAtMs = 0L
    private var updatedAtMs = 0L
    private var windowStartedAt = 0L
    private var stalls = 0
    private var waitedMs = 0L
    private var waitForPlayback = false

    fun reset(trackUid: String? = null) {
        uid = trackUid
        hasPlayed = false
        bufferStartedAt = null
        attemptInFlight = false
        slowResources.clear()
        finishedAttempts = 0
        retryAtMs = 0L
        waitForPlayback = false
        stalls = 0
        waitedMs = 0L
    }

    fun attemptStarted(resourceId: String? = null) {
        resourceId?.let(slowResources::add)
        attemptInFlight = true
        retryAtMs = updatedAtMs + 30_000
    }

    /** 仅真正结束的搜索/接管计次；用户暂停、切歌或拖动取消不永久耗尽该曲机会。 */
    fun attemptFinished(completed: Boolean, nowMs: Long) {
        attemptInFlight = false
        if (completed) finishedAttempts++
        retryAtMs = nowMs + 30_000
    }

    fun interrupted() {
        attemptInFlight = false
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
        updatedAtMs = nowMs
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
        // 单首最多两次完整恢复，间隔至少30秒；连续8秒或30秒内三次累计6秒才启动。
        val ongoing = (nowMs - started).coerceAtLeast(0)
        val repeated = nowMs - windowStartedAt <= 30_000 && stalls >= 3 && waitedMs + ongoing >= 6_000
        if (!attemptInFlight && finishedAttempts < 2 && nowMs >= retryAtMs &&
            allowSwitch && (ongoing >= 8_000 || repeated)) {
            return Action.FIND_ALTERNATIVE
        }
        return Action.NONE
    }
}

/** 同曲错误恢复只尝试不同的失败资源；时间窗口仅限制再次发起，不中断已经正常出声的歌曲。 */
internal class PlaybackErrorRecovery {
    private val attempted = mutableSetOf<String>()
    private var startedAtMs: Long? = null

    fun reset() { attempted.clear(); startedAtMs = null }

    fun allowRetry(resourceId: String, nowMs: Long, autoSwitch: Boolean): Boolean {
        val started = startedAtMs ?: nowMs.also { startedAtMs = it }
        return nowMs - started in 0L..30_000L && attempted.size < (if (autoSwitch) 2 else 1) &&
            attempted.add(resourceId)
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
