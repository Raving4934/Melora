package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import com.google.common.util.concurrent.Futures
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SourceResolver
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 实际Media3队列与保存窗口；只使用隔离包缓存静音文件，不解析在线音源。 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackQueueAppendInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("melora-queue", 0)
    private fun track(id: String) = UiTrack(id, "同名歌曲", "测试歌手", "测试专辑")

    @Test fun batchAppendPreservesPausedPositionAndDeduplicatesInPlaylistOrder() = withPlayer { player, _ ->
        main { PlaybackController.addToQueue(context, listOf(track("b"), track("c"), track("c"), track("d"))) }
        await { player.mediaItemCount == 4 }
        main {
            assertEquals(listOf("a", "b", "c", "d"), uids(player))
            assertEquals("a", player.currentMediaItem?.mediaId)
            assertTrue("追加后位置=${player.currentPosition}", player.currentPosition in 11_800L..12_200L)
            assertFalse(player.playWhenReady)
            assertEquals("已加入播放队列 2 首", PlaybackController.state.value.message)
            PlaybackController.addToQueue(context, listOf(track("b"), track("c")))
            assertEquals("歌曲已在播放队列中", PlaybackController.state.value.message)
        }
        val saved = JSONArray(prefs.getString("queue", "[]"))
        assertEquals(listOf("a", "b", "c", "d"), (0 until saved.length()).map { saved.getJSONObject(it).getString("uid") })
    }

    @Test fun appendDoesNotPauseOrSeekPlayingTrack() = withPlayer { player, controller ->
        main { controller.play() }
        await { player.isPlaying }
        val before = main { player.currentPosition }
        main { PlaybackController.addToQueue(context, listOf(track("c"), track("d"))) }
        await { player.mediaItemCount == 4 }
        main {
            assertTrue(player.isPlaying)
            assertEquals("a", player.currentMediaItem?.mediaId)
            assertTrue(player.currentPosition in before..(before + 3_000))
        }
    }

    @Test fun emptyQueueAppendNeverStartsPlaybackAndKeepsDifferentIdentities() = withPlayer(empty = true) { player, _ ->
        main { PlaybackController.addToQueue(context, listOf(track("quality128"), track("qualityHR"), track("quality128"))) }
        await { player.mediaItemCount == 2 }
        main {
            assertEquals(listOf("quality128", "qualityHR"), uids(player))
            assertEquals(Player.STATE_IDLE, player.playbackState)
            assertFalse(player.playWhenReady)
        }
    }

    @Test fun appendRestoresSavedQueueBeforeAddingWithoutAutoplay() = withPlayer(empty = true, listen = true) { player, _ ->
        val saved = JSONArray().put(JSONObject().put("uid", "saved-a")).put(JSONObject().put("uid", "saved-b"))
        prefs.edit().putString("queue", saved.toString()).putInt("index", 1).commit()
        main { PlaybackController.addToQueue(context, listOf(track("c"))) }
        await { player.mediaItemCount == 3 }
        main {
            assertEquals(listOf("saved-a", "saved-b", "c"), uids(player))
            assertEquals(1, player.currentMediaItemIndex)
            assertFalse(player.playWhenReady)
        }
    }

    @Test fun emptyInputAndDisconnectedServiceDoNotReportSuccess() = withPlayer { player, _ ->
        main {
            PlaybackController.addToQueue(context, emptyList())
            assertEquals("没有可加入的歌曲", PlaybackController.state.value.message)
            field("controller").set(PlaybackController, null)
            PlaybackController.addToQueue(context, listOf(track("c")))
            assertEquals("播放服务尚未就绪，请稍后重试", PlaybackController.state.value.message)
            assertEquals(listOf("a", "b"), uids(player))
        }
    }

    @Test fun clearQueueRemovesSavedQueueAndAppendCannotResurrectIt() = withPlayer(listen = true) { player, _ ->
        main {
            PlaybackController.addToQueue(context, listOf(track("c")))
            assertTrue(prefs.contains("queue"))
            PlaybackController.clearQueue()
            assertNull(PlaybackController.state.value.current)
            assertTrue(PlaybackController.state.value.queue.isEmpty())
            assertFalse(prefs.contains("queue"))
        }
        await { player.mediaItemCount == 0 }
        main { PlaybackController.addToQueue(context, listOf(track("new"))) }
        await { player.mediaItemCount == 1 }
        main { assertEquals(listOf("new"), uids(player)) }
    }

    @Test fun serviceExitClearsSavedQueueEvenWithoutConnectedUi() = withPlayer { player, _ ->
        main {
            PlaybackController.addToQueue(context, listOf(track("c")))
            assertNotNull(PlaybackController.state.value.current)
            field("controller").set(PlaybackController, null)
            // 通知栏使用服务端Player，不能依赖界面控制器仍然连接。
            PlaybackController.stop(player)
            assertEquals(0, player.mediaItemCount)
            assertFalse(prefs.contains("queue"))
            assertNull(PlaybackController.state.value.current)
            assertTrue(PlaybackController.state.value.queue.isEmpty())
            assertFalse(PlaybackController.state.value.playing)
        }
    }

    @Test fun serviceExitDoesNotRestoreOldTracksOnNextQueueUse() = withPlayer(listen = true) { player, controller ->
        main { PlaybackController.addToQueue(context, listOf(track("c"))) }
        // 先让已保存队列真正到达服务端，再模拟通知栏退出命令。
        await { player.mediaItemCount == 3 }
        main {
            PlaybackController.stop(player)
            assertFalse(prefs.contains("queue"))
        }
        await { controller.mediaItemCount == 0 }
        instrumentation.waitForIdleSync()
        main {
            assertFalse(prefs.contains("queue"))
            assertNull(PlaybackController.state.value.current)
            PlaybackController.addToQueue(context, listOf(track("new")))
        }
        await { player.mediaItemCount == 1 }
        main { assertEquals(listOf("new"), uids(player)) }
    }

    @Test fun externalTimelineClearIsPersistedAfterCallbacksSettle() = withPlayer(listen = true) { player, controller ->
        main { PlaybackController.addToQueue(context, listOf(track("c"))) }
        await { player.mediaItemCount == 3 }
        main { player.clearMediaItems() }
        await { controller.mediaItemCount == 0 && PlaybackController.state.value.current == null }
        instrumentation.waitForIdleSync()
        assertFalse("外部清空不能留下待恢复的旧队列", prefs.contains("queue"))
    }

    @Test fun disconnectInvalidatesControllerButKeepsSavedQueueForRecovery() = withPlayer(listen = true) { _, controller ->
        main { PlaybackController.addToQueue(context, listOf(track("c"))) }
        val saved = prefs.getString("queue", null)
        main { controller.release() }
        await { field("controller").get(PlaybackController) == null }
        main {
            assertNull(field("controllerFuture").get(PlaybackController))
            assertFalse(PlaybackController.state.value.ready)
            assertNull(PlaybackController.state.value.current)
            assertEquals(saved, prefs.getString("queue", null))
        }
    }

    @Test fun autoClearRemovesPreviousTrackByIdentityAfterRemovingEarlierQueueItem() =
        withPlayer(listen = true) { player, controller ->
            val previousAutoClear = main {
                MeloraSettings.autoClearPlayed.value.also { MeloraSettings.autoClearPlayed.value = true }
            }
            try {
                val audioUri = main { player.getMediaItemAt(0).localConfiguration!!.uri }
                main {
                    TrackRegistry.register(track("c"))
                    controller.addMediaItem(
                        MediaItem.Builder().setMediaId("c").setUri(audioUri).build(),
                    )
                }
                await { uids(player) == listOf("a", "b", "c") }

                // 先手动切到 B，保留前置 A；随后删除 A，使 B 的下标从 1 变为 0。
                main { PlaybackController.jumpTo(1) }
                await { player.currentMediaItem?.mediaId == "b" && player.playbackState == Player.STATE_READY }
                main { controller.pause() }
                await { !player.playWhenReady }
                main { PlaybackController.removeFromQueue(0) }
                await { uids(player) == listOf("b", "c") && player.currentMediaItem?.mediaId == "b" }

                // 静音 WAV 为 60 秒，跳到尾部触发真实 AUTO 切歌；B 必须按 UID 被移除。
                main {
                    controller.seekTo(59_000L)
                    controller.play()
                }
                await {
                    player.currentMediaItem?.mediaId == "c" &&
                        uids(player) == listOf("c")
                }
            } finally {
                main { MeloraSettings.autoClearPlayed.value = previousAutoClear }
            }
        }

    @Test fun autoClearDoesNotRemoveSameTrackOnRepeatOne() =
        withPlayer(listen = true) { player, controller ->
            val previousAutoClear = main {
                MeloraSettings.autoClearPlayed.value.also { MeloraSettings.autoClearPlayed.value = true }
            }
            try {
                main {
                    controller.repeatMode = Player.REPEAT_MODE_ONE
                    controller.seekTo(59_000L)
                    controller.play()
                }
                await {
                    player.currentMediaItem?.mediaId == "a" &&
                        player.currentPosition < 2_000L &&
                        player.playbackState == Player.STATE_READY
                }
                main {
                    assertEquals(listOf("a", "b"), uids(player))
                    assertEquals("a", controller.currentMediaItem?.mediaId)
                }
            } finally {
                main { MeloraSettings.autoClearPlayed.value = previousAutoClear }
            }
        }

    @Test fun autoClearRemovesLastTrackWhenRepeatAllWrapsToFirst() =
        withPlayer(listen = true) { player, controller ->
            val previousAutoClear = main {
                MeloraSettings.autoClearPlayed.value.also { MeloraSettings.autoClearPlayed.value = true }
            }
            try {
                // 手动切到末项 B，避免前置 A 的清理逻辑参与本场景。
                main { PlaybackController.jumpTo(1) }
                await { player.currentMediaItem?.mediaId == "b" && player.playbackState == Player.STATE_READY }
                main { controller.pause() }
                await { !player.playWhenReady }

                main {
                    controller.repeatMode = Player.REPEAT_MODE_ALL
                    controller.seekTo(59_000L)
                    controller.play()
                }
                await {
                    player.currentMediaItem?.mediaId == "a" &&
                        player.currentPosition < 2_000L &&
                        uids(player) == listOf("a")
                }
                main {
                    assertEquals(listOf("a"), uids(player))
                    assertEquals("a", controller.currentMediaItem?.mediaId)
                }
            } finally {
                main { MeloraSettings.autoClearPlayed.value = previousAutoClear }
            }
        }

    @Test fun coldAndCachedCardsWithoutSourcesBothKeepExistingPlayback() = withPlayer { player, controller ->
        val noSources = withoutSources()
        main { field("controllerFuture").set(PlaybackController, Futures.immediateFuture(controller)) }
        for (cached in listOf(false, true)) {
            val key = "queue-test.source-check.$cached"
            val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "queue-test-${System.nanoTime()}"))
            OnlineCache.clear(key)
            if (cached) OnlineCache.put(key, listOf(song))
            try {
                main {
                    PlaybackController.consumeMessage()
                    PlaybackController.requestQueue(noSources, key, key, { it: List<OnlineSong> -> it }) { listOf(song) }
                }
                await { PlaybackController.state.value.message == SourceResolver.NO_SOURCE_MESSAGE }
                main {
                    assertEquals(listOf("a", "b"), uids(player))
                    assertEquals("a", player.currentMediaItem?.mediaId)
                    assertFalse(player.playWhenReady)
                    assertTrue(player.currentPosition in 11_800L..12_200L)
                    assertNull(PlaybackController.state.value.pendingQueueId)
                }
            } finally {
                OnlineCache.clear(key)
            }
        }
    }

    @Test fun newerSelectionCancelsWaitingDirectoryBeforeOfflinePreflight() = withPlayer { player, controller ->
        val noSources = withoutSources()
        val key = "queue-test.pending-directory"
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<OnlineSong>>()
        val song = OnlineSong(JSONObject().put("source", "kw").put("songmid", "new-selection-${System.nanoTime()}"))
        try {
            main {
                field("controllerFuture").set(PlaybackController, Futures.immediateFuture(controller))
                PlaybackController.requestQueue(noSources, key, key, { it: List<OnlineSong> -> it }) {
                    started.complete(Unit)
                    response.await()
                }
            }
            await { started.isCompleted }
            main {
                val oldJob = field("queueLoadJob").get(PlaybackController) as Job
                PlaybackController.playTrack(noSources, UiTrack.fromOnline(song))
                assertTrue("选择新曲目时立即取消旧目录等待", oldJob.isCancelled)
                assertNull(PlaybackController.state.value.pendingQueueId)
            }
            response.complete(listOf(song))
            await { PlaybackController.state.value.message == SourceResolver.NO_SOURCE_MESSAGE }
            main {
                assertEquals(listOf("a", "b"), uids(player))
                assertEquals("a", player.currentMediaItem?.mediaId)
                assertFalse(player.playWhenReady)
            }
        } finally {
            response.cancel()
            OnlineCache.clear(key)
        }
    }

    @Test fun coldDirectoryDoesNotStartServiceAndClearCancelsWaiting() = withPlayer(empty = true) { _, _ ->
        val key = "queue-test.cold-start"
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<OnlineSong>>()
        val noServiceContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = error("目录未返回，不应启动播放服务")
        }
        try {
            main {
                field("controllerFuture").set(PlaybackController, null)
                field("controller").set(PlaybackController, null)
                PlaybackController.requestQueue(noServiceContext, key, key, { it: List<OnlineSong> -> it }) {
                    started.complete(Unit)
                    response.await()
                }
            }
            await { started.isCompleted }
            main {
                assertNull(field("controllerFuture").get(PlaybackController))
                assertNull(field("controller").get(PlaybackController))
                assertEquals(key, PlaybackController.state.value.pendingQueueId)
                val job = field("queueLoadJob").get(PlaybackController) as Job
                PlaybackController.clearQueue()
                assertTrue(job.isCancelled)
            }
            response.complete(emptyList())
            instrumentation.waitForIdleSync()
            main {
                assertNull(PlaybackController.state.value.pendingQueueId)
                assertNull(PlaybackController.state.value.message)
                assertTrue(PlaybackController.state.value.queue.isEmpty())
            }
        } finally {
            response.cancel()
            OnlineCache.clear(key)
        }
    }

    private fun withoutSources(): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(if (name == "lx-sources") "queue-test-empty-sources" else name, mode)
    }.also { it.getSharedPreferences("lx-sources", 0).edit().clear().commit() }

    private fun withPlayer(empty: Boolean = false, listen: Boolean = false, test: (ExoPlayer, MediaController) -> Unit) {
        val audio = silentWav()
        val player = main { ExoPlayer.Builder(context).build().apply { volume = 0f } }
        val session = main { MediaSession.Builder(context, player).setId("append-${System.nanoTime()}").build() }
        val controller = main {
            MediaController.Builder(context, session.token)
                .setListener(field("controllerListener").get(PlaybackController) as MediaController.Listener)
                .buildAsync()
        }.get(5, TimeUnit.SECONDS)
        val fields = listOf("controller", "controllerFuture", "bookQueue", "appContext", "lastQueueFingerprint", "detailUid", "currentQueueId", "playbackPreflight", "queueLoadJob", "pendingPlayback").associateWith(::field)
        val previous = main { fields.mapValues { it.value.get(PlaybackController) } }
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(PlaybackController) as MutableStateFlow<PlayerUiState>
        val previousState = state.value
        val savedPreferences = prefs.all
        prefs.edit().clear().commit()
        try {
            main {
                fields.getValue("controller").set(PlaybackController, controller)
                fields.getValue("appContext").set(PlaybackController, context.applicationContext)
                fields.getValue("detailUid").set(PlaybackController, if (empty) "quality128" else "a")
                if (!empty) {
                    TrackRegistry.registerAll(listOf(track("a"), track("b")))
                    controller.setMediaItems(listOf("a", "b").map { MediaItem.Builder().setMediaId(it).setUri(Uri.fromFile(audio)).build() })
                    controller.prepare()
                }
            }
            if (!empty) {
                await { player.playbackState == Player.STATE_READY && controller.playbackState == Player.STATE_READY }
                main { controller.seekTo(12_000) }
                await { player.currentPosition in 11_800L..12_200L && controller.currentPosition in 11_800L..12_200L }
            }
            if (listen) main {
                PlaybackController.javaClass.getDeclaredMethod("attach", MediaController::class.java)
                    .apply { isAccessible = true }.invoke(PlaybackController, controller)
            }
            test(player, controller)
        } finally {
            main {
                (field("playbackPreflight").get(PlaybackController) as? Job)?.cancel()
                (field("queueLoadJob").get(PlaybackController) as? Job)?.cancel()
                fields.forEach { (name, field) -> field.set(PlaybackController, previous[name]) }
                state.value = previousState
                controller.release(); session.release(); player.release()
            }
            prefs.edit().clear().apply {
                savedPreferences.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                } }
            }.commit()
            audio.delete()
        }
    }

    private fun field(name: String) = PlaybackController.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun uids(player: Player) = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!main(predicate)) { assertTrue("Media3 append timed out", SystemClock.elapsedRealtime() < deadline); Thread.sleep(10) }
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
        return File(context.cacheDir, "append-${System.nanoTime()}.wav").also {
            RandomAccessFile(it, "rw").use { out -> out.setLength(44L + bytes); out.write(header) }
        }
    }
}
