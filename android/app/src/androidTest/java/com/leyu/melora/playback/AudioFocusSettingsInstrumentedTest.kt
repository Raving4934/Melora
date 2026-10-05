package com.leyu.melora.playback

import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 验证真实Media3播放线程的焦点策略，不播放音频，不启动用户服务，不写用户设置。 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AudioFocusSettingsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun settingChangesReachThePlayerWithoutAConnectedController() = withPlayer { player, scope ->
        onMain {
            MeloraSettings.pauseOnOtherAudio.value = false
            player.followAudioFocusSettings(scope)
        }
        assertNull(focusAttributes(player))
        onMain { MeloraSettings.updatePauseOnOtherAudio(true) }
        assertEquals(C.USAGE_MEDIA, focusAttributes(player)?.usage)
        assertEquals(C.AUDIO_CONTENT_TYPE_MUSIC, focusAttributes(player)?.contentType)
        onMain { MeloraSettings.updatePauseOnOtherAudio(false) }
        assertNull(focusAttributes(player))
        onMain { MeloraSettings.updatePauseOnOtherAudio(false) }
        assertNull("重复更新保持禁用", focusAttributes(player))
    }

    @Test fun backupPublicationAndOwnerRecreationUseTheCurrentSetting() = withPlayer { player, scope ->
        onMain {
            MeloraSettings.pauseOnOtherAudio.value = true
            player.followAudioFocusSettings(scope)
        }
        assertEquals(C.USAGE_MEDIA, focusAttributes(player)?.usage)
        onMain { BackupSettings.apply(JSONObject().put("pauseOnOtherAudio", false)) }
        assertNull("备份发布必须直接抵达实际播放器", focusAttributes(player))
        onMain {
            scope.cancel()
            MeloraSettings.updatePauseOnOtherAudio(true)
        }
        assertNull("服务生命周期结束后不得继续修改旧播放器", focusAttributes(player))
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            onMain { player.followAudioFocusSettings(nextScope) }
            assertEquals("重建时不等待新的设置变化", C.USAGE_MEDIA, focusAttributes(player)?.usage)
        } finally { onMain { nextScope.cancel() } }
    }

    private fun withPlayer(test: (ExoPlayer, CoroutineScope) -> Unit) {
        val initialized = MeloraSettings.javaClass.getDeclaredField("initialized").apply { isAccessible = true }
        val previousInitialized = initialized.getBoolean(MeloraSettings)
        val previousValue = MeloraSettings.pauseOnOtherAudio.value
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var player: ExoPlayer? = null
        try {
            assertNull("用例不能依赖应用controller连接", PlaybackController.javaClass.getDeclaredField("controller")
                .apply { isAccessible = true }.get(PlaybackController))
            // 只测已验证持久化链路之后的运行时发布，不读取/覆写真机偏好文件。
            initialized.setBoolean(MeloraSettings, false)
            val actual = onMain { ExoPlayer.Builder(instrumentation.targetContext).build() }
            player = actual
            test(actual, scope)
        } finally {
            onMain {
                scope.cancel()
                player?.release()
                MeloraSettings.pauseOnOtherAudio.value = previousValue
                initialized.setBoolean(MeloraSettings, previousInitialized)
            }
        }
    }

    private fun focusAttributes(player: ExoPlayer): AudioAttributes? {
        instrumentation.waitForIdleSync()
        val completed = CountDownLatch(1)
        var result: Result<AudioAttributes?>? = null
        // 与真实setAudioAttributes消息共用播放线程；等消息处理后检查Media3实际接管开关，非mock。
        Handler(player.playbackLooper).post {
            result = runCatching {
                val internal = player.javaClass.getDeclaredField("internalPlayer").apply { isAccessible = true }.get(player)
                val manager = internal.javaClass.getDeclaredField("audioFocusManager").apply { isAccessible = true }.get(internal)
                manager.javaClass.getDeclaredField("audioAttributes").apply { isAccessible = true }.get(manager) as AudioAttributes?
            }
            completed.countDown()
        }
        assertTrue("等待Media3焦点设置超时", completed.await(5, TimeUnit.SECONDS))
        return checkNotNull(result).getOrThrow()
    }

    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
