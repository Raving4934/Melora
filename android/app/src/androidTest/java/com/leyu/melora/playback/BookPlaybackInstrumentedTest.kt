package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import com.google.common.util.concurrent.Futures
import com.leyu.melora.playback.sdk.KwBookApi
import com.leyu.melora.playback.local.LocalMediaStore
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.sdk.OnlineSong
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
            }
            val resumeContext = isolatedContext()
            try {
                withIsolatedUserLibrary {
                    withInjectedPlaybackController(resumeContext, controller, queue) {
                        main { PlaybackController.resumeBook(resumeContext, OnlineSong.from(track(100).raw)!!) }
                        await { player.currentMediaItem?.mediaId == track(101).uid && player.isPlaying }
                    }
                }
            } finally {
                resumeContext.getSharedPreferences("melora-queue", Context.MODE_PRIVATE).edit().clear().commit()
                resumeContext.getSharedPreferences("melora-progress", Context.MODE_PRIVATE).edit().clear().commit()
            }
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

    @Test fun controllerResumeKeepsActiveChapterAndRestartSeeksThatChapterToZero() = withBook { player, controller, queue, item ->
        val registryField = TrackRegistry::class.java.getDeclaredField("tracks").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val registry = registryField.get(TrackRegistry) as java.util.concurrent.ConcurrentHashMap<String, UiTrack>
        val previousTracks = registry.toMap()
        val previousRememberProgress = MeloraSettings.rememberProgress.value
        val testContext = isolatedContext()
        val progressPrefs = testContext.getSharedPreferences("melora-progress", Context.MODE_PRIVATE)
        val queuePrefs = testContext.getSharedPreferences("melora-queue", Context.MODE_PRIVATE)
        progressPrefs.edit().clear().commit()
        queuePrefs.edit().clear().commit()
        MeloraSettings.rememberProgress.value = true

        try {
            withIsolatedUserLibrary {
                withInjectedPlaybackController(testContext, controller, queue) {
                    val chapters = (100..104).map(::track)
                    val currentSong = requireNotNull(OnlineSong.from(chapters.first().raw))
                    val staleBookSong = requireNotNull(OnlineSong.from(chapters[1].raw))
                    val progress = main {
                        PlaybackProgress(player, progressPrefs, TrackRegistry::get, isEnabled = { true })
                    }
                    try {
                        main {
                            controller.setMediaItems(chapters.map(item), 0, C.TIME_UNSET)
                            queue.start("fixture")
                            controller.prepare()
                        }
                        await {
                            player.playbackState == Player.STATE_READY &&
                                player.currentMediaItem?.mediaId == currentSong.uid
                        }
                        assertEquals("整书队列应复用本地测试队列而不请求目录", "fixture", queue.albumId)
                        main {
                            controller.pause()
                            controller.seekTo(24_000L)
                        }
                        await { player.currentPosition in 23_500L..24_500L }

                        // 另一章持有更旧的每书指针；活动队列当前章必须优先于它。
                        BookListeningProgress.persist(progressPrefs, staleBookSong, 54_000L, 60_000L, completed = false)
                        main {
                            PlaybackController.resumeBook(testContext, currentSong)
                            controller.pause()
                        }
                        assertEquals(currentSong.uid, main { player.currentMediaItem?.mediaId })
                        assertTrue("续听当前章不得归零或套用另一章断点", main { player.currentPosition in 22_500L..26_000L })
                        assertEquals(54_000L, progressPrefs.getLong(staleBookSong.uid, 0L))

                        // 即使当前 UID 有旧断点，显式重听也必须先清断点再 seekTo(0)。
                        BookListeningProgress.persist(progressPrefs, currentSong, 45_000L, 60_000L, completed = false)
                        main {
                            PlaybackController.restartBookChapter(testContext, currentSong)
                            controller.pause()
                        }
                        await { player.currentMediaItem?.mediaId == currentSong.uid && player.currentPosition == 0L }
                        assertFalse(progressPrefs.contains(currentSong.uid))
                        assertFalse(progressPrefs.getBoolean("completed:${currentSong.uid}", false))
                        assertEquals(54_000L, progressPrefs.getLong(staleBookSong.uid, 0L))
                    } finally {
                        main { progress.close() }
                    }
                }
            }
        } finally {
            MeloraSettings.rememberProgress.value = previousRememberProgress
            progressPrefs.edit().clear().commit()
            queuePrefs.edit().clear().commit()
            registry.clear()
            registry.putAll(previousTracks)
        }
    }

    @Test fun legacyChapterWithoutSupportedAlbumKeepsResumeAndColdRestartIntents() = withBook(
        load = { _, _ -> error("缺失或不支持的专辑身份不能请求酷我目录") },
    ) { _, controller, queue, item ->
        val originalLocalSongs = LocalMediaStore.songs.value
        val testContext = isolatedContext()
        val progressPrefs = testContext.getSharedPreferences("melora-progress", Context.MODE_PRIVATE)
        val controllerField = PlaybackController.javaClass.getDeclaredField("controller").apply { isAccessible = true }
        val pendingField = PlaybackController.javaClass.getDeclaredField("pendingPlayback").apply { isAccessible = true }
        try {
            withIsolatedUserLibrary {
                withInjectedPlaybackController(testContext, controller, queue) {
                    main { controllerField.set(PlaybackController, null) }
                    for (source in listOf("kw", "wy")) {
                        val song = OnlineSong(JSONObject().put("source", source).put("songmid", "legacy-$source")
                            .put("name", "旧章节-$source").put("singer", "测试主播").put("isBookChapter", true)
                            .put("albumId", if (source == "kw") "" else "other-platform-book"))
                        val track = UiTrack.fromOnline(song)
                        val uri = item(track).localConfiguration!!.uri.toString()
                        LocalMediaStore.replaceAll(listOf(LocalSong(
                            id = "legacy-$source", uri = uri, title = song.name, artist = song.singer, album = "",
                            durationMs = 60_000L, sizeBytes = 960_044L, mimeType = "audio/wav", sampleRate = 8_000,
                            bitrate = 128_000, modifiedAt = 0L, addedAt = 0L, folder = "",
                        )))
                        assertNotNull("离线夹具必须命中本地资源，不能执行联网预检", LocalMediaStore.matchTrack(track))
                        progressPrefs.edit().putLong(song.uid, 23_000L).commit()
                        main { PlaybackController.resumeBook(testContext, song) }
                        val resume = main { pendingField.get(PlaybackController) as? PendingPlaybackSelection }
                        assertEquals(song.uid, resume?.tracks?.single()?.uid)
                        assertTrue("无目录章节也必须建立续听意图", resume?.insertSingle == true)
                        assertEquals(23_000L, progressPrefs.getLong(song.uid, 0L))
                        main { PlaybackController.restartBookChapter(testContext, song) }
                        val restart = main { pendingField.get(PlaybackController) as? PendingPlaybackSelection }
                        assertEquals(song.uid, restart?.tracks?.single()?.uid)
                        assertEquals(0L, restart?.startPositionMs)
                        assertNull(restart?.queueId)
                        assertTrue("不可凭空创建专辑容器", UserLibrary.recentContainers.value.isEmpty())
                    }
                }
            }
        } finally {
            LocalMediaStore.replaceAll(originalLocalSongs)
            progressPrefs.edit().clear().commit()
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

    private fun isolatedContext(): Context {
        val namespace = "book-controller-test-${System.nanoTime()}"
        return object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                instrumentation.context.getSharedPreferences("$namespace.$name", mode)
        }
    }

    private fun withIsolatedUserLibrary(block: () -> Unit) {
        val fileField = UserLibrary::class.java.getDeclaredField("file").apply { isAccessible = true }
        val previousFile = fileField.get(UserLibrary)
        val snapshot = UserLibrary.exportSnapshot()
        val directory = Files.createTempDirectory("book-controller-library").toFile()
        fileField.set(UserLibrary, File(directory, "user-library.json"))
        try {
            UserLibrary.replaceFromBackup("{}")
            block()
        } finally {
            try {
                UserLibrary.replaceFromBackup(snapshot)
            } finally {
                fileField.set(UserLibrary, previousFile)
                directory.deleteRecursively()
            }
        }
    }

    private fun withInjectedPlaybackController(
        testContext: Context,
        controller: MediaController,
        queue: BookPlaybackQueue,
        block: () -> Unit,
    ) {
        val names = listOf(
            "controller", "controllerFuture", "bookQueue", "appContext", "currentQueueId",
            "lastQueueFingerprint", "playbackPreflight", "queueLoadJob", "urlPrefetchJob",
            "urlPrefetchUid", "pendingPlayback",
        )
        val fields = names.associateWith { name ->
            PlaybackController.javaClass.getDeclaredField(name).apply { isAccessible = true }
        }
        val previous = main { fields.mapValues { (_, field) -> field.get(PlaybackController) } }
        assertTrue(
            "注入前不应有尚未完成的全局 MediaController 连接",
            (previous["controllerFuture"] as? java.util.concurrent.Future<*>)?.isDone != false,
        )
        listOf("playbackPreflight", "queueLoadJob", "urlPrefetchJob", "pendingPlayback").forEach { name ->
            assertNull("注入前不应有全局待处理工作：$name", previous[name])
        }
        @Suppress("UNCHECKED_CAST")
        val state = PlaybackController.javaClass.getDeclaredField("_state").apply { isAccessible = true }
            .get(PlaybackController) as MutableStateFlow<PlayerUiState>
        val previousState = state.value
        try {
            main {
                fields.getValue("controller").set(PlaybackController, controller)
                fields.getValue("controllerFuture").set(PlaybackController, Futures.immediateFuture(controller))
                fields.getValue("bookQueue").set(PlaybackController, queue)
                fields.getValue("appContext").set(PlaybackController, testContext.applicationContext)
                fields.getValue("currentQueueId").set(PlaybackController, bookQueueId("fixture"))
                fields.getValue("lastQueueFingerprint").set(PlaybackController, null)
            }
            block()
            main {
                listOf("playbackPreflight", "queueLoadJob", "urlPrefetchJob").forEach { name ->
                    assertNull("整合用例不得遗留 Controller 请求任务：$name", fields.getValue(name).get(PlaybackController))
                }
            }
        } finally {
            main {
                listOf("playbackPreflight", "queueLoadJob", "urlPrefetchJob").forEach { name ->
                    (fields.getValue(name).get(PlaybackController) as? Job)
                        ?.takeIf { it !== previous[name] }?.cancel()
                }
                fields.forEach { (name, field) -> field.set(PlaybackController, previous[name]) }
                state.value = previousState
            }
        }
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
