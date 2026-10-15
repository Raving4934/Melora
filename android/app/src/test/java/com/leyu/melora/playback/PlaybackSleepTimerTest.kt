package com.leyu.melora.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PlaybackSleepTimerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val timer = PlaybackSleepTimer(scope) { 0L }
    private val playback = Playback()

    init { timer.attach(playback.player) }

    @After fun cleanup() { timer.attach(null); scope.cancel() }

    @Test fun immediateTimerPausesWithoutEnablingEndOfItemFlag() {
        timer.start(0, false)
        assertEquals(1, playback.pauses)
        assertFalse(playback.pauseAtEnd)
        assertNull(timer.remaining.value)
    }

    @Test fun waitUsesRealEndOfItemEvenWhenBuffering() {
        playback.state = Player.STATE_BUFFERING
        timer.start(0, true)
        assertEquals(0, timer.remaining.value)
        assertTrue(playback.pauseAtEnd)
        assertEquals(0, playback.pauses)
        playback.listeners.single().onPlaybackStateChanged(Player.STATE_READY)
        assertTrue(playback.pauseAtEnd)
        playback.wantsPlayback = false
        playback.listeners.single().onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        assertFalse(playback.pauseAtEnd)
        assertNull(timer.remaining.value)
    }

    @Test fun cancellingOrReschedulingWaitDoesNotPauseOrLeaveEndFlag() {
        timer.start(0, true)
        timer.cancel()
        assertFalse(playback.pauseAtEnd)
        assertNull(timer.remaining.value)
        assertEquals(0, playback.pauses)
        timer.start(0, true)
        timer.start(5, false)
        assertFalse(playback.pauseAtEnd)
        assertEquals(300, timer.remaining.value)
        assertEquals(0, playback.pauses)
    }

    @Test fun naturalEndAndManualTrackChangeFinishWaiting() {
        timer.start(0, true)
        playback.listeners.single().onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(1, playback.pauses)
        assertNull(timer.remaining.value)
        timer.start(0, true)
        playback.listeners.single().onMediaItemTransition(MediaItem.EMPTY, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        assertEquals(2, playback.pauses)
        assertFalse(playback.pauseAtEnd)
        assertNull(timer.remaining.value)
    }

    @Test fun pausedOrEmptySessionDoesNotWaitForAnEndThatCannotArrive() {
        playback.wantsPlayback = false
        timer.start(0, true)
        assertNull(timer.remaining.value)
        assertFalse(playback.pauseAtEnd)
        playback.wantsPlayback = true
        playback.count = 0
        timer.start(0, true)
        assertNull(timer.remaining.value)
        assertFalse(playback.pauseAtEnd)
    }

    @Test fun detachAndNewSessionCancelBothCountdownAndWait() {
        timer.start(0, true)
        timer.attach(null)
        assertTrue(playback.listeners.isEmpty())
        assertFalse(playback.pauseAtEnd)
        assertNull(timer.remaining.value)
        timer.attach(playback.player)
        timer.start(5, false)
        val next = Playback()
        timer.attach(next.player)
        assertNull(timer.remaining.value)
        assertTrue(playback.listeners.isEmpty())
        assertEquals(1, next.listeners.size)
        assertEquals(0, next.pauses)
    }

    private class Playback {
        val listeners = mutableListOf<Player.Listener>()
        var wantsPlayback = true
        var state = Player.STATE_READY
        var count = 2
        var pauseAtEnd = false
        var pauses = 0
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                "getPlayWhenReady" -> wantsPlayback
                "getPlaybackState" -> state
                "getMediaItemCount" -> count
                "setPauseAtEndOfMediaItems" -> { pauseAtEnd = args!![0] as Boolean; null }
                "pause" -> { pauses++; null }
                else -> error("Timer must not call ${method.name}")
            }
        } as ExoPlayer
    }
}
