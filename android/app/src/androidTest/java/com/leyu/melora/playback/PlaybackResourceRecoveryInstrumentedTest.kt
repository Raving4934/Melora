package com.leyu.melora.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实Media3错误回调、不同资源和静音WAV；不联网、不加载用户脚本/服务。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackResourceRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun twoBadResourcesCanReachThirdWithoutSkippingOrLosingProgress() = withPlayer(2) { player, controller, source, uid ->
        main { controller.seekTo(0, 7_500); controller.prepare(); controller.play() }
        await { player.playbackState == Player.STATE_READY }
        main {
            assertEquals("不能把第二个坏链接当作整首歌不可用", uid, player.currentMediaItem?.mediaId)
            assertTrue(player.currentPosition >= 7_000)
            assertTrue(player.playWhenReady)
            assertEquals(2, player.mediaItemCount)
        }
        assertEquals(2, source.failedOpens.get())
    }

    @Test fun repeatedFailedResourceDoesNotLoopOrConsumeTheRestOfTheQueue() = withPlayer(10, repeatResource = true) { player, controller, source, _ ->
        main { controller.prepare(); controller.play() }
        await { player.playbackState == Player.STATE_READY }
        main {
            assertEquals("next", player.currentMediaItem?.mediaId)
            assertTrue(player.playWhenReady)
            assertEquals(2, player.mediaItemCount)
        }
        assertEquals(2, source.failedOpens.get())
    }

    @Test fun pauseBeforeLateFailurePreventsRetryAndSkip() = withPlayer(2, holdFailure = true) { player, controller, source, uid ->
        main { controller.prepare(); controller.play() }
        assertTrue(source.entered.await(3, TimeUnit.SECONDS))
        main { PlaybackController.toggle() }
        await { !player.playWhenReady }
        source.release.countDown()
        await { player.playerError != null && PlaybackController.state.value.message != null }
        main {
            assertEquals(uid, player.currentMediaItem?.mediaId)
            assertFalse(player.playWhenReady)
        }
        assertEquals(1, source.failedOpens.get())
    }

    private fun withPlayer(failures: Int, repeatResource: Boolean = false, holdFailure: Boolean = false,
                           test: (ExoPlayer, MediaController, Resources, String) -> Unit) {
        val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "recovery-${System.nanoTime()}")
            .put("name", "Fixture").put("singer", "Fixture Artist"))
        val track = UiTrack.fromOnline(song)
        val resources = Resources(track.uid, failures, repeatResource, holdFailure)
        val factory = ProgressiveMediaSource.Factory(DataSource.Factory { resources.newSource() })
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
                override fun getMinimumLoadableRetryCount(dataType: Int): Int = 0
                override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                    androidx.media3.common.C.TIME_UNSET
            })
        val player = main { ExoPlayer.Builder(context).build().apply { volume = 0f } }
        val session = main { MediaSession.Builder(context, player).setId("recovery-${System.nanoTime()}").build() }
        val controller = main { MediaController.Builder(context, session.token).buildAsync() }.get(5, TimeUnit.SECONDS)
        val fields = listOf("controller", "appContext", "consecutiveErrors").associateWith(::field)
        val originals = fields.mapValues { it.value.get(PlaybackController) }
        @Suppress("UNCHECKED_CAST") val state = field("_state").get(PlaybackController) as MutableStateFlow<PlayerUiState>
        val originalState = state.value
        val originalSwitch = MeloraSettings.autoSwitchSource.value
        try {
            main {
                PlaybackController.clearQueue()
                SourceResolver.clearCache()
                fields.getValue("controller").set(PlaybackController, controller)
                fields.getValue("appContext").set(PlaybackController, context)
                fields.getValue("consecutiveErrors").set(PlaybackController, 0)
                MeloraSettings.autoSwitchSource.value = true
                TrackRegistry.register(track)
                state.value = PlayerUiState(current = track, queue = listOf(track))
                controller.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        PlaybackController.javaClass.getDeclaredMethod("handlePlayerError", PlaybackException::class.java)
                            .apply { isAccessible = true }.invoke(PlaybackController, error)
                    }
                })
                player.setMediaSources(listOf(track.uid, "next").map { id ->
                    factory.createMediaSource(MediaItem.Builder().setMediaId(id).setUri("fixture://audio/$id").build())
                })
            }
            await { controller.mediaItemCount == 2 }
            test(player, controller, resources, track.uid)
        } finally {
            resources.release.countDown()
            main {
                PlaybackController.clearQueue()
                fields.forEach { (name, field) -> field.set(PlaybackController, originals[name]) }
                state.value = originalState
                MeloraSettings.autoSwitchSource.value = originalSwitch
                controller.release(); session.release(); player.release()
                TrackRegistry.clearResolved(track.uid)
                SourceResolver.clearCache()
            }
        }
    }

    private fun field(name: String) = PlaybackController.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!main(predicate)) { assertTrue("Media3恢复超时", SystemClock.elapsedRealtime() < deadline); Thread.sleep(10) }
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private class Resources(val uid: String, val failures: Int, val repeatResource: Boolean, val holdFailure: Boolean) {
        val failedOpens = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (holdFailure) 1 else 0)
        private val wav = ByteBuffer.allocate(44 + 320_000).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(320_000)
        }.array()
        fun newSource() = object : DataSource {
            private var position = 0
            private var uri: Uri? = null
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun getUri(): Uri? = uri
            override fun open(dataSpec: DataSpec): Long {
                uri = dataSpec.uri
                if (uri?.lastPathSegment == uid) {
                    val attempt = failedOpens.get()
                    val resource = if (repeatResource) "same" else attempt.toString()
                    TrackRegistry.notifyResolved(uid, "320k", "lx:fixture:$resource")
                    if (attempt < failures) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "fixture wait expired" }
                        failedOpens.incrementAndGet()
                        throw IOException("fixture resource failed")
                    }
                }
                position = dataSpec.position.toInt()
                return (wav.size - position).toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position >= wav.size) return -1
                val count = minOf(length, wav.size - position)
                wav.copyInto(buffer, offset, position, position + count); position += count
                return count
            }
            override fun close() = Unit
        }
    }
}
