package com.leyu.melora.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioCacheSizeSettingInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactRowOpensAndSavesDecimalOrUnlimited() {
        val saves = mutableListOf<Int>()
        compose.setContent {
            var size by remember { mutableIntStateOf(1024) }
            MeloraTheme { AudioCacheSizeSetting(size, 1536L * 1024 * 1024) { saves += it; size = it } }
        }
        val row = compose.onNodeWithText("播放缓存")
        compose.onNodeWithText("1 G").assertExists()
        compose.onNodeWithText("已用 1.5 GB · 超出上限").assertExists()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()

        row.performClick()
        compose.onNodeWithText("缓存上限").assertExists()
        compose.onNodeWithText("G", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("0 G 表示不限制缓存大小。").assertExists()
        val input = compose.onNodeWithContentDescription("最大缓存大小")
        input.performTextReplacement("1.25")
        compose.runOnIdle { assertEquals(emptyList<Int>(), saves) }
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.onNodeWithText("缓存上限").assertDoesNotExist()
        compose.onNodeWithText("1.25 G").assertExists()

        row.performClick()
        input.performClick()
        input.performTextReplacement("0")
        input.performImeAction()
        compose.runOnIdle { assertEquals(listOf(1280, 0), saves) }
        compose.onNodeWithText("不限").assertExists()
        compose.onNodeWithText("已用 1.5 GB").assertExists()
    }

    @Test fun invalidInputAndCancelledEditKeepTheStoredLimit() {
        val saves = mutableListOf<Int>()
        compose.setContent { MeloraTheme { AudioCacheSizeSetting(256, 0L) { saves += it } } }
        val row = compose.onNodeWithText("播放缓存")
        row.performClick()
        val input = compose.onNodeWithContentDescription("最大缓存大小")
        val save = compose.onNodeWithText("保存")

        for (text in listOf("0.0001", "2097152", "-1")) {
            input.performTextReplacement(text)
            compose.onNodeWithText("请输入可用的非负容量。").assertExists()
            save.assertIsNotEnabled()
        }
        input.performTextReplacement("3")
        save.assertIsEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("0.25 G").assertExists()
        row.performClick()
        compose.onNodeWithText("0.25").assertExists()
        compose.runOnIdle { assertEquals(emptyList<Int>(), saves) }
    }
}
