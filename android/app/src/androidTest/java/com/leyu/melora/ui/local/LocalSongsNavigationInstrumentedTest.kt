package com.leyu.melora.ui.local

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.local.LocalSong
import com.leyu.melora.playback.local.LocalSongIndexLabels
import com.leyu.melora.playback.local.LocalSortField
import com.leyu.melora.playback.local.localSongSectionStarts
import com.leyu.melora.playback.local.sortLocalSongs
import com.leyu.melora.ui.common.SongSelectionState
import com.leyu.melora.ui.common.SongListState
import com.leyu.melora.ui.common.LocalSongListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSongsNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private val selection = SongSelectionState()
    private val field = mutableStateOf(LocalSortField.FileName)
    private val songs = mutableStateOf(LocalSongIndexLabels.flatMap { label ->
        (1..5).map { number ->
            LocalSong(
                id = "$label-$number", uri = "file:///nonexistent/$label-$number.mp3",
                title = "$label song $number", artist = "测试歌手", album = "测试专辑",
                durationMs = 120_000, sizeBytes = number.toLong(), mimeType = "audio/mpeg",
                sampleRate = 44_100, bitrate = 320_000, modifiedAt = 1, addedAt = 1, folder = "/nonexistent",
            )
        }
    })
    private val shared = SongListState(songs, mutableStateOf(false), mutableStateOf(null))
    private var refreshes = 0
    private val moreSongs = mutableListOf<String>()

    private fun showPage() {
        compose.setContent {
            list = rememberLazyListState()
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                LocalSongsListContent(
                    songs = sortLocalSongs(songs.value, field.value, ascending = true),
                    sortField = field.value, ascending = true, selection = selection,
                    listState = list, onOpenDrawer = {}, onOpenSearch = {}, onOpenSortSheet = {},
                    onMore = { moreSongs += it.id }, onDeleteSelection = {}, onAddToPlaylist = {},
                    pullEnabled = true, refreshing = false, onRefresh = { refreshes++ },
                )
                }
            }
        }
    }

    @Test
    fun indexJumpsToActualListPositionAndKeepsBatchSelection() {
        selection.start()
        selection.toggle("local:kept")
        showPage()
        val sorted = sortLocalSongs(songs.value, field.value, true)
        val starts = localSongSectionStarts(sorted, field.value)
        for (label in listOf("M", "A", "T", "0", "#")) {
            compose.onNodeWithContentDescription("跳转到 $label").performClick()
            compose.runOnIdle {
                val expected = starts.getValue(label)
                assertTrue("$label 首项应可见", list.layoutInfo.visibleItemsInfo.any { it.index == expected })
                assertEquals(setOf("local:kept"), selection.selectedUids)
                assertTrue(selection.active)
            }
        }
    }

    @Test
    fun indexGestureDoesNotTriggerPullRefreshAndFinishesAtLastSection() {
        showPage()
        compose.onNodeWithTag("local-song-index").performTouchInput {
            swipe(topCenter, bottomCenter, durationMillis = 650)
        }
        compose.runOnIdle {
            assertEquals(0, refreshes)
            val start = localSongSectionStarts(sortLocalSongs(songs.value, field.value, true), field.value).getValue("Z")
            assertTrue(list.layoutInfo.visibleItemsInfo.any { it.index == start })
        }
        compose.onNodeWithTag("local-song-index-preview").assertDoesNotExist()
    }

    @Test
    fun moreButtonKeepsItsPositionAndReceivesTouchesBesideTheRail() {
        showPage()
        val more = compose.onAllNodesWithContentDescription("更多")[3]
        val originalCenter = more.fetchSemanticsNode().boundsInRoot.center.x
        val rail = compose.onNodeWithTag("local-song-index").fetchSemanticsNode().boundsInRoot
        assertTrue("更多图标中心不能落入字母触摸区", originalCenter < rail.left)
        more.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, moreSongs.size) }
        // 非字母排序时索引消失，但操作列不能左右跳动。
        compose.runOnIdle { field.value = LocalSortField.Size }
        val withoutRail = compose.onAllNodesWithContentDescription("更多")[3]
        assertEquals(originalCenter, withoutRail.fetchSemanticsNode().boundsInRoot.center.x, 0.5f)
        withoutRail.performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(2, moreSongs.size) }
    }

    @Test
    fun nonAlphabeticalSortAndEmptyLibraryHaveNoMisleadingRail() {
        showPage()
        compose.onNodeWithTag("local-song-index").assertIsDisplayed()
        compose.runOnIdle { field.value = LocalSortField.Size }
        compose.onNodeWithTag("local-song-index").assertDoesNotExist()
        compose.runOnIdle { field.value = LocalSortField.Artist }
        compose.onNodeWithTag("local-song-index").assertIsDisplayed()
        compose.runOnIdle { songs.value = emptyList() }
        compose.onNodeWithTag("local-song-index").assertDoesNotExist()
        compose.onNodeWithText("还没有本地歌曲").assertIsDisplayed()
    }
}
