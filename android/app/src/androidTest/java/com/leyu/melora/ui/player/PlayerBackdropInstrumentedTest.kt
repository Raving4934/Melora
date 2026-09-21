package com.leyu.melora.ui.player

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerBackdropInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun disableAutomaticClockAdvance() {
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun api33RuntimeBackdropFreezesWhilePausedAndContinuesAfterResume() {
        assumeRuntimeShaderDevice()
        val artwork = createGradientPng()
        try {
            val entry = mutableStateOf<PlayerBackdropCacheEntry?>(null)
            val playing = mutableStateOf(true)
            val visible = mutableStateOf(true)
            val loaded = AtomicReference<PlayerBackdropCacheEntry?>(null)
            val lifecycle = AtomicReference(Lifecycle.State.INITIALIZED)

            setBackdropContent(artwork, entry, playing, visible, loaded, lifecycle)
            awaitBackdropLoaded(loaded, lifecycle)
            advanceClock(500L) // finish the existing 240ms crossfade before sampling.

            composeRule.runOnIdle { playing.value = false }
            advanceClock(400L) // process cancellation and settle on the last shader time.
            val pausedBefore = captureBackdrop()
            advanceClock(800L)
            val pausedAfter = captureBackdrop()
            assertTrue("paused RuntimeShader pixels changed", pausedBefore.sameAs(pausedAfter))

            composeRule.runOnIdle { playing.value = true }
            advanceClock(1_000L)
            val resumed = captureBackdrop()
            assertFalse("RuntimeShader did not continue after resume", pausedAfter.sameAs(resumed))
        } finally {
            artwork.delete()
        }
    }

    @Test
    fun api33RuntimeBackdropFreezesWhileNotVisibleAndContinuesWhenVisibleAgain() {
        assumeRuntimeShaderDevice()
        val artwork = createGradientPng()
        try {
            val entry = mutableStateOf<PlayerBackdropCacheEntry?>(null)
            val playing = mutableStateOf(true)
            val visible = mutableStateOf(true)
            val loaded = AtomicReference<PlayerBackdropCacheEntry?>(null)
            val lifecycle = AtomicReference(Lifecycle.State.INITIALIZED)

            setBackdropContent(artwork, entry, playing, visible, loaded, lifecycle)
            awaitBackdropLoaded(loaded, lifecycle)
            advanceClock(500L)

            composeRule.runOnIdle { visible.value = false }
            advanceClock(400L) // process the visibility gate and freeze the last shader time.
            val hiddenBefore = captureBackdrop()
            advanceClock(800L)
            val hiddenAfter = captureBackdrop()
            assertTrue("invisible RuntimeShader pixels changed", hiddenBefore.sameAs(hiddenAfter))

            composeRule.runOnIdle { visible.value = true }
            advanceClock(1_000L)
            val visibleAgain = captureBackdrop()
            assertFalse("RuntimeShader did not continue after becoming visible", hiddenAfter.sameAs(visibleAgain))
        } finally {
            artwork.delete()
        }
    }

    private fun setBackdropContent(
        artwork: File,
        entry: MutableState<PlayerBackdropCacheEntry?>,
        playing: MutableState<Boolean>,
        visible: MutableState<Boolean>,
        loaded: AtomicReference<PlayerBackdropCacheEntry?>,
        lifecycle: AtomicReference<Lifecycle.State>,
    ) {
        composeRule.setContent {
            val owner = LocalLifecycleOwner.current
            val lifecycleOwner = owner.lifecycle
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, _ ->
                    lifecycle.set(lifecycleOwner.currentState)
                }
                lifecycleOwner.addObserver(observer)
                lifecycle.set(lifecycleOwner.currentState)
                onDispose { lifecycleOwner.removeObserver(observer) }
            }
            PlayerBackdrop(
                artwork = Uri.fromFile(artwork).toString(),
                entry = entry.value,
                onEntryReady = { ready ->
                    entry.value = ready
                    loaded.set(ready)
                },
                modifier = Modifier.fillMaxSize().testTag(BACKDROP_TAG),
                isVisible = visible.value,
                playing = playing.value,
            )
        }
    }

    private fun awaitBackdropLoaded(
        loaded: AtomicReference<PlayerBackdropCacheEntry?>,
        lifecycle: AtomicReference<Lifecycle.State>,
    ) {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            loaded.get() != null && lifecycle.get() == Lifecycle.State.RESUMED
        }
    }

    private fun advanceClock(durationMillis: Long) {
        composeRule.mainClock.advanceTimeBy(durationMillis)
    }

    private fun captureBackdrop(): Bitmap = composeRule
        .onNodeWithTag(BACKDROP_TAG)
        .captureToImage()
        .asAndroidBitmap()

    private fun createGradientPng(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("player-backdrop-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 128) {
                for (x in 0 until 128) {
                    val red = 16 + x * 220 / 127
                    val green = 16 + y * 220 / 127
                    val blue = 32 + (x + y) * 96 / 254
                    bitmap.setPixel(x, y, android.graphics.Color.rgb(red, green, blue))
                }
            }
            file.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "failed to write temporary backdrop PNG"
                }
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun assumeRuntimeShaderDevice() {
        assumeTrue(
            "RuntimeShader backdrop requires API 33+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )
        assumeTrue("system animations must be enabled", ValueAnimator.areAnimatorsEnabled())
        val powerManager = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSystemService(PowerManager::class.java)
        assumeFalse("power saver must be disabled for motion assertions", powerManager?.isPowerSaveMode == true)
    }

    private companion object {
        const val BACKDROP_TAG = "player-backdrop-instrumented"
    }
}
