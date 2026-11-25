package com.leyu.melora.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.ui.awaitStable
import com.leyu.melora.ui.player.MiniPlayerContentHeight
import com.leyu.melora.ui.theme.MeloraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DockedPlayerInsetsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun listAndBatchActionsStayAboveDockedPlayer() {
        val batchActive = mutableStateOf(false)
        val rowEndPadding = 12.dp
        var lastRowTaps = 0
        compose.setContent {
            MeloraTheme {
                ChromeScaffold(
                    expectedTopBarHeight = 0.dp,
                    bottomBar = {
                        Spacer(
                            Modifier.navigationBarsPadding()
                                .fillMaxWidth()
                                .height(MiniPlayerContentHeight)
                                .testTag("docked-player"),
                        )
                    },
                ) {
                    ChromeScaffold(
                        expectedTopBarHeight = 0.dp,
                        bottomBar = {
                            if (batchActive.value) {
                                Box(Modifier.fillMaxWidth().height(48.dp).testTag("batch-actions"))
                            }
                        },
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("song-list"),
                            contentPadding = chromeContentPadding(PaddingValues(bottom = rowEndPadding)),
                        ) {
                            items(60) { index ->
                                Box(
                                    Modifier.fillMaxWidth().height(44.dp).testTag("song-$index")
                                        .clickable { if (index == 59) lastRowTaps++ },
                                ) { Text("第 ${index + 1} 首") }
                            }
                        }
                    }
                }
            }
        }
        val list = compose.onNodeWithTag("song-list")
        val player = compose.onNodeWithTag("docked-player")
        val batch = compose.onNodeWithTag("batch-actions")
        val last = compose.onNodeWithTag("song-59")
        val playerTop = player.getUnclippedBoundsInRoot().top.value
        assertEquals(playerTop, list.getUnclippedBoundsInRoot().bottom.value, 1f)

        list.performScrollToIndex(59)
        last.assertIsDisplayed()
        assertEquals(rowEndPadding.value, playerTop - last.getUnclippedBoundsInRoot().bottom.value, 1f)
        last.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, lastRowTaps)
            batchActive.value = true
        }

        compose.awaitStable(batch)
        assertEquals(playerTop, batch.getUnclippedBoundsInRoot().bottom.value, 1f)
        list.performScrollToIndex(59)
        last.assertIsDisplayed()
        val batchTop = batch.getUnclippedBoundsInRoot().top.value
        assertEquals(batchTop, list.getUnclippedBoundsInRoot().bottom.value, 1f)
        assertEquals(rowEndPadding.value, batchTop - last.getUnclippedBoundsInRoot().bottom.value, 1f)
        last.performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, lastRowTaps) }
    }
}
