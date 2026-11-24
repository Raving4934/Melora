package com.leyu.melora.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.leyu.melora.playback.sdk.OnlineCache
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.playback.sdk.OnlineRepository
import com.leyu.melora.playback.sdk.OnlineSong
import com.leyu.melora.playback.sdk.SongPage
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.theme.MeloraTheme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class PlaylistEntryPageInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val playlist = OnlinePlaylist(JSONObject()
        .put("source", "kw").put("id", "playlist-entry-${System.nanoTime()}")
        .put("name", "首屏测试歌单").put("img", "file:///catalog-test.jpg"))
    private val entryKey = OnlineRepository.playlistEntryPageKey(playlist)
    private val detailKey = "playlistDetail.${playlist.source}.${playlist.id}"

    @After fun cleanup() {
        OnlineCache.clear(entryKey)
        OnlineCache.clear(detailKey)
    }

    @Test fun detailsCanUseThePageAlreadyFetchedForCardPlayback() {
        val firstPlayable = page(2, song("second", "第二页首个可播歌曲"))
        OnlineCache.put(entryKey, firstPlayable)
        show()
        compose.awaitStable(compose.onNodeWithText("第二页首个可播歌曲"))
        assertSame(firstPlayable, OnlineCache.peek<SongPage>(detailKey))
    }

    @Test fun openingDetailsKeepsPreviouslyLoadedRowsAndDoesNotExpandTheCardQueue() {
        val first = song("first", "首段测试歌曲")
        val firstPlayable = page(1, first)
        val loaded = page(2, first, song("second", "之前浏览加载的歌曲"))
        OnlineCache.put(entryKey, firstPlayable)
        OnlineCache.put(detailKey, loaded)
        show()
        val laterSong = compose.onNodeWithText("之前浏览加载的歌曲")
        compose.awaitStable(laterSong)
        laterSong.assertIsDisplayed()
        assertSame(firstPlayable, OnlineCache.peek<SongPage>(entryKey))
        assertSame(loaded, OnlineCache.peek<SongPage>(detailKey))
    }

    private fun show() = compose.setContent {
        MeloraTheme { SongListStateProvider { PlaylistDetailContent(playlist, onBack = {}) } }
    }

    private fun page(number: Int, vararg songs: OnlineSong) =
        SongPage(songs.toList(), total = 2, page = number, allPage = 2)

    private fun song(id: String, name: String) = OnlineSong(JSONObject()
        .put("source", playlist.source).put("songmid", id).put("name", name)
        .put("img", "file:///catalog-song.jpg"))
}
