package com.leyu.melora.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class AudioCacheSizeSettingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun semanticAdjustmentSavesHalfGigabyteStopsAndHapticsOnlyOnChange() {
        val saves = mutableListOf<Int>()
        val haptics = RecordingHaptics()
        compose.setContent {
            var size by remember { mutableIntStateOf(1024) }
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                MeloraTheme { AudioCacheSizeSetting(size) { saves += it; size = it } }
            }
        }
        val slider = compose.onNodeWithContentDescription("最大缓存大小")
        for (step in listOf(1f, 1f, 18f, 0f)) {
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(step) }
        }
        compose.runOnIdle {
            assertEquals(listOf(1536, 10240, 1024), saves)
            assertEquals(3, haptics.count)
        }
    }

    @Test fun dragPreviewsCapacityButSavesOnlyOnRelease() {
        val saves = mutableListOf<Int>()
        val haptics = RecordingHaptics()
        compose.setContent {
            var size by remember { mutableIntStateOf(1024) }
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                MeloraTheme { AudioCacheSizeSetting(size) { saves += it; size = it } }
            }
        }
        val slider = compose.onNodeWithContentDescription("最大缓存大小")
        slider.performTouchInput {
            down(Offset(8f, centerY))
            moveTo(Offset(width * 0.25f, centerY), delayMillis = 100)
            moveTo(center, delayMillis = 100)
        }
        // 触摸 slop 随设备密度变化，验证实际离散档而非假定屏幕中点必落某档。
        val step = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current.roundToInt()
        assertTrue(step in 1..17)
        val expected = audioCacheMbAtStep(step)
        compose.onNodeWithText("${audioCacheSizeGb(expected)} G").assertExists()
        compose.runOnIdle { assertTrue(saves.isEmpty()); assertTrue(haptics.count > 0) }
        val count = haptics.count
        slider.performTouchInput { moveTo(center + Offset(1f, 0f)) }
        compose.runOnIdle { assertEquals(count, haptics.count) }
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(expected), saves) }
    }

    @Test fun openingSettingsDoesNotRewriteAnExistingOutOfRangeLimit() {
        var saves = 0
        compose.setContent { MeloraTheme { AudioCacheSizeSetting(256) { saves++ } } }
        compose.onNodeWithText("0.25 G").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    private class RecordingHaptics : HapticFeedback {
        var count = 0
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            assertEquals(HapticFeedbackType.TextHandleMove, hapticFeedbackType)
            count++
        }
    }
}
