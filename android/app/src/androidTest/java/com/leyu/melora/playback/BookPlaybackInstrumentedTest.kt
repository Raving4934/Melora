package com.leyu.melora.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.sdk.OnlineSong
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 本地静音音频 + 真实ExoPlayer/MediaSession；不用在线解析器，也不改用户的歌曲和断点。 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class BookPlaybackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun chapter100AutomaticallyContinues101WithoutOldMusicQueue() = withBook { player, controller, queue, item ->
        main {
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.shuffleModeEnabled = true
            controller.setMediaItem(item(track(100)))
            queue.start("fixture")
            controller.prepare()
        }
        await { controller.playbackState == Player.STATE_READY && controller.mediaItemCount == 101 }
        main {
            assertEquals(Player.REPEAT_MODE_OFF, controller.repeatMode)
            assertFalse(controller.shuffleModeEnabled)
            controller.seekTo(59_500)
            controller.play()
        }
        await { player.currentMediaItem?.mediaId == track(101).uid && player.isPlaying }
    }

    @Test fun pausedEndedChapterDoesNotRestartWhenDirectoryArrives() {
        val response = CompletableDeferred<KwBookApi.BookChapters>()
        withBook(load = { _, _ -> response.await() }) { player, controller, queue, item ->
            main {
                controller.setMediaItem(item(track(100)))
                queue.start("fixture")
                controller.prepare()
            }
            await { controller.playbackState == Player.STATE_READY }
            main { controller.seekTo(59_900); controller.play() }
            await { player.playbackState == Player.STATE_ENDED }
            main { controller.pause() }
            response.complete(page(2))
            await { controller.mediaItemCount == 101 }
            main {
                assertFalse(player.playWhenReady)
                assertEquals(track(100).uid, player.currentMediaItem?.mediaId)
                controller.play()
            }
            await { player.currentMediaItem?.mediaId == track(101).uid && player.isPlaying }
        }
    }

    @Test fun nextWhileIdlePreparesNewChapterAfterDirectoryArrives() {
        val response = CompletableDeferred<KwBookApi.BookChapters>()
        withBook(load = { _, _ -> response.await() }) { player, controller, queue, item ->
            main {
                controller.setMediaItem(item(track(100)))
                queue.start("fixture")
                assertTrue(queue.next())
                controller.play()
            }
            response.complete(page(2))
            try {
                await { player.currentMediaItem?.mediaId == track(101).uid && player.isPlaying }
            } catch (failure: AssertionError) {
                throw AssertionError(main {
                    "engine=${player.currentMediaItem?.mediaId}/${player.playbackState}/${player.playWhenReady}, " +
                        "controller=${controller.currentMediaItem?.mediaId}/${controller.playbackState}/${controller.playWhenReady}, " +
                        "count=${controller.mediaItemCount}, book=${queue.albumId}"
                }, failure)
            }
        }
    }

    @Test fun resumeSelectedChapterRestoresTimeAndKeepsFollowingChapters() = withBook { player, controller, queue, item ->
        val prefs = context.getSharedPreferences("book-continuation-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putLong(track(100).uid, 32_000L).commit()
        val progress = main { PlaybackProgress(player, prefs, isEnabled = { true }) }
        try {
            main {
                controller.setMediaItems(listOf(item(track(100))), 0, C.TIME_UNSET)
                queue.start("fixture")
                controller.prepare()
            }
            await { player.playbackState == Player.STATE_READY && player.currentPosition in 31_500L..32_500L }
            await { controller.mediaItemCount == 101 }
            main {
                assertEquals(track(100).uid, player.currentMediaItem?.mediaId)
                assertEquals(track(101).uid, controller.getMediaItemAt(1).mediaId)
            }
        } finally {
            main { progress.close() }
            prefs.edit().clear().commit()
        }
    }

    @Test fun restoredQueueWindowContinuesAfter755NotAfterLastOriginallyLoadedPage() = withBook { _, controller, queue, item ->
        main {
            controller.setMediaItems((556..755).map { item(track(it)) }, 199, C.TIME_UNSET)
            queue.start("fixture")
        }
        await { controller.mediaItemCount == 245 }
        main {
            assertEquals(track(755).uid, controller.currentMediaItem?.mediaId)
            assertEquals(track(756).uid, controller.getMediaItemAt(200).mediaId)
        }
    }

    private fun withBook(
        load: suspend (String, Int) -> KwBookApi.BookChapters = { _, p -> page(p) },
        test: (ExoPlayer, MediaController, BookPlaybackQueue, (UiTrack) -> MediaItem) -> Unit,
    ) {
        val file = silentWav()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = main { ExoPlayer.Builder(context).build().apply { volume = 0f } }
        val session = main { MediaSession.Builder(context, player).setId("book-test-${System.nanoTime()}").build() }
        var controller: MediaController? = null
        var queue: BookPlaybackQueue? = null
        try {
            val remote = main { MediaController.Builder(context, session.token).buildAsync() }.get(5, TimeUnit.SECONDS)
            controller = remote
            val item: (UiTrack) -> MediaItem = {
                TrackRegistry.register(it)
                MediaItem.Builder().setMediaId(it.uid).setUri(Uri.fromFile(file)).build()
            }
            val active = main {
                BookPlaybackQueue(remote, scope, TrackRegistry::get, item, load, {}, { error(it) }).also { book ->
                    remote.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) { book.check() }
                    })
                }
            }
            queue = active
            test(player, remote, active, item)
        } finally {
            main {
                queue?.stop()
                scope.cancel()
                controller?.release()
                session.release()
                player.release()
            }
            file.delete()
        }
    }

    private fun track(number: Int): UiTrack = UiTrack.fromOnline(OnlineSong(JSONObject()
        .put("source", "kw").put("songmid", "book_test_$number").put("name", "测试章节 $number")
        .put("albumId", "fixture").put("isBookChapter", true).put("interval", "01:00")
        .put("bookPage", (number - 1) / 100 + 1).put("bookPageEnd", number % 100 == 0).put("bookHasMore", true)))

    private fun page(number: Int) = KwBookApi.BookChapters(
        ((number - 1) * 100 + 1..number * 100).map { OnlineSong(track(it).raw!!) }, true, page = number,
    )

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!main(predicate)) {
            assertTrue("等待真实播放器状态超时", SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(15)
        }
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun silentWav(): File {
        val bytes = 8_000 * 2 * 60
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes)
        }.array()
        return File(context.cacheDir, "book-test-${System.nanoTime()}.wav").also { file ->
            RandomAccessFile(file, "rw").use { it.setLength(44L + bytes); it.seek(0); it.write(header) }
        }
    }
}
