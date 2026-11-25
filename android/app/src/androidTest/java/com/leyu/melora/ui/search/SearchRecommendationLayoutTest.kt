package com.leyu.melora.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.leyu.melora.playback.sdk.OnlinePlaylist
import com.leyu.melora.ui.theme.MeloraTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SearchRecommendationLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collectionRecommendationsAppendOnlyTheRowsActuallyAvailable() {
        val six = (1..6).map { OnlinePlaylist(JSONObject().put("id", it.toString()).put("name", "推荐 $it")) }
        val listState = LazyListState()
        val results = mutableStateOf(emptyList<OnlinePlaylist>())
        val category = mutableStateOf(SearchCategory.Playlist)
        compose.setContent {
            MeloraTheme {
                Box(Modifier.size(340.dp, 310.dp)) {
                    SearchIdleContent(
                        listState = listState, category = category.value,
                        selectedPlatform = PlatformSource.All, searchHistory = listOf("周杰伦"),
                        hotWords = emptyList(), hotLoading = false, hotPlaylists = emptyList(), hotPlaylistsLoading = false,
                        recommendSongs = emptyList(), recommendLoading = false, recommendedCollections = results.value,
                        favoriteUids = emptySet(), onSubmit = {}, onOpenPlaylist = {}, onMoreSong = {}, onPlaySong = {},
                    )
                }
            }
        }
        val history = compose.onNodeWithText("周杰伦")
        val top = history.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        for (kind in listOf(SearchCategory.Playlist, SearchCategory.Audiobook)) {
            compose.runOnIdle {
                category.value = kind
                results.value = emptyList()
            }
            compose.onNodeWithText("猜你喜欢").assertDoesNotExist()
            compose.runOnIdle { assertFalse(listState.canScrollForward) }
            compose.runOnIdle { results.value = six.take(2) }
            compose.onNodeWithText("推荐 2").assertIsDisplayed()
            compose.onNodeWithText("推荐 3").assertDoesNotExist()
            compose.runOnIdle { results.value = six }
            compose.onNodeWithText("推荐 6").assertExists()
            assertEquals(top, history.fetchSemanticsNode().boundsInRoot.top, 0f)
        }
    }
}
