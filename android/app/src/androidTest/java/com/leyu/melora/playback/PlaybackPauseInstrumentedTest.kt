package com.leyu.melora.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** 独立MediaSession + 内存静音WAV，不连接正式播放服务、不改用户音量、源或历史记录。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackPauseInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun resolvingOrInitialBufferingCanPauseAndLateDataCannotResume() = withPlayer { player, controller, stream ->
        onMain { player.prepare(); player.play() }
        await { controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady }
        assertTrue(stream.blocked.await(3, TimeUnit.SECONDS))
        onMain {
            assertFalse(controller.isPlaying)
            PlaybackController.toggle()
            assertFalse("缓冲中的暂停不能被误当作play", controller.playWhenReady)
        }
        await { !player.playWhenReady }
        stream.release.countDown()
        await { controller.playbackState == Player.STATE_READY }
        onMain {
            assertFalse("数据晚到不能覆盖暂停", player.playWhenReady)
            assertFalse(controller.isPlaying)
            PlaybackController.toggle()
        }
        await { player.isPlaying }
        onMain { PlaybackController.toggle() }
        await { !player.playWhenReady }
    }

    @Test fun rebufferingDuringPlaybackCanStillPause() = withPlayer(blockAfterBytes = 32_044) { player, controller, stream ->
        onMain { player.prepare(); player.play() }
        await { player.isPlaying }
        assertTrue(stream.blocked.await(3, TimeUnit.SECONDS))
        await { controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady }
        onMain { PlaybackController.toggle() }
        await { !player.playWhenReady }
        stream.release.countDown()
        await { controller.playbackState == Player.STATE_READY }
        onMain { assertFalse(player.playWhenReady); assertFalse(controller.isPlaying) }
    }

    @Test fun repeatedToggleDuringBufferingHonorsLastIntent() = withPlayer { player, controller, stream ->
        onMain { player.prepare(); player.play() }
        await { controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady }
        onMain {
            repeat(5) { index ->
                PlaybackController.toggle()
                assertEquals(index % 2 == 1, controller.playWhenReady)
            }
        }
        await { !player.playWhenReady }
        stream.release.countDown()
        await { controller.playbackState == Player.STATE_READY }
        onMain { assertFalse(player.playWhenReady) }
    }

    @Test fun pausedLateFailureDoesNotRetryOrSkipTrack() = withPlayer { player, controller, stream ->
        val stateField = PlaybackController.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(PlaybackController) as MutableStateFlow<PlayerUiState>
        val previousState = state.value
        try {
            onMain {
                state.value = PlayerUiState(current = UiTrack("pause-fixture", "Fixture", "Artist", "Album", "kw",
                    raw = JSONObject().put("source", "kw").put("songmid", "pause-fixture")))
                TrackRegistry.notifyResolved("pause-fixture", "flac24bit", "lx:fixture:pause")
                player.prepare(); player.play()
            }
            await { controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady }
            onMain { PlaybackController.toggle() }
            await { !player.playWhenReady }
            onMain {
                PlaybackController.javaClass.getDeclaredMethod("handlePlayerError", PlaybackException::class.java)
                    .apply { isAccessible = true }.invoke(PlaybackController,
                        PlaybackException("delayed test timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
                assertFalse(controller.playWhenReady)
                assertEquals("pause-fixture", controller.currentMediaItem?.mediaId)
            }
            stream.release.countDown()
            await { controller.playbackState == Player.STATE_READY }
            onMain { assertFalse(player.playWhenReady) }
            assertEquals(1, stream.opens.get())
        } finally { onMain { state.value = previousState; TrackRegistry.clearResolved("pause-fixture") } }
    }

    @Test fun productionLoadControlStartsWithOriginalOneSecondBuffer() =
        withPlayer(blockAfterBytes = 19_244, productionLoadControl = true) { player, _, stream ->
            onMain { player.prepare(); player.play() }
            assertTrue(stream.blocked.await(3, TimeUnit.SECONDS))
            // 只供给1.2秒，仍必须能起播；若首播误用3秒重缓冲门槛会在此失败。
            await { player.isPlaying }
            assertEquals(1L, stream.release.count)
        }

    @Test fun resourceRestartKeepsQueueIndexAndPositionWithoutRestartingSong() =
        withPlayer { player, controller, _ ->
            onMain {
                player.addMediaItem(MediaItem.Builder().setMediaId("second").setUri("https://example.test/second.wav").build())
                player.seekTo(0, 7500)
                player.prepare(); player.play()
            }
            await { controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady }
            onMain {
                val current = requireNotNull(controller.currentMediaItem)
                assertTrue(restartBufferedMediaItem(controller, current))
                assertEquals(2, controller.mediaItemCount)
                assertEquals(0, controller.currentMediaItemIndex)
                assertEquals(7500L, controller.currentPosition)
                assertTrue(controller.playWhenReady)
            }
            await { player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && player.currentPosition == 7500L }
        }

    @Test fun replacementCannotOverridePauseOrAnotherSong() = withPlayer { player, controller, _ ->
        onMain { player.prepare(); player.play() }
        await { controller.playbackState == Player.STATE_BUFFERING && controller.playWhenReady }
        onMain {
            val original = requireNotNull(controller.currentMediaItem)
            val other = original.buildUpon().setMediaId("other").build()
            assertFalse(restartBufferedMediaItem(controller, other))
            controller.pause()
            assertFalse(restartBufferedMediaItem(controller, original))
            assertEquals(original, controller.currentMediaItem)
        }
    }

    @Test fun recoveredPlaybackDoesNotAcceptLateResourceRestart() = withPlayer { player, controller, stream ->
        stream.release.countDown()
        onMain { player.prepare(); player.play() }
        await { controller.playbackState == Player.STATE_READY }
        onMain { assertFalse(restartBufferedMediaItem(controller, requireNotNull(controller.currentMediaItem))) }
        assertEquals(1, stream.opens.get())
    }

    private fun withPlayer(
        blockAfterBytes: Int = 0,
        productionLoadControl: Boolean = false,
        test: (ExoPlayer, MediaController, SlowWavDataSource) -> Unit,
    ) {
        val stream = SlowWavDataSource(blockAfterBytes)
        lateinit var player: ExoPlayer
        lateinit var session: MediaSession
        var playerCreated = false
        var sessionCreated = false
        var controller: MediaController? = null
        val controllerField = PlaybackController.javaClass.getDeclaredField("controller").apply { isAccessible = true }
        val previousController = controllerField.get(PlaybackController)
        try {
            onMain {
                player = ExoPlayer.Builder(instrumentation.targetContext)
                    .setLoadControl(if (productionLoadControl) playbackLoadControl()
                        else DefaultLoadControl.Builder().setBufferDurationsMs(1000, 2000, 100, 100).build())
                    .build().apply { volume = 0f }
                playerCreated = true
                player.setMediaSource(ProgressiveMediaSource.Factory(DataSource.Factory { stream })
                    .createMediaSource(MediaItem.Builder().setMediaId("pause-fixture")
                        .setUri("https://example.test/silent.wav").build()))
                session = MediaSession.Builder(instrumentation.targetContext, player)
                    .setId("pause-test-${System.nanoTime()}").build()
                sessionCreated = true
            }
            val future = onMain { MediaController.Builder(instrumentation.targetContext, session.token).buildAsync() }
            val connected = future.get(5, TimeUnit.SECONDS)
            controller = connected
            onMain { controllerField.set(PlaybackController, connected) }
            await { connected.mediaItemCount == 1 }
            test(player, connected, stream)
        } finally {
            stream.release.countDown()
            onMain {
                controllerField.set(PlaybackController, previousController)
                controller?.release()
                if (sessionCreated) session.release()
                if (playerCreated) player.release()
            }
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!onMain(predicate)) {
            check(SystemClock.elapsedRealtime() < deadline) { "Media3 state timed out" }
            Thread.sleep(10)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private class SlowWavDataSource(private val blockAfterBytes: Int) : DataSource {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        // 8kHz单声道16bit，20秒静音；第二种场景先给2秒，再阻断流，触发真正的重新缓冲。
        private val bytes = ByteBuffer.allocate(44 + 320_000).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(320_000)
        }.array()
        private var position = 0
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun getUri(): Uri = Uri.parse("https://example.test/silent.wav")
        override fun open(dataSpec: DataSpec): Long {
            opens.incrementAndGet()
            position = dataSpec.position.toInt()
            return (bytes.size - position).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= bytes.size) return -1
            if (position >= blockAfterBytes && release.count > 0) {
                blocked.countDown()
                if (!release.await(15, TimeUnit.SECONDS)) throw IOException("test slow stream timed out")
            }
            val available = if (position < blockAfterBytes) blockAfterBytes - position else bytes.size - position
            val count = minOf(length, available)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun close() = Unit
    }
}
