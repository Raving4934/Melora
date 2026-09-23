package com.leyu.melora.playback

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leyu.melora.playback.local.LocalFilePresence
import com.leyu.melora.playback.local.localFilePresence
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 隔离静音文件与真实Media3；不读取用户的媒体目录。 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LocalQueueDeletionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun deletingCurrentWhilePlayingContinuesWithNextTrack() = withQueue { player, controller, tracks ->
        main { controller.play() }
        await { player.isPlaying }
        main { controller.removeDeletedLocalItems(DeletedLocalFiles(setOf("1"), emptySet()), tracks::get) }
        await { player.currentMediaItem?.mediaId == "local_2" && player.isPlaying }
    }

    @Test fun deletingCurrentWhilePausedKeepsNextTrackPaused() = withQueue { player, controller, tracks ->
        main { controller.removeDeletedLocalItems(DeletedLocalFiles(setOf("1"), emptySet()), tracks::get) }
        await { player.currentMediaItem?.mediaId == "local_2" }
        main { assertFalse(player.playWhenReady); assertFalse(player.isPlaying) }
    }

    @Test fun deletingOtherTracksKeepsCurrentPositionAndDoesNotSeek() = withQueue { player, controller, tracks ->
        main { controller.seekTo(1, 23_000) }
        await { player.currentMediaItem?.mediaId == "local_2" && player.currentPosition in 22_800L..23_200L }
        main { controller.removeDeletedLocalItems(DeletedLocalFiles(setOf("1", "3"), emptySet()), tracks::get) }
        await { player.mediaItemCount == 1 }
        main {
            assertEquals("local_2", player.currentMediaItem?.mediaId)
            assertTrue(player.currentPosition in 22_800L..23_200L)
            assertFalse(player.playWhenReady)
        }
    }

    @Test fun batchDeletionNeverSelectsAnotherDeletedItem() = withQueue { player, controller, tracks ->
        val transitions = mutableListOf<String?>()
        main {
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { transitions += item?.mediaId }
            })
            controller.play()
            controller.removeDeletedLocalItems(DeletedLocalFiles(setOf("1", "2"), emptySet()), tracks::get)
        }
        await { player.currentMediaItem?.mediaId == "local_3" && player.isPlaying }
        main { assertFalse(transitions.contains("local_2")) }
    }

    @Test fun deletingAllClearsPlayerAndPlaybackIntent() = withQueue { player, controller, tracks ->
        main {
            controller.play()
            controller.removeDeletedLocalItems(DeletedLocalFiles(setOf("1", "2", "3"), emptySet()), tracks::get)
        }
        await { player.mediaItemCount == 0 && !player.playWhenReady }
        main { assertNull(player.currentMediaItem) }
    }

    @Test fun repeatOneCannotRepeatDeletedTrack() = withQueue { player, controller, tracks ->
        main {
            controller.repeatMode = Player.REPEAT_MODE_ONE
            controller.play()
            controller.removeDeletedLocalItems(DeletedLocalFiles(setOf("1"), emptySet()), tracks::get)
        }
        await { player.currentMediaItem?.mediaId == "local_2" && player.isPlaying }
    }

    @Test fun singleRepeatCurrentDeletionWhilePausedDoesNotStartPlayback() = withQueue { player, controller, tracks ->
        main {
            controller.repeatMode = Player.REPEAT_MODE_ONE
            controller.removeDeletedLocalItems(DeletedLocalFiles(setOf("1"), emptySet()), tracks::get)
        }
        await { player.currentMediaItem?.mediaId == "local_2" && player.playbackState == Player.STATE_READY }
        main { assertFalse(player.playWhenReady); assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode) }
    }

    @Test fun singleRepeatOtherDeletionKeepsCurrentPosition() = withQueue { player, controller, tracks ->
        main { controller.repeatMode = Player.REPEAT_MODE_ONE; controller.seekTo(23_000) }
        await { player.currentPosition in 22_800L..23_200L }
        main { controller.removeDeletedLocalItems(DeletedLocalFiles(setOf("2"), emptySet()), tracks::get) }
        await { player.mediaItemCount == 2 }
        main {
            assertEquals("local_1", player.currentMediaItem?.mediaId)
            assertTrue(player.currentPosition in 22_800L..23_200L)
        }
    }

    @Test fun confirmedDeletionUpdatesLiveAndSavedQueueTogether() = withQueue { player, controller, tracks ->
        withPlaybackController(controller, tracks) {
            runBlocking { PlaybackController.onLocalFilesDeleted(context, setOf("1"), emptySet()).join() }
            await { player.currentMediaItem?.mediaId == "local_2" }
            val prefs = context.getSharedPreferences("melora-queue", 0)
            val saved = JSONArray(prefs.getString("queue", "[]"))
            assertEquals(listOf("local_2", "local_3"), (0 until saved.length()).map { saved.getJSONObject(it).getString("uid") })
            main { assertFalse(player.playWhenReady) }
            runBlocking { PlaybackController.onLocalFilesDeleted(context, setOf("2", "3"), emptySet()).join() }
            await { player.mediaItemCount == 0 }
            assertFalse(prefs.contains("queue"))
            main { assertNull(PlaybackController.state.value.current) }
        }
    }

    private fun withPlaybackController(controller: MediaController, tracks: Map<String, UiTrack>, test: () -> Unit) {
        val fields = listOf("controller", "appContext", "lastQueueFingerprint", "detailUid").associateWith {
            PlaybackController.javaClass.getDeclaredField(it).apply { isAccessible = true }
        }
        val previous = main { fields.mapValues { it.value.get(PlaybackController) } }
        val prefs = context.getSharedPreferences("melora-queue", 0)
        val saved = prefs.all
        try {
            main {
                fields.getValue("controller").set(PlaybackController, controller)
                fields.getValue("appContext").set(PlaybackController, context.applicationContext)
                // 此测试聚焦删除与持久化，不触发异步歌词加载。
                fields.getValue("detailUid").set(PlaybackController, "local_2")
                TrackRegistry.registerAll(tracks.values.toList())
            }
            test()
        } finally {
            main { fields.forEach { (name, field) -> field.set(PlaybackController, previous[name]) } }
            prefs.edit().clear().apply {
                saved.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                } }
            }.commit()
        }
    }

    @Test fun onlyConfirmedMissingFilesAreReportedMissing() {
        val parent = File(context.cacheDir, "presence-${System.nanoTime()}").apply { mkdirs() }
        val file = File(parent, "audio.wav").apply { writeBytes(byteArrayOf(0, 1)) }
        try {
            assertEquals(LocalFilePresence.Present, localFilePresence(context, Uri.fromFile(file).toString()))
            assertTrue(file.delete())
            assertEquals(LocalFilePresence.Missing, localFilePresence(context, Uri.fromFile(file).toString()))
            assertEquals(LocalFilePresence.Unknown, localFilePresence(context, Uri.fromFile(File(parent, "unavailable/audio.wav")).toString()))
            assertEquals(LocalFilePresence.Unknown, localFilePresence(context, "content://fixture.not.available/tree/root/document/song"))
        } finally { parent.deleteRecursively() }
    }

    private fun withQueue(test: (ExoPlayer, MediaController, Map<String, UiTrack>) -> Unit) {
        val audio = silentWav()
        val tracks = (1..3).associate { number ->
            val uid = "local_$number"
            uid to UiTrack(uid, "Fixture $number", "", "", "local", raw = JSONObject().put("localUri", Uri.fromFile(audio)))
        }
        val player = main { ExoPlayer.Builder(context).build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ALL } }
        val session = main { MediaSession.Builder(context, player).setId("deletion-${System.nanoTime()}").build() }
        var controller: MediaController? = null
        try {
            val remote = main { MediaController.Builder(context, session.token).buildAsync() }.get(5, TimeUnit.SECONDS)
            controller = remote
            main {
                remote.setMediaItems(tracks.keys.map { MediaItem.Builder().setMediaId(it).setUri(Uri.fromFile(audio)).build() })
                remote.prepare()
            }
            await { player.playbackState == Player.STATE_READY && remote.playbackState == Player.STATE_READY }
            test(player, remote, tracks)
        } catch (error: AssertionError) {
            val actual = main { "uid=${player.currentMediaItem?.mediaId}, index=${player.currentMediaItemIndex}, state=${player.playbackState}, playWhenReady=${player.playWhenReady}, playing=${player.isPlaying}, count=${player.mediaItemCount}, error=${player.playerError}" }
            throw AssertionError("${error.message}; $actual", error)
        } finally {
            main { controller?.release(); session.release(); player.release() }
            audio.delete()
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!main(predicate)) { assertTrue("Media3 deletion timed out", SystemClock.elapsedRealtime() < deadline); Thread.sleep(10) }
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
        return File(context.cacheDir, "queue-delete-${System.nanoTime()}.wav").also {
            RandomAccessFile(it, "rw").use { out -> out.setLength(44L + bytes); out.write(header) }
        }
    }
}
