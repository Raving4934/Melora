package com.leyu.melora.playback

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 Media3 播放链路上的播放进度回归测试；音频只来自本地生成的静音 WAV。 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackProgressInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val progressPrefs: SharedPreferences
        get() = context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)

    private lateinit var shortWav: File
    private lateinit var longWav: File

    @Before
    fun setUp() {
        progressPrefs.edit().clear().commit()
        shortWav = createSilentWav(SHORT_DURATION_MS)
        longWav = createSilentWav(LONG_DURATION_MS)
    }

    @After
    fun tearDown() {
        progressPrefs.edit().clear().commit()
        if (::shortWav.isInitialized) shortWav.delete()
        if (::longWav.isInitialized) longWav.delete()
    }

    @Test
    fun manualQueueReplacementSavesOldTrackBeforeNewSelection() {
        val oldTrack = onlineTrack("test_old", interval = null)
        val newTrack = onlineTrack("test_new", interval = null)
        val tracks = mapOf(oldTrack.uid to oldTrack, newTrack.uid to newTrack)

        withSession { player, controller ->
            val progress = progressFor(player, tracks)
            try {
                prepare(controller, listOf(oldTrack to longWav, newTrack to longWav))
                seekPaused(controller, 47_000L)

                // 模拟歌单重换/用户立即点选新曲；不能把旧曲位置写到新 UID。
                onMain {
                    controller.setMediaItems(
                        listOf(mediaItem(newTrack, longWav)),
                        0,
                        C.TIME_UNSET,
                    )
                    controller.prepare()
                }
                await {
                    controller.currentMediaItem?.mediaId == newTrack.uid &&
                        controller.playbackState == Player.STATE_READY
                }
                awaitSaved(oldTrack.uid, 46_000L, 49_000L)
                assertFalse("新选曲不应继承旧曲的 UID 进度", progressPrefs.contains(newTrack.uid))
            } finally {
                onMain { progress.close() }
            }
        }
    }

    @Test
    fun controllerJumpNextPreviousUseInjectedControllerAndPrepareIdleQueue() {
        val first = onlineTrack("test_first", interval = null)
        val selected = onlineTrack("test_selected", interval = null)
        val last = onlineTrack("test_last", interval = null)
        val tracks = mapOf(first.uid to first, selected.uid to selected, last.uid to last)
        progressPrefs.edit()
            .putLong(selected.uid, 42_000L)
            .putLong(durationKey(selected.uid), LONG_DURATION_MS)
            .commit()

        withSession { player, controller ->
            withInjectedController(controller) {
                val progress = progressFor(player, tracks)
                try {
                    prepare(
                        controller,
                        listOf(first to longWav, selected to longWav, last to longWav),
                    )
                    // 冷启动恢复队列通常保持 IDLE；jumpTo 必须自己 prepareAndPlay。
                    onMain { player.stop() }
                    await {
                        player.playbackState == Player.STATE_IDLE && controller.mediaItemCount == 3
                    }

                    onMain { PlaybackController.jumpTo(1) }
                    await {
                        controller.currentMediaItem?.mediaId == selected.uid &&
                            controller.playbackState == Player.STATE_READY &&
                            near(controller.currentPosition, 42_000L, 1_500L)
                    }
                    onMain { controller.pause() }

                    onMain { PlaybackController.next() }
                    await {
                        controller.currentMediaItem?.mediaId == last.uid &&
                            controller.playbackState == Player.STATE_READY
                    }
                    onMain { controller.pause() }

                    // 上一首入口从 last 回到 selected，并应按历史位置恢复一次。
                    onMain { PlaybackController.previous() }
                    await {
                        controller.currentMediaItem?.mediaId == selected.uid &&
                            near(controller.currentPosition, 42_000L, 1_500L)
                    }
                    onMain { controller.pause() }

                    // 已超过3秒时再次点上一首是显式 seek(0)，不得又被历史位置拉回。
                    onMain { PlaybackController.previous() }
                    await {
                        controller.currentMediaItem?.mediaId == selected.uid &&
                            controller.playbackState == Player.STATE_READY &&
                            controller.currentPosition < 1_500L
                    }
                    Thread.sleep(300L)
                    assertTrue(
                        "从0重播不能回跳到历史进度",
                        onMain { controller.currentPosition < 3_000L },
                    )
                    onMain { controller.pause() }
                    assertTrue(progressPrefs.getLong(selected.uid, 0L) < 3_000L)
                } finally {
                    onMain { progress.close() }
                }
            }
        }
    }

    @Test
    fun automaticAdvanceRestoresNextAndClearsFinishedTrack() {
        val first = onlineTrack("test_auto_first", book = true, interval = "00:08")
        val next = onlineTrack("test_auto_next", book = true, interval = "12:00")
        val tracks = mapOf(first.uid to first, next.uid to next)
        progressPrefs.edit().putLong(next.uid, 45_000L).commit()

        withSession { player, controller ->
            val progress = progressFor(player, tracks)
            try {
                prepare(controller, listOf(first to shortWav, next to longWav))
                val autoTransition = CountDownLatch(1)
                onMain {
                    controller.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                                autoTransition.countDown()
                            }
                        }
                    })
                    controller.seekTo(SHORT_DURATION_MS - 3_000L)
                    controller.play()
                }

                assertTrue("静音本地 WAV 应能自动切到下一首", autoTransition.await(8, TimeUnit.SECONDS))
                await { controller.currentMediaItem?.mediaId == next.uid && near(controller.currentPosition, 45_000L, 1_500L) }
                awaitSaved(next.uid, 44_000L, 47_000L)
                assertFalse("已播到近尾的旧曲进度应被清除", progressPrefs.contains(first.uid))
            } finally {
                onMain { progress.close() }
            }
        }
    }

    @Test
    fun pauseFlushesCurrentPositionWithoutAnExplicitCheckpoint() {
        val track = onlineTrack("test_pause", interval = null)
        val tracks = mapOf(track.uid to track)

        withSession { player, controller ->
            val progress = progressFor(player, tracks)
            try {
                prepare(controller, listOf(track to longWav))
                onMain {
                    controller.seekTo(40_000L)
                    controller.play()
                }
                await { player.isPlaying && player.currentPosition >= 40_000L }
                val beforePause = progressPrefs.getLong(track.uid, 0L)
                await { player.currentPosition > beforePause + 800L }

                onMain { controller.pause() }
                await {
                    !controller.playWhenReady &&
                        progressPrefs.contains(track.uid) &&
                        progressPrefs.getLong(track.uid, 0L) > beforePause + 750L
                }
                val saved = progressPrefs.getLong(track.uid, 0L)
                val currentPosition = onMain { controller.currentPosition }
                val duration = onMain { controller.duration }
                assertTrue("暂停保存的位置不能超过实际位置太多", saved <= currentPosition + 1_500L)
                assertEquals(duration, progressPrefs.getLong(durationKey(track.uid), 0L))
            } finally {
                onMain { progress.close() }
            }
        }
    }

    @Test
    fun coldRebuildRestoresLongTrackUsingActualDurationWhenCatalogHasNone() {
        val track = onlineTrack("test_missing_catalog_duration", interval = null)
        val tracks = mapOf(track.uid to track)

        withSession { player, controller ->
            val progress = progressFor(player, tracks)
            try {
                prepare(controller, listOf(track to longWav))
                seekPaused(controller, 60_000L)
                onMain { progress.checkpoint() }
                awaitSaved(track.uid, 59_000L, 61_500L)
                val duration = onMain { controller.duration }
                assertEquals(duration, progressPrefs.getLong(durationKey(track.uid), 0L))
                assertTrue(
                    "测试 WAV 的实际时长必须达到长音频恢复阈值",
                    progressPrefs.getLong(durationKey(track.uid), 0L) >= LONG_TRACK_THRESHOLD_MS,
                )
            } finally {
                onMain { progress.close() }
            }
        }

        // 新的 MediaSession/MediaController/ExoPlayer 即冷重建，目录仍不提供 interval。
        withSession { player, controller ->
            val progress = progressFor(player, tracks)
            try {
                // 和启动后不自动播放一样，先只恢复队列，不触发音频加载。
                onMain { controller.setMediaItems(listOf(mediaItem(track, longWav))) }
                await { player.playbackState == Player.STATE_IDLE && near(player.currentPosition, 60_000L, 1_500L) }
                assertFalse(onMain { player.playWhenReady })
                onMain { controller.prepare() }
                await { player.playbackState == Player.STATE_READY && near(player.currentPosition, 60_000L, 1_500L) }
            } finally {
                onMain { progress.close() }
            }
        }
    }

    @Test
    fun rememberProgressOffSkipsBothSavingAndRestoring() {
        val track = onlineTrack("test_disabled", interval = null)
        val tracks = mapOf(track.uid to track)
        var enabled = false

        withSession { player, controller ->
            val progress = progressFor(player, tracks) { enabled }
            try {
                prepare(controller, listOf(track to longWav))
                seekPaused(controller, 45_000L)
                onMain { progress.checkpoint() }
                assertFalse("关闭记忆进度时不应新写 UID 进度", progressPrefs.contains(track.uid))
            } finally {
                onMain { progress.close() }
            }
        }

        progressPrefs.edit()
            .putLong(track.uid, 45_000L)
            .putLong(durationKey(track.uid), LONG_DURATION_MS)
            .commit()
        withSession { player, controller ->
            val progress = progressFor(player, tracks) { enabled }
            try {
                prepare(controller, listOf(track to longWav))
                assertTrue(
                    "关闭记忆进度时冷重建不能恢复旧位置",
                    onMain { controller.currentPosition < 1_500L },
                )
            } finally {
                onMain { progress.close() }
            }
        }
    }

    @Test
    fun manualSeekOverridesRestoreAndMetadataReplacementDoesNotRestoreAgain() {
        val track = onlineTrack("test_manual_seek", interval = null)
        val tracks = mapOf(track.uid to track)
        val restoredPosition = 75_000L
        progressPrefs.edit()
            .putLong(track.uid, restoredPosition)
            .putLong(durationKey(track.uid), LONG_DURATION_MS)
            .commit()

        withSession { player, controller ->
            val progress = progressFor(player, tracks)
            try {
                prepare(controller, listOf(track to longWav))
                await { near(controller.currentPosition, restoredPosition, 1_500L) }

                onMain { controller.seekTo(15_000L) }
                await { near(controller.currentPosition, 15_000L, 1_000L) }
                Thread.sleep(300L)
                assertTrue(
                    "用户手动 seek 后不能被恢复逻辑拉回旧位置",
                    onMain { controller.currentPosition < 30_000L },
                )

                // 保留旧快照，专门验证 metadata-only replace 不会再次触发恢复。
                progressPrefs.edit().putLong(track.uid, restoredPosition).commit()
                val replacement = mediaItem(track, longWav, title = "metadata-replaced")
                onMain { controller.replaceMediaItem(0, replacement) }
                await {
                    controller.currentMediaItem?.mediaMetadata?.title?.toString() == "metadata-replaced"
                }
                Thread.sleep(300L)
                assertTrue(
                    "仅替换 metadata 不应再次 seek 到持久化位置",
                    onMain { near(controller.currentPosition, 15_000L, 2_500L) },
                )
            } finally {
                onMain { progress.close() }
            }
        }
    }

    @Test
    fun repeatOneDoesNotJumpBackToSavedPosition() {
        val track = onlineTrack("test_repeat", book = true, interval = null)
        val tracks = mapOf(track.uid to track)
        val savedPosition = 30_000L
        progressPrefs.edit()
            .putLong(track.uid, savedPosition)
            .putLong(durationKey(track.uid), LONG_DURATION_MS)
            .commit()

        withSession { player, controller ->
            val progress = progressFor(player, tracks)
            try {
                prepare(controller, listOf(track to longWav))
                await { near(controller.currentPosition, savedPosition, 1_500L) }
                val repeated = CountDownLatch(1)
                val repeatStart = onMain { controller.duration - 1_000L }
                onMain {
                    controller.addListener(object : Player.Listener {
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeated.countDown()
                        }
                    })
                    controller.repeatMode = Player.REPEAT_MODE_ONE
                    controller.seekTo(repeatStart)
                    controller.play()
                }

                assertTrue("REPEAT_MODE_ONE 应真实循环本地静音 WAV", repeated.await(8, TimeUnit.SECONDS))
                await { controller.currentMediaItemIndex == 0 && controller.currentPosition < 5_000L }
                Thread.sleep(300L)
                assertTrue(
                    "单曲循环回到开头时不能再次跳回旧进度",
                    onMain { controller.currentPosition < 8_000L },
                )
            } finally {
                onMain { progress.close() }
            }
        }
    }

    @Test
    fun nearEndCheckpointRemovesUidProgressButKeepsActualDuration() {
        val track = onlineTrack("test_near_end", book = true, interval = "00:08")
        val tracks = mapOf(track.uid to track)

        withSession { player, controller ->
            val progress = progressFor(player, tracks)
            try {
                prepare(controller, listOf(track to shortWav))
                seekPaused(controller, SHORT_DURATION_MS - 1_000L)
                onMain { progress.checkpoint() }
                await { !progressPrefs.contains(track.uid) }
                val duration = onMain { controller.duration }
                assertEquals(duration, progressPrefs.getLong(durationKey(track.uid), 0L))
            } finally {
                onMain { progress.close() }
            }
        }
    }

    @Test
    fun closeFlushesLastPositionAndDetachesListener() {
        val track = onlineTrack("test_close", interval = null)
        val tracks = mapOf(track.uid to track)

        withSession { player, controller ->
            val progress = progressFor(player, tracks)
            prepare(controller, listOf(track to longWav))
            seekPaused(controller, 25_000L)
            onMain { controller.play() }
            await { player.isPlaying && player.currentPosition >= 25_000L }
            val beforeClose = progressPrefs.getLong(track.uid, 0L)
            await { player.currentPosition > beforeClose + 800L }
            onMain { progress.close() }

            awaitSaved(track.uid, beforeClose + 750L, beforeClose + 3_000L)
            val savedAtClose = progressPrefs.getLong(track.uid, 0L)

            // close 后再发生 seek/播放器事件，不应继续改写偏好。
            seekPaused(controller, 45_000L)
            assertEquals(savedAtClose, progressPrefs.getLong(track.uid, 0L))
        }
    }

    @Test
    fun rapidSelectionsKeepEachTracksOwnCheckpoint() {
        val tracks = listOf("rapid-a", "rapid-b", "rapid-c").map { onlineTrack(it) }
        progressPrefs.edit().putLong(tracks[1].uid, 30_000L).putLong(tracks[2].uid, 45_000L).commit()
        withSession { player, controller ->
            val progress = progressFor(player, tracks.associateBy { it.uid })
            try {
                prepare(controller, tracks.map { it to longWav })
                seekPaused(controller, 17_000L)
                onMain {
                    controller.seekToDefaultPosition(1)
                    controller.seekToDefaultPosition(2)
                }
                await { controller.currentMediaItemIndex == 2 && near(controller.currentPosition, 45_000L, 1_000L) }
                awaitSaved(tracks[0].uid, 16_500L, 17_500L)
                awaitSaved(tracks[1].uid, 29_500L, 30_500L)
                awaitSaved(tracks[2].uid, 44_500L, 45_500L)
            } finally { onMain { progress.close() } }
        }
    }

    @Test
    fun legacyBookmarkWithoutDurationWaitsForActualLongTrackTimeline() {
        val track = onlineTrack("legacy-duration", interval = null)
        progressPrefs.edit().putLong(track.uid, 60_000L).commit()
        withSession { player, controller ->
            val progress = progressFor(player, mapOf(track.uid to track))
            try {
                prepare(controller, listOf(track to longWav))
                await { near(player.currentPosition, 60_000L, 1_000L) }
                assertEquals(LONG_DURATION_MS, progressPrefs.getLong(durationKey(track.uid), 0L))
            } finally { onMain { progress.close() } }
        }
    }

    @Test
    fun explicitSeekWhilePreparingWinsOverPendingUnknownDurationRestore() {
        val track = onlineTrack("preparation-seek", interval = null)
        progressPrefs.edit().putLong(track.uid, 75_000L).commit()
        withSession { player, controller ->
            val progress = progressFor(player, mapOf(track.uid to track))
            try {
                onMain {
                    player.setMediaItems(listOf(mediaItem(track, longWav)))
                    player.seekTo(15_000L)
                    player.prepare()
                }
                await { controller.playbackState == Player.STATE_READY && near(controller.currentPosition, 15_000L, 1_000L) }
                awaitSaved(track.uid, 14_500L, 15_500L)
            } finally { onMain { progress.close() } }
        }
    }

    private fun withInjectedController(controller: MediaController, block: () -> Unit) {
        val controllerField = PlaybackController.javaClass
            .getDeclaredField("controller")
            .apply { isAccessible = true }
        val previousController = controllerField.get(PlaybackController)
        try {
            onMain { controllerField.set(PlaybackController, controller) }
            block()
        } finally {
            onMain { controllerField.set(PlaybackController, previousController) }
        }
    }

    private fun progressFor(
        player: Player,
        tracks: Map<String, UiTrack>,
        isEnabled: () -> Boolean = { true },
    ): PlaybackProgress = onMain {
        PlaybackProgress(
            player = player,
            prefs = progressPrefs,
            trackFor = { uid -> tracks[uid] },
            isEnabled = isEnabled,
        )
    }

    private fun prepare(
        controller: MediaController,
        queue: List<Pair<UiTrack, File>>,
        startIndex: Int = 0,
    ) {
        onMain {
            controller.setMediaItems(
                queue.map { (track, file) -> mediaItem(track, file) },
                startIndex,
                C.TIME_UNSET,
            )
            controller.prepare()
        }
        await {
            controller.mediaItemCount == queue.size &&
                controller.playbackState == Player.STATE_READY &&
                controller.duration > 0L
        }
    }

    private fun seekPaused(controller: MediaController, positionMs: Long) {
        onMain {
            controller.pause()
            controller.seekTo(positionMs)
        }
        await {
            !controller.playWhenReady && near(controller.currentPosition, positionMs, 750L)
        }
    }

    private fun awaitSaved(uid: String, lower: Long, upper: Long) {
        await {
            progressPrefs.contains(uid) && progressPrefs.getLong(uid, 0L) in lower..upper
        }
    }

    private fun await(timeoutMs: Long = 8_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!onMain(predicate)) {
            assertTrue("Media3 状态等待超时", SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(10L)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private fun withSession(test: (ExoPlayer, MediaController) -> Unit) {
        lateinit var player: ExoPlayer
        lateinit var session: MediaSession
        var playerCreated = false
        var sessionCreated = false
        var controller: MediaController? = null
        try {
            onMain {
                player = ExoPlayer.Builder(context)
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(1_000, 2_000, 100, 100)
                            .build(),
                    )
                    .build()
                    .apply { volume = 0f }
                playerCreated = true
                session = MediaSession.Builder(context, player)
                    .setId("progress-test-${System.nanoTime()}")
                    .build()
                sessionCreated = true
            }
            val future = onMain {
                MediaController.Builder(context, session.token).buildAsync()
            }
            controller = future.get(5, TimeUnit.SECONDS)
            test(player, controller!!)
        } finally {
            onMain {
                controller?.release()
                if (sessionCreated) session.release()
                if (playerCreated) player.release()
            }
        }
    }

    private fun mediaItem(track: UiTrack, file: File, title: String = track.title): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.uid)
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .build(),
            )
            .build()

    private fun onlineTrack(
        uid: String,
        book: Boolean = false,
        interval: String? = "12:00",
    ): UiTrack {
        val raw = JSONObject()
            .put("source", "test")
            .put("songmid", uid)
            .put("name", uid)
            .put("singer", "Instrumented Test")
            .put("albumName", "Local Silent WAV")
        if (book) raw.put("isBookChapter", true)
        if (interval != null) raw.put("interval", interval)
        return UiTrack(
            uid = uid,
            title = uid,
            artist = "Instrumented Test",
            album = "Local Silent WAV",
            source = "test",
            raw = raw,
        )
    }

    private fun createSilentWav(durationMs: Long): File {
        require(durationMs % 1_000L == 0L)
        val file = File(context.cacheDir, "playback-progress-${durationMs}-${System.nanoTime()}.wav")
        val dataSize = (SAMPLE_RATE * (durationMs / 1_000L) * BYTES_PER_SAMPLE).toInt()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE)
            putShort((CHANNELS * BYTES_PER_SAMPLE).toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
        RandomAccessFile(file, "rw").use { output ->
            // 只扩展稀疏文件，不在测试进程里分配整段音频内存。
            output.setLength(44L + dataSize)
            output.seek(0L)
            output.write(header)
        }
        return file
    }

    private fun near(actual: Long, expected: Long, tolerance: Long): Boolean =
        abs(actual - expected) <= tolerance

    private fun durationKey(uid: String): String = "duration:$uid"

    companion object {
        private const val PROGRESS_PREFS = "playback-progress-test"
        private const val SAMPLE_RATE = 8_000
        private const val CHANNELS = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val BITS_PER_SAMPLE = 16
        private const val SHORT_DURATION_MS = 8_000L
        private const val LONG_DURATION_MS = 12 * 60 * 1_000L
        private const val LONG_TRACK_THRESHOLD_MS = 10 * 60 * 1_000L
    }
}
