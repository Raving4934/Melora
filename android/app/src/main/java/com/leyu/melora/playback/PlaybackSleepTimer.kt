package com.leyu.melora.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 服务端播放器是曲尾的唯一时钟；不按 UI 采样位置猜测剩余时长。所有入口均在主线程。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackSleepTimer(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val mutableRemaining = MutableStateFlow<Int?>(null)
    val remaining = mutableRemaining.asStateFlow()
    private var player: ExoPlayer? = null
    private var timer: Job? = null
    private var waitingForEnd = false

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // 包括真正播到曲尾及用户主动暂停；等待态不能遗留到下一次播放。
            if (waitingForEnd && !playWhenReady) cancel()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (waitingForEnd && (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED)) {
                finish()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 到期后手动切歌/换队列也结束等待，不能让定时器继续追逐另一首歌。
            if (waitingForEnd) finish()
        }
    }

    fun attach(next: ExoPlayer?) {
        if (player === next) return
        cancel()
        player?.removeListener(listener)
        player = next
        next?.addListener(listener)
    }

    fun start(minutes: Int, extendToFinishTrack: Boolean) {
        require(minutes in 0..1_440)
        cancel()
        val deadline = nowMs() + minutes * 60_000L
        mutableRemaining.value = minutes * 60
        timer = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                val left = (deadline - nowMs()).coerceAtLeast(0L)
                if (left == 0L) break
                mutableRemaining.value = ((left + 999L) / 1_000L).toInt()
                delay(minOf(left, 1_000L))
            }
            timer = null
            val current = player
            if (extendToFinishTrack && current != null && current.playWhenReady &&
                current.mediaItemCount > 0 &&
                current.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)
            ) {
                waitingForEnd = true
                // 0 表示到期等待曲尾，仍可在 UI 中关闭。支持缓冲、倍速、未知时长和单曲循环。
                mutableRemaining.value = 0
                current.setPauseAtEndOfMediaItems(true)
            } else {
                finish()
            }
        }.also { it.start() }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
        val wasWaiting = waitingForEnd
        waitingForEnd = false
        mutableRemaining.value = null
        if (wasWaiting) player?.setPauseAtEndOfMediaItems(false)
    }

    private fun finish() {
        val current = player
        cancel()
        current?.pause()
    }
}
