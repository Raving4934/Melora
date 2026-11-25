package com.leyu.melora.ui.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.leyu.melora.playback.sdk.Tag
import com.leyu.melora.playback.sdk.TagInfo
import com.leyu.melora.ui.boundsInSameFrame
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaylistCategoryListTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delayedCategoriesKeepTheFirstActionAvailableAndStationary() {
        val category = Tag("chinese", "华语")
        val result = TagInfo(listOf(category), emptyList())
        val tagInfo = mutableStateOf<TagInfo?>(null)
        val opening = mutableIntStateOf(0)
        var clicked: Tag? = null
        val clock = compose.mainClock
        clock.autoAdvance = false
        try {
            compose.setContent {
                MeloraTheme {
                    Box(Modifier.testTag("playlist-category-list")) {
                        key(opening.intValue) {
                            PlaylistCategoryList(tagInfo.value, selectedTagId = "", onSelectTag = { clicked = it })
                        }
                    }
                }
            }
            clock.advanceTimeByFrame()
            val list = compose.onNodeWithTag("playlist-category-list")
            val title = compose.onNodeWithText("热门推荐")
            val all = compose.onNodeWithText("全部歌单")
            val loadedTag = compose.onNodeWithText(category.name)
            val hint = compose.onNodeWithContentDescription("正在加载分类")

            title.assertIsDisplayed()
            all.assertIsSelected().performClick()
            compose.runOnIdle { assertEquals(Tag("", "全部歌单"), clicked) }
            val initial = compose.boundsInSameFrame(list, title, all)
            fun assertStableLayout() = assertEquals(initial, compose.boundsInSameFrame(list, title, all))
            fun reopen(initialTags: TagInfo?) {
                compose.runOnIdle {
                    opening.intValue++
                    tagInfo.value = initialTags
                }
                clock.advanceTimeByFrame()
            }

            clock.advanceTimeBy(160)
            hint.assertDoesNotExist()
            compose.runOnIdle { tagInfo.value = result }
            clock.advanceTimeBy(480)
            loadedTag.assertIsDisplayed()
            hint.assertDoesNotExist()
            assertStableLayout()

            reopen(null)
            clock.advanceTimeBy(320)
            hint.assertDoesNotExist()
            clock.advanceTimeBy(160)
            hint.assertIsDisplayed()
            assertStableLayout()
            compose.runOnIdle { tagInfo.value = result }
            clock.advanceTimeByFrame()
            assertStableLayout()
            clock.advanceTimeBy(320)
            hint.assertDoesNotExist()
            loadedTag.performClick()
            compose.runOnIdle { assertEquals(category, clicked) }

            reopen(result)
            loadedTag.assertIsDisplayed()
            hint.assertDoesNotExist()
            assertStableLayout()
            reopen(TagInfo(emptyList(), emptyList()))
            clock.advanceTimeBy(480)
            hint.assertDoesNotExist()
            loadedTag.assertDoesNotExist()
            assertStableLayout()
        } finally {
            clock.autoAdvance = true
        }
    }
}
