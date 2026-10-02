package com.leyu.melora.ui.local

import com.leyu.melora.ui.awaitStable

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
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
import org.junit.Assert.assertSame
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
    @Volatile private var published: LocalSongsContent? = null
    private var refreshes = 0
    private val moreSongs = mutableListOf<String>()

    private fun showPage() {
        compose.setContent {
            list = rememberLazyListState()
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                val content = rememberLocalSongsContent(songs.value, field.value, ascending = true)
                SideEffect { published = content }
                LocalSongsListContent(
                    content = content,
                    selection = selection,
                    listState = list, onOpenDrawer = {}, onOpenSearch = {}, onOpenSortSheet = {},
                    onMore = { moreSongs += it.id }, onDeleteSelection = {}, onAddToPlaylist = {},
                    pullEnabled = true, refreshing = false, onRefresh = { refreshes++ },
                )
                }
            }
        }
        compose.waitUntil(5_000) { published != null }
    }

    @Test
    fun indexJumpsToActualListPositionAndKeepsBatchSelection() {
        selection.start()
        selection.toggle("local:kept")
        showPage()
        val sorted = sortLocalSongs(songs.value, field.value, true)
        val starts = localSongSectionStarts(sorted, field.value)
        compose.awaitStable("local-song-index")
        for (label in listOf("M", "A", "T", "0", "#")) {
            val expected = starts.getValue(label)
            compose.onNodeWithContentDescription("跳转到 $label").performClick()
            // requestScrollToItem 在下一次测量生效，不把主线程空闲等同于测量已完成。
            compose.waitUntil(5_000) { list.layoutInfo.visibleItemsInfo.any { it.index == expected } }
            compose.runOnIdle {
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
        compose.awaitStable("local-song-index")
        val more = compose.onAllNodesWithContentDescription("更多")[3]
        compose.awaitStable(more)
        val originalCenter = more.fetchSemanticsNode().boundsInRoot.center.x
        val rail = compose.onNodeWithTag("local-song-index").fetchSemanticsNode().boundsInRoot
        assertTrue("更多图标中心不能落入字母触摸区: center=$originalCenter rail=$rail", originalCenter < rail.left)
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

        val sizeContent = LocalSongsContent(
            sortLocalSongs(songs.value, LocalSortField.Size, true),
            emptyMap(),
        )
        compose.runOnIdle { field.value = LocalSortField.Size }
        compose.waitUntil(5_000) { published == sizeContent }
        compose.onNodeWithTag("local-song-index").assertDoesNotExist()

        val artistSongs = sortLocalSongs(songs.value, LocalSortField.Artist, true)
        val artistContent = LocalSongsContent(
            artistSongs,
            localSongSectionStarts(artistSongs, LocalSortField.Artist),
        )
        compose.runOnIdle { field.value = LocalSortField.Artist }
        // Size 的空索引快照先确认提交，因此旧的文件名快照不能提前满足此条件。
        compose.waitUntil(5_000) { published == artistContent }
        compose.onNodeWithTag("local-song-index").assertIsDisplayed()

        compose.runOnIdle { songs.value = emptyList() }
        compose.waitUntil(5_000) { published == LocalSongsContent() }
        compose.onNodeWithTag("local-song-index").assertDoesNotExist()
        compose.onNodeWithText("还没有本地歌曲").assertIsDisplayed()
    }
    @Test
    fun firstCompositionAfterEveryNavigationKeepsTheSameCompleteSnapshot() {
        val tab = mutableStateOf(5)
        val entries = mutableListOf<LocalSongsContent?>()
        compose.setContent {
            val content = rememberLocalSongsContent(songs.value, field.value, true)
            SideEffect { published = content }
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    key(tab.value) {
                        if (tab.value == 5) {
                            SideEffect { entries += content }
                            LocalSongsPage(content = content, onOpenDrawer = {})
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) { published != null }
        val ready = published!!
        assertEquals(sortLocalSongs(songs.value, field.value, true), ready.songs)
        assertEquals(localSongSectionStarts(ready.songs, field.value), ready.sections)
        for (otherTab in listOf(6, 2, 1)) {
            compose.runOnIdle { tab.value = otherTab }
            compose.runOnIdle { entries.clear(); tab.value = 5 }
            compose.runOnIdle {
                assertTrue(entries.isNotEmpty())
                entries.forEach { assertSame("重入第一帧不能重新发布空列表", ready, it) }
            }
            compose.onNodeWithText(ready.songs.first().title).assertIsDisplayed()
        }
    }

    @Test
    fun sortUpdatesKeepCompleteRowsAndLatestResultWinsEvenWhilePageIsAbsent() {
        val ascending = mutableStateOf(true)
        val frames = mutableListOf<LocalSongsContent?>()
        compose.setContent {
            val content = rememberLocalSongsContent(songs.value, field.value, ascending.value)
            SideEffect { published = content; frames += content }
        }
        compose.waitUntil(5_000) { published != null }
        compose.runOnIdle { frames.clear(); field.value = LocalSortField.Artist }
        compose.runOnIdle { ascending.value = false; field.value = LocalSortField.Size }
        val expected = sortLocalSongs(songs.value, LocalSortField.Size, false)
        compose.waitUntil(5_000) { published?.songs == expected && published?.sections == emptyMap<String, Int>() }
        compose.runOnIdle {
            assertTrue(frames.isNotEmpty())
            assertTrue("更新不能丢行或先画空列表", frames.all { it?.songs?.size == songs.value.size })
            frames.filterNotNull().forEach { frame ->
                assertTrue(frame.sections.isEmpty() || frame.sections == localSongSectionStarts(frame.songs, LocalSortField.Artist) ||
                    frame.sections == localSongSectionStarts(frame.songs, LocalSortField.FileName))
            }
        }
    }

    @Test
    fun clearingAndRepopulatingNeverResurrectsTheOldLibrary() {
        val frames = mutableListOf<LocalSongsContent?>()
        compose.setContent {
            val content = rememberLocalSongsContent(songs.value, field.value, true)
            SideEffect { published = content; frames += content }
        }
        compose.waitUntil(5_000) { published != null }
        val replacement = songs.value.takeLast(2)
        compose.runOnIdle { frames.clear(); songs.value = emptyList() }
        compose.runOnIdle {
            assertTrue(frames.isNotEmpty())
            assertTrue(frames.all { it?.songs?.isEmpty() == true && it.sections.isEmpty() })
            frames.clear()
            songs.value = replacement
        }
        compose.waitUntil(5_000) { published?.songs?.size == replacement.size }
        compose.runOnIdle {
            assertTrue(frames.all { it == null || it.songs == replacement })
            assertEquals(localSongSectionStarts(replacement, field.value), published!!.sections)
        }
    }

    @Test
    fun preparingSortDoesNotPretendLibraryIsEmptyAndBatchUsesDisplayedSnapshot() {
        val content = mutableStateOf<LocalSongsContent?>(null)
        var deleted = emptyList<LocalSong>()
        val known = songs.value.take(2)
        compose.setContent {
            list = rememberLazyListState()
            MaterialTheme {
                CompositionLocalProvider(LocalSongListState provides shared) {
                    LocalSongsListContent(
                        content = content.value, selection = selection,
                        listState = list, onOpenDrawer = {}, onOpenSearch = {}, onOpenSortSheet = {},
                        onMore = {}, onDeleteSelection = { deleted = it }, onAddToPlaylist = {},
                        pullEnabled = true, refreshing = false, onRefresh = {},
                    )
                }
            }
        }
        compose.onNodeWithText("还没有本地歌曲").assertDoesNotExist()
        compose.onNodeWithTag("local-song-index").assertDoesNotExist()
        compose.runOnIdle { content.value = LocalSongsContent(known, localSongSectionStarts(known, field.value)) }
        compose.onNodeWithText(known.first().title).assertIsDisplayed()
        compose.runOnIdle { selection.start() }
        compose.awaitStable(compose.onNodeWithText("全选"))
        compose.onNodeWithText("全选").performClick()
        compose.runOnIdle { assertEquals(known.map { it.localUid() }.toSet(), selection.selectedUids) }
        compose.awaitStable(compose.onNodeWithText("永久删除"))
        compose.onNodeWithText("永久删除").performClick()
        compose.runOnIdle { assertEquals(known, deleted) }
    }
}
