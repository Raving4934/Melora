package com.leyu.melora.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlayerCoverStyle
import com.leyu.melora.playback.ThemeMode
import com.leyu.melora.ui.theme.LocalForceHideStatusBar
import com.leyu.melora.ui.theme.SystemBarsVisibility
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class PlayerCoverPickerSystemBarsTest {
    @get:Rule val compose = createComposeRule()

    private val originalHideStatusBar = MeloraSettings.hideStatusBar.value

    @After
    fun restoreStatusBarSetting() {
        MeloraSettings.hideStatusBar.value = originalHideStatusBar
    }

    @Test
    fun statusBarPolicySurvivesPickerOpenSelectionAndClose() {
        val forceHide = mutableStateOf(false)
        val pickerVisible = mutableStateOf(false)
        val selected = mutableStateOf(PlayerCoverStyle.Default)
        var selectionWindowStatusVisible: Boolean? = null
        var dismissalWindowStatusVisible: Boolean? = null
        var activityWindow: Window? = null

        compose.setContent {
            activityWindow = requireNotNull(LocalContext.current.findActivity()).window
            CompositionLocalProvider(LocalForceHideStatusBar provides forceHide.value) {
                // Mirror the single Activity-window policy used by the player. The picker must
                // independently apply it to its ModalBottomSheet dialog window.
                SystemBarsVisibility()
                Column {
                    Button(
                        onClick = { pickerVisible.value = true },
                        modifier = Modifier.testTag("open-player-cover-picker"),
                    ) { Text("选择封面") }
                    if (pickerVisible.value) {
                        PlayerAppearanceProvider(dark = true) {
                            PlayerCoverPicker(
                                selected = selected.value,
                                track = null,
                                immersive = forceHide.value, motionEnabled = false,
                                themeMode = ThemeMode.Auto, onThemeModeChange = {},
                                onSelect = { style ->
                                    selectionWindowStatusVisible = dialogWindow()?.let(::statusBarVisible)
                                    selected.value = style
                                    // Match FullPlayerPageContent: applying a card closes the picker.
                                    pickerVisible.value = false
                                },
                                onToggleImmersive = {},
                                onDismiss = {
                                    dismissalWindowStatusVisible = dialogWindow()?.let(::statusBarVisible)
                                    pickerVisible.value = false
                                },
                            )
                        }
                    }
                }
            }
        }

        val policies = listOf(
            StatusBarPolicy(hideSetting = true, forceHide = false, expectedHidden = true),
            StatusBarPolicy(hideSetting = false, forceHide = true, expectedHidden = true),
            StatusBarPolicy(hideSetting = false, forceHide = false, expectedHidden = false),
        )

        policies.forEach { policy ->
            compose.runOnIdle {
                MeloraSettings.hideStatusBar.value = policy.hideSetting
                forceHide.value = policy.forceHide
            }
            awaitVisibility({ activityWindow }, hidden = policy.expectedHidden)

            // Opening keeps the configured visibility in the separate sheet window.
            compose.onNodeWithTag("open-player-cover-picker").performClick()
            compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
            awaitVisibility(::dialogWindow, hidden = policy.expectedHidden)

            // Selecting a cover style closes the picker in the real player; record the dialog state
            // in the selection callback, then verify the Activity window is restored unchanged.
            val nextStyle = PlayerCoverStyle.entries.first { it != selected.value }
            compose.onNodeWithTag("player-cover-${nextStyle.storageValue}").performClick()
            compose.runOnIdle {
                assertEquals(nextStyle, selected.value)
                assertEquals(!policy.expectedHidden, selectionWindowStatusVisible)
            }
            compose.onNodeWithTag("player-cover-picker").assertDoesNotExist()
            awaitVisibility({ activityWindow }, hidden = policy.expectedHidden)

            // Reopen and use the explicit cancel action to cover the other close path.
            compose.onNodeWithTag("open-player-cover-picker").performClick()
            compose.onNodeWithTag("player-cover-picker").assertIsDisplayed()
            awaitVisibility(::dialogWindow, hidden = policy.expectedHidden)
            compose.onNodeWithText("取消").performClick()
            compose.runOnIdle {
                assertEquals(!policy.expectedHidden, dismissalWindowStatusVisible)
            }
            compose.onNodeWithTag("player-cover-picker").assertDoesNotExist()
            awaitVisibility({ activityWindow }, hidden = policy.expectedHidden)
        }
    }

    private fun awaitVisibility(window: () -> Window?, hidden: Boolean) {
        compose.waitUntil(timeoutMillis = 5_000) {
            var visible: Boolean? = null
            compose.runOnUiThread { visible = window()?.let(::statusBarVisible) }
            visible == !hidden
        }
    }

    private fun statusBarVisible(window: Window): Boolean? =
        ViewCompat.getRootWindowInsets(window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.statusBars())

    private fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun dialogWindow(): Window? = WindowInspector.getGlobalWindowViews()
        .asSequence()
        .flatMap(::descendants)
        .mapNotNull { it.dialogWindow() }
        .firstOrNull { it.decorView.isShown }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
        }
    }

    private fun View.dialogWindow(): Window? =
        generateSequence(parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()
            ?.window

    private data class StatusBarPolicy(
        val hideSetting: Boolean,
        val forceHide: Boolean,
        val expectedHidden: Boolean,
    )
}
