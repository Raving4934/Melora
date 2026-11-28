package com.leyu.melora.playback

import android.content.Context
import android.content.ContextWrapper
import android.util.TypedValue
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopLyricServiceInstrumentedTest {
    @Test
    fun initialMountAndRetryUseTheConfiguredLyricWindow() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = DesktopLyricService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, context)
        val systemWindows = requireNotNull(context.getSystemService(WindowManager::class.java))
        val mounts = mutableListOf<Pair<DesktopLyricViewport, WindowManager.LayoutParams>>()
        val windows = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
            if (method.name == "addView") {
                val (view, params) = requireNotNull(args)
                mounts += (view as DesktopLyricViewport) to (params as WindowManager.LayoutParams)
                if (mounts.size == 1) throw WindowManager.BadTokenException("模拟首次挂载失败")
                null
            } else {
                method.invoke(systemWindows, *(args ?: emptyArray()))
            }
        }
        val serviceClass = DesktopLyricService::class.java
        serviceClass.getDeclaredField("windowManager").apply { isAccessible = true }.set(service, windows)
        val lyricView = serviceClass.getDeclaredField("lyricView").apply { isAccessible = true }
        val addOverlay = serviceClass.getDeclaredMethod("addOverlay").apply { isAccessible = true }
        val state = playbackFlow<PlayerUiState>("_state")
        val lyric = playbackFlow<PlayerLyric?>("_lyric")
        val oldState = state.value
        val oldLyric = lyric.value
        val oldFontSize = MeloraSettings.lyricFontSize.value
        val oldAlpha = MeloraSettings.lyricAlpha.value
        val oldBackground = MeloraSettings.lyricBackground.value
        val oldLocked = MeloraSettings.lockLyrics.value
        val oldPosition = MeloraSettings.desktopLyricPosition.value
        try {
            val track = UiTrack("desktop-window-fixture", "测试歌曲", "测试歌手", "")
            val line = "挂载时已经显示的歌词"
            state.value = PlayerUiState(current = track, playing = true, positionMs = 1_500L)
            lyric.value = PlayerLyric(track.uid, track.title, track.artist, listOf(LyricLine(1_000L, line)), "test")
            MeloraSettings.lyricFontSize.value = 28f
            MeloraSettings.lyricAlpha.value = 65f
            MeloraSettings.lyricBackground.value = true
            MeloraSettings.lockLyrics.value = true
            MeloraSettings.desktopLyricPosition.value = DesktopLyricPosition(0.5f, 0.4f)

            assertEquals(false, addOverlay.invoke(service))
            assertNull(lyricView.get(service))
            assertEquals(true, addOverlay.invoke(service))

            assertEquals(2, mounts.size)
            for ((view, params) in mounts) {
                assertTrue(params.width > 1 && params.height > 1)
                assertEquals(view.measuredWidth, params.width)
                assertEquals(view.measuredHeight, params.height)
                assertTrue(params.y > 0)
                assertEquals(line, view.contentDescription?.toString())
                assertEquals(0.65f, view.alpha, 0.001f)
                assertNotNull(view.background)
                assertFalse(view.isClickable)
                val row = view.getChildAt(0) as DesktopLyricView
                val expectedSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28f, view.resources.displayMetrics)
                assertEquals(expectedSize, row.textSize, 0.01f)
            }
        } finally {
            service.onDestroy()
            state.value = oldState
            lyric.value = oldLyric
            MeloraSettings.lyricFontSize.value = oldFontSize
            MeloraSettings.lyricAlpha.value = oldAlpha
            MeloraSettings.lyricBackground.value = oldBackground
            MeloraSettings.lockLyrics.value = oldLocked
            MeloraSettings.desktopLyricPosition.value = oldPosition
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> playbackFlow(name: String): MutableStateFlow<T> =
        PlaybackController::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(PlaybackController) as MutableStateFlow<T>

    private fun onMain(block: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        checkNotNull(result).getOrThrow()
    }
}
