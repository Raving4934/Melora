package com.leyu.melora.ui.common

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookChapterPickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enteringThousandSelectsOrdinalWithoutWalkingThroughEarlierRanges() {
        val open = mutableStateOf(true)
        val selections = mutableListOf<Int>()
        compose.setContent {
            MeloraTheme {
                if (open.value) BookChapterPicker(1568, 1, false, { open.value = false }, selections::add, {})
            }
        }
        val field = compose.onNode(hasSetTextAction())
        compose.awaitStable(field)
        if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = compose.onNodeWithTag("book-chapter-picker").captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "book-chapter-picker.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        field.performTextReplacement("1000")
        field.performImeAction()
        compose.waitUntil(5_000) { selections.isNotEmpty() }
        assertEquals(listOf(1000), selections)
        assertFalse(open.value)
    }

    @Test fun descendingRangeSelectsSourcePageRatherThanInventingAnEpisode() {
        val selected = mutableListOf<Int>()
        val open = mutableStateOf(true)
        compose.setContent {
            MeloraTheme {
                if (open.value) BookChapterPicker(1568, 1, true, { open.value = false }, {}, selected::add)
            }
        }
        val range = compose.onNodeWithContentDescription("目录 1501–1568")
        compose.awaitStable(range)
        range.performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertEquals(listOf(16), selected)
    }

    @Test fun invalidOrdinalCannotBeSubmittedAndUnknownTotalDoesNotInventRanges() {
        compose.setContent { MeloraTheme { BookChapterPicker(null, 1, false, {}, {}, {}) } }
        compose.onNodeWithContentDescription("目录 1–100").assertDoesNotExist()
        compose.onNodeWithText("定位").assertIsNotEnabled()
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("0")
        compose.onNodeWithText("定位").assertIsNotEnabled()
        field.performTextReplacement("1000")
        compose.onNodeWithText("定位").assertIsEnabled()
    }
}
