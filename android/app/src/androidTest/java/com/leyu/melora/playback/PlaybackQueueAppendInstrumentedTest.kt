package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
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
    private val testContext = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            instrumentation.context.getSharedPreferences("queue-fixture.$name", mode)
    }
    private val prefs get() = testContext.getSharedPreferences("melora-queue", 0)
    private fun track(id: String) = UiTrack(id, "同名歌曲", "测试歌手", "测试专辑")

    @Test fun emptyControllerTimelinePublishesAnEmptyQueueOrder() = withPlayer(empty = true, listen = true) { _, controller ->
        main {
            assertEquals(0, controller.currentTimeline.windowCount)
            assertTrue(controller.playbackQueueOrder().isEmpty())
            PlaybackController.javaClass.getDeclaredMethod("publish")
                .apply { isAccessible = true }
                .invoke(PlaybackController)
            assertTrue(PlaybackController.state.value.queue.isEmpty())
            assertTrue(PlaybackController.state.value.queueOrder.isEmpty())
        }
    }

    @Test fun queueOrderProjectsTheControllerTimelineAcrossPlayModesWithoutMovingPlayback() =
        withPlayer(listen = true) { player, controller ->
            val previousMode = main { MeloraSettings.musicPlayMode.value }
            try {
                installProjectionQueue(player, controller)
                main {
                    MeloraSettings.updateMusicPlayMode(PlayMode.List)
                    PlaybackController.applyMusicPlayMode()
                }
                await {
                    PlaybackController.state.value.mode == PlayMode.List &&
                        PlaybackController.state.value.queueOrder == listOf(0, 1, 2, 3)
                }
                main {
                    assertEquals(listOf("repeat", "b", "repeat", "d"), PlaybackController.state.value.queue.map { it.uid })
                    assertEquals(1, PlaybackController.state.value.currentIndex)
                }

                val expectedModes = listOf(
                    PlayMode.Single to listOf(0, 1, 2, 3),
                    PlayMode.Shuffle to listOf(2, 0, 3, 1),
                    PlayMode.List to listOf(0, 1, 2, 3),
                )
                expectedModes.forEach { (mode, order) ->
                    val before = main {
                        Triple(controller.currentMediaItem?.mediaId, controller.currentMediaItemIndex, controller.currentPosition)
                    }
                    main { PlaybackController.cycleMode() }
                    await {
                        PlaybackController.state.value.mode == mode && PlaybackController.state.value.queueOrder == order
                    }
                    main {
                        assertEquals(before.first, controller.currentMediaItem?.mediaId)
                        assertEquals(before.second, controller.currentMediaItemIndex)
                        assertTrue(
                            "切换到$mode 不应重置播放位置：${before.third} -> ${controller.currentPosition}",
                            kotlin.math.abs(before.third - controller.currentPosition) <= 250L,
                        )
                    }
                }
            } finally {
                main { MeloraSettings.updateMusicPlayMode(previousMode) }
            }
        }

    @Test fun nextFollowsTheDisplayedRepeatedTracksShuffleSuccessorByRawIndex() =
        withPlayer(listen = true) { player, controller ->
            val previousMode = main { MeloraSettings.musicPlayMode.value }
            try {
                installProjectionQueue(player, controller)
                main {
                    MeloraSettings.updateMusicPlayMode(PlayMode.Shuffle)
                    PlaybackController.applyMusicPlayMode()
                }
                await { PlaybackController.state.value.queueOrder == listOf(2, 0, 3, 1) }

                val (rawIndex, expectedNextRawIndex) = main {
                    val order = PlaybackController.state.value.queueOrder
                    val displayIndex = 0
                    val selectedRawIndex = order[displayIndex]
                    val nextRawIndex = order[displayIndex + 1]
                    assertEquals(2, selectedRawIndex)
                    assertEquals(0, nextRawIndex)
                    assertEquals(
                        PlaybackController.state.value.queue[selectedRawIndex].uid,
                        PlaybackController.state.value.queue[nextRawIndex].uid,
                    )
                    selectedRawIndex to nextRawIndex
                }

                main { PlaybackController.jumpTo(rawIndex) }
                await {
                    player.currentMediaItemIndex == rawIndex &&
                        PlaybackController.state.value.currentIndex == rawIndex
                }
                main { PlaybackController.next() }
                await {
                    player.currentMediaItemIndex == expectedNextRawIndex &&
                        PlaybackController.state.value.currentIndex == expectedNextRawIndex
                }
                main {
                    assertEquals("repeat", controller.currentMediaItem?.mediaId)
                    assertEquals(0, controller.currentMediaItemIndex)
                    controller.pause()
                }
            } finally {
                main { MeloraSettings.updateMusicPlayMode(previousMode) }
            }
        }

    @Test fun largeShuffledControllerTimelinesProjectEveryIndexOnceUnderRepeatOne() {
        val audio = silentWav()
        val audioUri = Uri.fromFile(audio)
        try {
            withPlayer(empty = true, listen = true) { player, controller ->
                for (itemCount in listOf(589, 2_000)) {
                    val items = (0 until itemCount).map { index ->
                        MediaItem.Builder()
                            .setMediaId("large-projection-$itemCount-$index")
                            .setUri(audioUri)
                            .build()
                    }
                    main {
                        controller.repeatMode = Player.REPEAT_MODE_ONE
                        controller.shuffleModeEnabled = true
                        controller.setMediaItems(items)
                    }
                    await {
                        player.mediaItemCount == itemCount &&
                            controller.currentTimeline.windowCount == itemCount
                    }
                    main {
                        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(itemCount, 7L))
                    }
                    await {
                        player.mediaItemCount == itemCount &&
                            controller.currentTimeline.windowCount == itemCount &&
                            controller.repeatMode == Player.REPEAT_MODE_ONE &&
                            controller.shuffleModeEnabled &&
                            PlaybackController.state.value.queueOrder.size == itemCount &&
                            PlaybackController.state.value.queueOrder.firstOrNull() ==
                            controller.currentTimeline.getFirstWindowIndex(/* shuffleModeEnabled= */ true)
                    }

                    val order = main { PlaybackController.state.value.queueOrder }
                    assertEquals("$itemCount 个时间线窗口应完整投影", (0 until itemCount).toList(), order.sorted())
                    main { assertEquals(Player.STATE_IDLE, player.playbackState) }
                }
            }
        } finally {
            audio.delete()
        }
    }

    @Test fun removingTheDisplayedShuffledDuplicateUsesItsOriginalMediaItemIndex() =
        withPlayer(listen = true) { player, controller ->
            val previousMode = main { MeloraSettings.musicPlayMode.value }
            try {
                installProjectionQueue(player, controller)
                main {
                    MeloraSettings.updateMusicPlayMode(PlayMode.Shuffle)
                    PlaybackController.applyMusicPlayMode()
                }
                await { PlaybackController.state.value.queueOrder == listOf(2, 0, 3, 1) }

                // 展示列表第一项是原始索引 2；它与原始索引 0 是同一曲目，必须只删除索引 2。
                val originalMediaItemIndex = main { PlaybackController.state.value.queueOrder.first() }
                main { PlaybackController.removeFromQueue(originalMediaItemIndex) }
                await { uids(player) == listOf("repeat", "b", "d") }
                await {
                    PlaybackController.state.value.queue.map { it.uid } == listOf("repeat", "b", "d") &&
                        PlaybackController.state.value.currentIndex == 1
                }
                main {
                    assertEquals("b", controller.currentMediaItem?.mediaId)
                    assertEquals(1, controller.currentMediaItemIndex)
                    assertEquals(listOf("repeat", "b", "d"), uids(player))
                }
            } finally {
                main { MeloraSettings.updateMusicPlayMode(previousMode) }
            }
        }

    @Test fun batchAppendPreservesPausedPositionAndDeduplicatesInPlaylistOrder() = withPlayer { player, _ ->
        main { PlaybackController.addToQueue(testContext, listOf(track("b"), track("c"), track("c"), track("d"))) }
        await { player.mediaItemCount == 4 }
        main {
            assertEquals(listOf("a", "b", "c", "d"), uids(player))
            assertEquals("a", player.currentMediaItem?.mediaId)
            assertTrue("追加后位置=${player.currentPosition}", player.currentPosition in 11_800L..12_200L)
            assertFalse(player.playWhenReady)
            assertEquals("已加入播放队列 2 首", PlaybackController.state.value.message)
            PlaybackController.addToQueue(testContext, listOf(track("b"), track("c")))
            assertEquals("歌曲已在播放队列中", PlaybackController.state.value.message)
        }
        val saved = JSONArray(prefs.getString("queue", "[]"))
        assertEquals(listOf("a", "b", "c", "d"), (0 until saved.length()).map { saved.getJSONObject(it).getString("uid") })
    }

    @Test fun appendDoesNotPauseOrSeekPlayingTrack() = withPlayer { player, controller ->
        main { controller.play() }
        await { player.isPlaying }
        val before = main { player.currentPosition }
        main { PlaybackController.addToQueue(testContext, listOf(track("c"), track("d"))) }
        await { player.mediaItemCount == 4 }
        main {
            assertTrue(player.isPlaying)
            assertEquals("a", player.currentMediaItem?.mediaId)
            assertTrue(player.currentPosition in before..(before + 3_000))
        }
    }

    @Test fun emptyQueueAppendNeverStartsPlaybackAndKeepsDifferentIdentities() = withPlayer(empty = true) { player, _ ->
        main { PlaybackController.addToQueue(testContext, listOf(track("quality128"), track("qualityHR"), track("quality128"))) }
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
        main { PlaybackController.addToQueue(testContext, listOf(track("c"))) }
        await { player.mediaItemCount == 3 }
        main {
            assertEquals(listOf("saved-a", "saved-b", "c"), uids(player))
            assertEquals(1, player.currentMediaItemIndex)
            assertFalse(player.playWhenReady)
        }
    }

    @Test fun emptyInputAndDisconnectedServiceDoNotReportSuccess() = withPlayer { player, _ ->
        main {
            PlaybackController.addToQueue(testContext, emptyList())
            assertEquals("没有可加入的歌曲", PlaybackController.state.value.message)
            field("controller").set(PlaybackController, null)
            PlaybackController.addToQueue(testContext, listOf(track("c")))
            assertEquals("播放服务尚未就绪，请稍后重试", PlaybackController.state.value.message)
            assertEquals(listOf("a", "b"), uids(player))
        }
    }

    @Test fun clearQueueRemovesSavedQueueAndCannotRestoreItBeforeNextAppend() = withPlayer(listen = true) { player, controller ->
        main {
            PlaybackController.addToQueue(testContext, listOf(track("c")))
            assertTrue(prefs.contains("queue"))
            PlaybackController.clearQueue()
            assertNull(PlaybackController.state.value.current)
            assertTrue(PlaybackController.state.value.queue.isEmpty())
            assertFalse(prefs.contains("queue"))
        }
        await { player.mediaItemCount == 0 && controller.mediaItemCount == 0 }
        restoreQueue(controller, autoPlay = false)
        await { controller.mediaItemCount == 0 }
        assertFalse("显式清空后，冷恢复不得复活旧队列", prefs.contains("queue"))
        main { PlaybackController.addToQueue(testContext, listOf(track("new"))) }
        await { player.mediaItemCount == 1 }
        main { assertEquals(listOf("new"), uids(player)) }
    }

    @Test fun serviceExitPersistsQueueIndexAndPositionWithoutUiControllerAndIsIdempotent() =
        withPlayer(listen = true) { player, controller ->
            main { PlaybackController.addToQueue(testContext, listOf(track("c"))) }
            await { player.mediaItemCount == 3 }
            main { PlaybackController.jumpTo(1) }
            await { player.currentMediaItemIndex == 1 && player.playbackState == Player.STATE_READY }
            main {
                controller.pause()
                controller.seekTo(23_000L)
            }
            await { !player.playWhenReady && player.currentPosition in 22_800L..23_200L }
            val beforeExitPosition = main { player.currentPosition }

            main {
                // 通知退出必须以服务端 Player 为来源；模拟 UI controller 已先断开的竞态。
                field("controller").set(PlaybackController, null)
                field("controllerFuture").set(PlaybackController, Futures.immediateFuture(controller))
                PlaybackController.exitPlayback(player)
                assertNull(field("controller").get(PlaybackController))
                assertNull(field("controllerFuture").get(PlaybackController))
                assertNull(PlaybackController.state.value.current)
                assertFalse(PlaybackController.state.value.playing)
            }
            await { !controller.isConnected }
            val saved = JSONArray(prefs.getString("queue", "[]"))
            assertEquals(listOf("a", "b", "c"), (0 until saved.length()).map { saved.getJSONObject(it).getString("uid") })
            assertEquals(1, prefs.getInt("index", -1))
            assertTrue("退出前=$beforeExitPosition，持久化=${prefs.getLong("positionMs", -1L)}",
                kotlin.math.abs(prefs.getLong("positionMs", -1L) - beforeExitPosition) <= 1_000L)
            main {
                assertEquals(3, player.mediaItemCount)
                assertFalse(player.playWhenReady)
            }

            // 已解绑后的重复退出无 Player，必须幂等且不能把已保存断点覆盖为 0。
            val savedPosition = prefs.getLong("positionMs", -1L)
            main { PlaybackController.exitPlayback() }
            assertEquals(saved.toString(), JSONArray(prefs.getString("queue", "[]")).toString())
            assertEquals(1, prefs.getInt("index", -1))
            assertEquals(savedPosition, prefs.getLong("positionMs", -1L))
        }

    @Test fun serviceExitSnapshotRestoresForNextControllerConnection() =
        withPlayer(listen = true) { player, controller ->
            main { PlaybackController.addToQueue(testContext, listOf(track("c"))) }
            await { player.mediaItemCount == 3 }
            main { PlaybackController.jumpTo(1) }
            await { player.currentMediaItemIndex == 1 && player.playbackState == Player.STATE_READY }
            main {
                controller.pause()
                controller.seekTo(31_000L)
            }
            await { !player.playWhenReady && player.currentPosition in 30_800L..31_200L }
            val beforeExitPosition = main { player.currentPosition }
            main {
                field("controllerFuture").set(PlaybackController, Futures.immediateFuture(controller))
                PlaybackController.exitPlayback(player)
            }
            await { !controller.isConnected }
            assertTrue(kotlin.math.abs(prefs.getLong("positionMs", -1L) - beforeExitPosition) <= 1_000L)

            // 冷重建一个新的本地 MediaSession/Controller；只恢复队列快照，不触发网络解析。
            withFreshController { _, restored ->
                restoreQueue(restored, autoPlay = false)
                await { restored.mediaItemCount == 3 && restored.currentMediaItemIndex == 1 }
                main {
                    assertEquals(listOf("a", "b", "c"), uids(restored))
                    assertEquals("b", restored.currentMediaItem?.mediaId)
                    assertTrue(kotlin.math.abs(restored.currentPosition - beforeExitPosition) <= 1_000L)
                    assertFalse(restored.playWhenReady)
                    assertEquals(Player.STATE_IDLE, restored.playbackState)
                }
            }
        }

    @Test fun periodicQueueCheckpointSurvivesColdRestoreWithoutExitSaving() = withPlayer { player, _ ->
        val rememberProgress = main {
            MeloraSettings.rememberProgress.value.also { MeloraSettings.rememberProgress.value = true }
        }
        try {
            main { PlaybackController.saveQueue(force = true, player = player) }
            val queueJson = prefs.getString("queue", null)
            main { player.seekTo(37_000) }
            await { player.currentPosition == 37_000L }
            main { PlaybackController.checkpointQueuePosition(player) }
            assertEquals("不能重写整份队列", queueJson, prefs.getString("queue", null))
            assertEquals(37_000L, prefs.getLong("positionMs", -1L))
            // 不执行exitPlayback/saveQueue；仅从已落盘快照新建会话，模拟异常终止后的冷恢复。
            withFreshController { _, restored ->
                restoreQueue(restored, autoPlay = false)
                await { restored.mediaItemCount == 2 }
                main {
                    assertEquals("a", restored.currentMediaItem?.mediaId)
                    assertEquals(37_000L, restored.currentPosition)
                    assertFalse(restored.playWhenReady)
                }
            }
        } finally { main { MeloraSettings.rememberProgress.value = rememberProgress } }
    }

    @Test fun periodicPositionCannotBeWrittenIntoAnotherSongsSnapshot() = withPlayer { player, _ ->
        main { PlaybackController.saveQueue(force = true, player = player) }
        val before = prefs.getLong("positionMs", -1L)
        main { player.seekTo(1, 37_000) }
        await { player.currentMediaItemIndex == 1 && player.currentPosition == 37_000L }
        main { PlaybackController.checkpointQueuePosition(player) }
        assertEquals(0, prefs.getInt("index", -1))
        assertEquals("结构快照还是A，不能写入B的37秒", before, prefs.getLong("positionMs", -1L))
        main {
            PlaybackController.saveQueue(player = player)
            player.seekTo(41_000)
        }
        await { player.currentPosition == 41_000L }
        main { PlaybackController.checkpointQueuePosition(player) }
        assertEquals(1, prefs.getInt("index", -1))
        assertEquals(41_000L, prefs.getLong("positionMs", -1L))
    }

    @Test fun periodicCheckpointDoesNotEnumerateQueueAndDoesNotRewriteUnchangedPosition() = withPlayer { player, _ ->
        main { PlaybackController.saveQueue(force = true, player = player); player.seekTo(37_000) }
        await { player.currentPosition == 37_000L }
        val changedKeys = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changedKeys += key }
        main {
            val bounded = object : ForwardingPlayer(player) {
                override fun getMediaItemCount(): Int = error("定时位置保存不能读取或遍历全队列")
                override fun getMediaItemAt(index: Int): MediaItem = error("定时位置保存不能读取或遍历全队列")
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            try {
                PlaybackController.checkpointQueuePosition(bounded)
                PlaybackController.checkpointQueuePosition(bounded)
                assertEquals(listOf("positionMs"), changedKeys)
            } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    @Test fun restoreQueueRestoresPausedShortMusicSnapshotAtNonFirstIndex() = withPlayer(empty = true) { _, controller ->
        val audio = silentWav()
        val previousRememberProgress = main {
            MeloraSettings.rememberProgress.value.also { MeloraSettings.rememberProgress.value = true }
        }
        try {
            // 60秒本地静音音频低于逐曲长音乐阈值；此处只验证队列快照当前位置。
            val tracks = listOf(track("short-first"), fileTrack(audio, "short-selected"))
            writeQueueSnapshot(tracks, index = 1, positionMs = 17_000L)
            restoreQueue(controller, autoPlay = false)
            await { controller.mediaItemCount == 2 && controller.currentMediaItemIndex == 1 }
            main {
                assertEquals("短歌队列快照", tracks[1].uid, controller.currentMediaItem?.mediaId)
                assertTrue("应恢复队列当前位置=${controller.currentPosition}",
                    kotlin.math.abs(controller.currentPosition - 17_000L) <= 1_000L)
                assertFalse(controller.playWhenReady)
                assertEquals(Player.STATE_IDLE, controller.playbackState)
            }
        } finally {
            main { MeloraSettings.rememberProgress.value = previousRememberProgress }
            audio.delete()
        }
    }

    @Test fun restoreQueueAutoPlayTruePreparesSavedLocalTrack() = withPlayer(empty = true) { player, controller ->
        val audio = silentWav()
        val previousRememberProgress = main {
            MeloraSettings.rememberProgress.value.also { MeloraSettings.rememberProgress.value = true }
        }
        try {
            val selected = fileTrack(audio, "autoplay-selected")
            writeQueueSnapshot(listOf(track("autoplay-first"), selected), index = 1, positionMs = 4_000L)
            restoreQueue(controller, autoPlay = true)
            await { player.currentMediaItem?.mediaId == selected.uid && player.playbackState == Player.STATE_READY && player.isPlaying }
            main {
                assertEquals(1, player.currentMediaItemIndex)
                assertTrue(player.playWhenReady)
                assertTrue("autoPlay=true must prepare the saved position", player.currentPosition >= 3_500L)
            }
        } finally {
            main {
                player.pause()
                MeloraSettings.rememberProgress.value = previousRememberProgress
            }
            audio.delete()
        }
    }

    @Test fun restoreQueueLegacySnapshotWithoutPositionUsesDefaultStart() = withPlayer(empty = true) { _, controller ->
        val previousRememberProgress = main {
            MeloraSettings.rememberProgress.value.also { MeloraSettings.rememberProgress.value = true }
        }
        try {
            writeQueueSnapshot(listOf(track("legacy-first"), track("legacy-selected")), index = 1, positionMs = null)
            restoreQueue(controller, autoPlay = false)
            await { controller.mediaItemCount == 2 && controller.currentMediaItemIndex == 1 }
            main {
                assertEquals("legacy-selected", controller.currentMediaItem?.mediaId)
                assertEquals(0L, controller.currentPosition)
                assertFalse(controller.playWhenReady)
                assertEquals(Player.STATE_IDLE, controller.playbackState)
            }
        } finally {
            main { MeloraSettings.rememberProgress.value = previousRememberProgress }
        }
    }

    @Test fun restoreQueueIgnoresSavedPositionWhenRememberProgressIsDisabled() = withPlayer(empty = true) { _, controller ->
        val previousRememberProgress = main {
            MeloraSettings.rememberProgress.value.also { MeloraSettings.rememberProgress.value = false }
        }
        try {
            writeQueueSnapshot(listOf(track("disabled-first"), track("disabled-selected")), index = 1, positionMs = 26_000L)
            restoreQueue(controller, autoPlay = false)
            await { controller.mediaItemCount == 2 && controller.currentMediaItemIndex == 1 }
            main {
                assertEquals("disabled-selected", controller.currentMediaItem?.mediaId)
                assertEquals("记忆关闭时队列索引仍恢复", 1, controller.currentMediaItemIndex)
                assertEquals("记忆关闭时不能恢复保存的位置", 0L, controller.currentPosition)
                assertFalse(controller.playWhenReady)
                assertEquals(Player.STATE_IDLE, controller.playbackState)
            }
        } finally {
            main { MeloraSettings.rememberProgress.value = previousRememberProgress }
        }
    }

    @Test fun externalTimelineClearIsPersistedAfterCallbacksSettle() = withPlayer(listen = true) { player, controller ->
        main { PlaybackController.addToQueue(testContext, listOf(track("c"))) }
        await { player.mediaItemCount == 3 }
        val lyricJob = Job()
        val lyricState = field("_lyric").get(PlaybackController) as MutableStateFlow<PlayerLyric?>
        main {
            field("lyricJob").set(PlaybackController, lyricJob)
            lyricState.value = PlayerLyric("a", "a", "artist", listOf(LyricLine(0, "old")), "test")
            player.clearMediaItems()
        }
        await { controller.mediaItemCount == 0 && PlaybackController.state.value.current == null }
        instrumentation.waitForIdleSync()
        main {
            assertTrue(lyricJob.isCancelled)
            assertNull(field("lyricJob").get(PlaybackController))
            assertNull(field("detailUid").get(PlaybackController))
            assertNull(lyricState.value)
        }
        assertFalse("外部清空不能留下待恢复的旧队列", prefs.contains("queue"))
    }

    @Test fun removingLastQueueItemCancelsCurrentLyricLoadThroughPublish() = withPlayer(listen = true) { _, _ ->
        val lyricJob = Job()
        val lyricState = field("_lyric").get(PlaybackController) as MutableStateFlow<PlayerLyric?>
        main {
            field("lyricJob").set(PlaybackController, lyricJob)
            lyricState.value = PlayerLyric("a", "a", "artist", listOf(LyricLine(0, "old")), "test")
            PlaybackController.removeFromQueue(1)
            PlaybackController.removeFromQueue(0)
        }
        await {
            PlaybackController.state.value.current == null &&
                lyricJob.isCancelled && field("lyricJob").get(PlaybackController) == null &&
                field("detailUid").get(PlaybackController) == null && lyricState.value == null
        }
        main {
            // 空队列重复发布/清理应幂等，不得重新挂起旧身份。
            PlaybackController.javaClass.getDeclaredMethod("publish").apply { isAccessible = true }
                .invoke(PlaybackController)
            PlaybackController.clearQueue()
            assertTrue(lyricJob.isCancelled)
            assertNull(field("lyricJob").get(PlaybackController))
            assertNull(field("detailUid").get(PlaybackController))
            assertNull(lyricState.value)
        }
    }

    @Test fun disconnectInvalidatesControllerButKeepsSavedQueueForRecovery() = withPlayer(listen = true) { _, controller ->
        main { PlaybackController.addToQueue(testContext, listOf(track("c"))) }
        val saved = prefs.getString("queue", null)
        val positionJob = main {
            assertFalse("fixture stays paused", controller.isPlaying)
            (field("positionJob").get(PlaybackController) as? Job)
                ?: error("connected controller should own a position sampler")
        }
        assertTrue("sampling remains active while paused", positionJob.isActive)
        main { controller.release() }
        await {
            field("controller").get(PlaybackController) == null &&
                field("positionJob").get(PlaybackController) == null && positionJob.isCancelled
        }
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

    private fun installProjectionQueue(player: ExoPlayer, controller: MediaController) {
        val audioUri = main { player.getMediaItemAt(0).localConfiguration!!.uri }
        val ids = listOf("repeat", "b", "repeat", "d")
        main {
            TrackRegistry.registerAll(ids.distinct().map(::track))
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
            controller.setMediaItems(
                ids.map { MediaItem.Builder().setMediaId(it).setUri(audioUri).build() },
                /* startIndex= */ 1,
                /* startPositionMs= */ 12_000L,
            )
            controller.prepare()
        }
        await {
            player.playbackState == Player.STATE_READY &&
                controller.playbackState == Player.STATE_READY &&
                player.currentMediaItemIndex == 1 && player.currentPosition in 11_800L..12_200L
        }
        main { player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(2, 0, 3, 1), 7L)) }
        await {
            controller.currentTimeline.getFirstWindowIndex(/* shuffleModeEnabled= */ true) == 2 &&
                PlaybackController.state.value.queueOrder == listOf(0, 1, 2, 3)
        }
    }

    private fun restoreQueue(controller: MediaController, autoPlay: Boolean) {
        main {
            PlaybackController.javaClass
                .getDeclaredMethod("restoreQueue", MediaController::class.java, Boolean::class.javaPrimitiveType!!)
                .apply { isAccessible = true }
                .invoke(PlaybackController, controller, autoPlay)
        }
    }

    private fun writeQueueSnapshot(tracks: List<UiTrack>, index: Int, positionMs: Long?) {
        val queue = JSONArray().apply {
            tracks.forEach { track ->
                put(JSONObject()
                    .put("uid", track.uid)
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("album", track.album)
                    .put("source", track.source)
                    .apply {
                        track.artwork?.let { put("artwork", it) }
                        track.raw?.let { put("raw", it) }
                    })
            }
        }
        prefs.edit()
            .putString("queue", queue.toString())
            .putInt("index", index)
            .apply {
                if (positionMs == null) remove("positionMs") else putLong("positionMs", positionMs)
            }
            .commit()
    }

    private fun fileTrack(file: File, title: String): UiTrack = UiTrack(
        uid = Uri.fromFile(file).toString(),
        title = title,
        artist = "测试歌手",
        album = "测试专辑",
    )

    private fun withFreshController(test: (ExoPlayer, MediaController) -> Unit) {
        val player = main { ExoPlayer.Builder(context).build().apply { volume = 0f } }
        val session = main { MediaSession.Builder(context, player).setId("queue-restore-${System.nanoTime()}").build() }
        val controller = main {
            MediaController.Builder(context, session.token).buildAsync()
        }.get(5, TimeUnit.SECONDS)
        try {
            test(player, controller)
        } finally {
            main {
                controller.release()
                session.release()
                player.release()
            }
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
        val fields = listOf("controller", "controllerFuture", "bookQueue", "appContext", "lastSavedQueue", "detailUid", "currentQueueId", "playbackPreflight", "queueLoadJob", "pendingPlayback", "lyricJob", "positionJob").associateWith(::field)
        val previous = main { fields.mapValues { it.value.get(PlaybackController) } }
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(PlaybackController) as MutableStateFlow<PlayerUiState>
        val previousState = state.value
        @Suppress("UNCHECKED_CAST")
        val lyricState = field("_lyric").get(PlaybackController) as MutableStateFlow<PlayerLyric?>
        val previousLyric = lyricState.value
        val previousController = previous["controller"] as? MediaController
        val savedPreferences = prefs.all
        prefs.edit().clear().commit()
        try {
            main {
                (fields.getValue("positionJob").get(PlaybackController) as? Job)?.cancel()
                fields.getValue("positionJob").set(PlaybackController, null)
                fields.getValue("controller").set(PlaybackController, controller)
                fields.getValue("appContext").set(PlaybackController, testContext.applicationContext)
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
                (fields.getValue("lyricJob").get(PlaybackController) as? Job)
                    ?.takeIf { it !== previous["lyricJob"] }?.cancel()
                (fields.getValue("positionJob").get(PlaybackController) as? Job)?.cancel()
                fields.forEach { (name, field) ->
                    if (name != "positionJob") field.set(PlaybackController, previous[name])
                }
                fields.getValue("positionJob").set(PlaybackController, null)
                state.value = previousState
                lyricState.value = previousLyric
                if (previousController?.isConnected == true) {
                    PlaybackController.javaClass.getDeclaredMethod("attach", MediaController::class.java)
                        .apply { isAccessible = true }.invoke(PlaybackController, previousController)
                }
                controller.release(); session.release(); player.release()
            }
            prefs.edit().clear().apply {
                savedPreferences.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
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
