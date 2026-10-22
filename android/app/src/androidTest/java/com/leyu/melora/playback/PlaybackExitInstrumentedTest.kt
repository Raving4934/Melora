package com.leyu.melora.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import androidx.media3.session.SessionResult
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真实Service/通知退出命令/同进程重新连接；仅独立测试包、本地静音音频，不依赖在线音源。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackExitInstrumentedTest {
    // Android 15+ 只允许前台应用/合规前台服务取得音频焦点；测试需提供真实前台宿主。
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("melora-queue", Context.MODE_PRIVATE)
    private lateinit var audio: File
    private var autoPlay = false
    private var rememberProgress = true
    private var librarySnapshot: String? = null

    @Before fun setUp() {
        check(context.packageName.endsWith(".debug")) { "Service tests require an isolated debug package" }
        UserLibrary.init(context)
        librarySnapshot = UserLibrary.exportSnapshot()
        main {
            autoPlay = MeloraSettings.autoPlayOnStart.value
            rememberProgress = MeloraSettings.rememberProgress.value
            MeloraSettings.autoPlayOnStart.value = false
            MeloraSettings.rememberProgress.value = true
            PlaybackController.clearQueue()
            PlaybackController.exitPlayback()
        }
        context.stopService(Intent(context, PlaybackService::class.java))
        await { !PlaybackService.isRunning }
        prefs.edit().clear().commit()
        val bytes = 8_000 * 2 * 60
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(bytes + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes)
        }.array()
        audio = File.createTempFile("exit-fixture", ".wav", context.cacheDir).apply {
            outputStream().use { it.write(header); it.write(ByteArray(bytes)) }
        }
        main { PlaybackController.init(context) }
        await { PlaybackController.state.value.ready }
        main {
            val tracks = listOf("first", "selected").map { id ->
                UiTrack("local_exit_$id", "Exit $id", "Fixture", "Fixture", "local", raw = JSONObject()
                    .put("source", "local").put("songmid", "exit_$id")
                    .put("localUri", Uri.fromFile(audio).toString()).put("name", "Exit $id")
                    .put("singer", "Fixture").put("interval", 60))
            }
            TrackRegistry.registerAll(tracks)
            controller().setMediaItems(tracks.map {
                MediaItem.Builder().setMediaId(it.uid).setUri(Uri.fromFile(audio)).build()
            }, 1, 12_000)
            controller().prepare()
        }
        await { controller().playbackState == Player.STATE_READY && controller().currentPosition == 12_000L }
    }

    @After fun tearDown() {
        // stopService不能销毁仍被Media3内部控制器绑定的服务；与被测退出同样释放session。
        main {
            MeloraSettings.autoPlayOnStart.value = false
            if (PlaybackService.isRunning && controllerOrNull() == null) PlaybackController.init(context)
        }
        if (main { PlaybackService.isRunning }) {
            await { controllerOrNull() != null }
            main { PlaybackController.clearQueue() }
            exitFromNotification()
        }
        main {
            PlaybackController.clearQueue()
            PlaybackController.exitPlayback()
            MeloraSettings.autoPlayOnStart.value = autoPlay
            MeloraSettings.rememberProgress.value = rememberProgress
        }
        prefs.edit().clear().commit()
        if (::audio.isInitialized) audio.delete()
        librarySnapshot?.let(UserLibrary::replaceFromBackup)
    }

    @Test fun continuousPlaybackKeepsLyricSamplesStableAcrossSessionPositionUpdates() {
        main { controller().volume = 0f; controller().play() }
        await { PlaybackController.state.value.positionAdvancing && controller().isPlaying }
        // 等待 AudioTrack 起播，随后跨过至少两个 MediaSession 周期校正点。
        Thread.sleep(800)
        var previous = main { lyricPositionAt(PlaybackController.state.value, SystemClock.elapsedRealtime()) }
        val first = previous
        var largestRetreat = 0L
        val deadline = SystemClock.elapsedRealtime() + 7_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = main { lyricPositionAt(PlaybackController.state.value, SystemClock.elapsedRealtime()) }
            largestRetreat = maxOf(largestRetreat, previous - current)
            previous = current
            Thread.sleep(16)
        }
        assertTrue("歌词时钟必须持续前进", previous > first + 6_000L)
        assertTrue("连续播放出现可见的周期校正回退: ${largestRetreat}ms", largestRetreat <= 32L)
        main { controller().pause(); controller().seekTo(1_000L) }
        await { !PlaybackController.state.value.positionAdvancing && PlaybackController.state.value.positionMs == 1_000L }
        main {
            assertEquals("真实向后Seek不能被当成抖动吞掉", 1_000L,
                lyricPositionAt(PlaybackController.state.value, SystemClock.elapsedRealtime() + 1_000L))
        }
    }

    @Test fun standardStopIsNotConsideredContinuableByTaskRemovalPolicy() {
        main { controller().play() }
        await { controller().playbackState == Player.STATE_READY && controller().isPlaying }
        main { controller().stop() }
        await { controller().playbackState == Player.STATE_IDLE }

        main {
            val player = controller()
            assertTrue("Media3 standard Stop retains playWhenReady", player.playWhenReady)
            assertEquals(2, player.mediaItemCount)
            assertFalse(player.canContinuePlaybackAfterTaskRemoved())
        }
    }

    @Test fun recreatedSessionDoesNotReuseRemovedNotificationIdentity() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun mediaNotifications() = notifications.activeNotifications.filter {
            it.notification.category == Notification.CATEGORY_TRANSPORT
        }
        main { controller().volume = 0f; controller().play() }
        await { controller().isPlaying && mediaNotifications().size == 1 }
        val previousId = main { mediaNotifications().single().id }
        main { controller().pause() }
        await { !controller().isPlaying && mediaNotifications().size == 1 }
        main { assertEquals("同一会话的通知更新不能换ID", previousId, mediaNotifications().single().id) }

        exitFromNotification()
        main { PlaybackController.init(context) }
        await { PlaybackController.state.value.ready }
        main { controller().volume = 0f; controller().play() }
        await { controller().isPlaying && mediaNotifications().size == 1 }
        main {
            assertNotEquals("旧通知的迟到移除不能命中新会话", previousId, mediaNotifications().single().id)
            // 旧通知的清理仍只能针对旧键，不能删除新会话的前台通知。
            notifications.cancel(previousId)
            assertEquals(1, mediaNotifications().size)
            assertTrue(controller().isPlaying)
        }
    }

    @Test fun notificationExitStopsServiceAndReopeningRestoresPausedMiniWithoutSelectingASong() {
        exitFromNotification()
        assertEquals(1, prefs.getInt("index", -1))
        assertEquals(12_000L, prefs.getLong("positionMs", -1))
        assertTrue(prefs.contains("queue"))
        main {
            assertNull(PlaybackController.state.value.current)
            assertFalse(PlaybackController.state.value.playing)
            // MainActivity.onResume使用的同一入口；Application没有重建。
            PlaybackController.init(context)
        }
        await { PlaybackController.state.value.current?.uid == "local_exit_selected" }
        main {
            assertEquals(2, PlaybackController.state.value.queue.size)
            assertEquals(12_000L, controller().currentPosition)
            assertFalse(controller().playWhenReady)
            assertEquals(Player.STATE_IDLE, controller().playbackState)
            val connected = controller()
            repeat(3) { PlaybackController.init(context) }
            assertSame("回到前台不能重置仍然连接的播放器", connected, controller())
            assertEquals(12_000L, controller().currentPosition)
        }
    }

    @Test fun notificationExitDoesNotAutoRestartUntilReopenAndThenHonorsAutoPlay() {
        main { MeloraSettings.autoPlayOnStart.value = true }
        exitFromNotification()
        Thread.sleep(400)
        main { assertFalse(PlaybackService.isRunning); assertNull(PlaybackController.state.value.current) }
        main { PlaybackController.init(context) }
        await { PlaybackController.state.value.playing && controller().playbackState == Player.STATE_READY }
        main {
            assertEquals("local_exit_selected", controller().currentMediaItem?.mediaId)
            assertTrue(controller().currentPosition >= 12_000L)
        }
    }

    @Test fun nextRequestAfterServiceRecreationKeepsRestoredPositionAndShuffle() {
        val mode = MeloraSettings.musicPlayMode.value
        try {
            main {
                MeloraSettings.updateMusicPlayMode(PlayMode.Shuffle)
                PlaybackController.applyMusicPlayMode()
            }
            await { controller().shuffleModeEnabled }
            exitFromNotification()
            main { PlaybackController.init(context) }
            await { PlaybackController.state.value.ready }
            main {
                assertTrue(controller().isSessionCommandAvailable(PlaybackSessionCallback.QueueNext))
                repeat(3) { PlaybackController.addToQueueNext(context, checkNotNull(TrackRegistry.get("local_exit_first"))) }
            }
            await {
                val player = controller()
                player.mediaItemCount == 2 && player.getMediaItemAt(0).mediaId == "local_exit_selected" &&
                    player.getMediaItemAt(1).mediaId == "local_exit_first" && player.nextMediaItemIndex == 1
            }
            main {
                assertTrue(PlaybackService.isRunning)
                assertTrue(controller().shuffleModeEnabled)
                assertEquals("local_exit_selected", controller().currentMediaItem?.mediaId)
                assertEquals(12_000L, controller().currentPosition)
                assertFalse(controller().playWhenReady)
                PlaybackController.next()
            }
            await { controller().isPlaying && controller().currentMediaItem?.mediaId == "local_exit_first" }
        } finally {
            main { MeloraSettings.updateMusicPlayMode(mode); PlaybackController.applyMusicPlayMode() }
        }
    }

    @Test fun exitDuringConnectionCannotRestoreOrAutoPlayFromALateCallback() {
        exitFromNotification()
        val savedQueue = prefs.getString("queue", null)
        assertNotNull(savedQueue)
        main {
            MeloraSettings.autoPlayOnStart.value = true
            PlaybackController.init(context)
            PlaybackController.exitPlayback()
        }
        Thread.sleep(600)
        instrumentation.waitForIdleSync()
        main {
            assertNull(controllerOrNull())
            assertNull(PlaybackController.state.value.current)
            assertFalse(PlaybackController.state.value.playing)
            assertEquals(savedQueue, prefs.getString("queue", null))
            assertEquals(12_000L, prefs.getLong("positionMs", -1))
        }
    }

    @Test fun notificationExitCancelsSleepTimerBeforeAnotherSessionStarts() {
        main {
            PlaybackController.setSleepTimer(5, extendToFinishTrack = false)
            assertNotNull(PlaybackController.sleepRemaining.value)
        }
        exitFromNotification()
        main { assertNull("退出不能把旧定时任务留给下一次播放", PlaybackController.sleepRemaining.value) }
    }

    @Test fun sleepTimerWaitsForTheActualEndAndRemainsCancellable() {
        main { controller().seekTo(58_000); controller().play() }
        await { controller().isPlaying && controller().currentPosition >= 58_000L }
        main { PlaybackController.setSleepTimer(0, extendToFinishTrack = true) }
        Thread.sleep(700)
        main {
            assertTrue("等待播完不能提前截掉曲尾", controller().playWhenReady)
            assertEquals("等待曲尾期间开关应保持开启并可取消", 0, PlaybackController.sleepRemaining.value)
        }
        await { !controller().playWhenReady }
        main {
            assertTrue("应完整播放到60秒末尾", controller().currentPosition >= 59_900L)
            assertNull(PlaybackController.sleepRemaining.value)
        }
    }

    @Test fun sleepTimerIgnoresNotificationMetadataAndStopsBeforeRepeating() {
        main { controller().repeatMode = Player.REPEAT_MODE_ONE; controller().play() }
        await { controller().isPlaying }
        main {
            PlaybackController.setSleepTimer(0, extendToFinishTrack = true)
            // 使用服务绑定的真实 ExoPlayer，不能用不产生 Media3 回调的代理证明此契约。
            val timer = PlaybackController.sleepTimer
            val player = timer.javaClass.getDeclaredField("player").apply { isAccessible = true }
                .get(timer) as Player
            syncNotificationMetadata(player, null, lyricsEnabled = false, coverEnabled = false)
            syncNotificationMetadata(player, null, lyricsEnabled = true, coverEnabled = false)
            assertEquals(0, PlaybackController.sleepRemaining.value)
            assertTrue(player.playWhenReady)
            player.seekTo(58_000)
        }
        await { !controller().playWhenReady }
        main {
            assertEquals("单曲循环不能绕过定时停止", 1, controller().currentMediaItemIndex)
            assertTrue("应播完而非提前暂停或重播", controller().currentPosition >= 59_900L)
            assertNull(PlaybackController.sleepRemaining.value)
        }
    }

    private fun exitFromNotification() {
        val result = main { controller().sendCustomCommand(SessionCommand("com.leyu.melora.action.EXIT", Bundle.EMPTY), Bundle.EMPTY) }
        assertEquals("退出命令必须完成，不能靠Media3的30秒解绑超时", SessionResult.RESULT_SUCCESS,
            result.get(3, TimeUnit.SECONDS).resultCode)
        await {
            !PlaybackService.isRunning && controllerOrNull() == null &&
                context.getSystemService(NotificationManager::class.java).activeNotifications.none {
                    it.notification.category == Notification.CATEGORY_TRANSPORT
                }
        }
    }

    private fun controllerOrNull(): MediaController? = PlaybackController.javaClass.getDeclaredField("controller")
        .apply { isAccessible = true }.get(PlaybackController) as? MediaController
    private fun controller() = requireNotNull(controllerOrNull())
    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!main(predicate)) {
            assertTrue("Playback service transition timed out", SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(20)
        }
    }
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
}
