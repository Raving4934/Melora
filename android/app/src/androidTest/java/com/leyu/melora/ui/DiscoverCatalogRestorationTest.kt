package com.leyu.melora.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class DiscoverCatalogRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun discoverCatalogEnumRestoresAfterRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var catalog by rememberSaveable { mutableStateOf<DiscoverCatalog?>(null) }
            Column {
                Text(catalog?.name ?: "发现首页")
                Button(onClick = { catalog = DiscoverCatalog.Audiobooks }) { Text("打开听书目录") }
            }
        }

        compose.onNodeWithText("打开听书目录").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Audiobooks").assertExists()
    }
}
