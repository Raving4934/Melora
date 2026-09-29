package com.leyu.melora.ui.common

import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class RootBackCallbackTest {
    @Test
    fun onlyNonBackKeyDownDiscardsArmedConfirmation() {
        val cases = listOf(
            Triple(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, true),
            Triple(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, true),
            Triple(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, true),
            Triple(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, false),
            Triple(KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_ENTER, false),
            Triple(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, false),
            Triple(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, false),
        )
        for ((action, keyCode, shouldReset) in cases) {
            val actions = mutableListOf<String>()
            val root = RootBackCallback({ 100L }, { actions += "hint" }, { actions += "background" })
            val dispatcher = OnBackPressedDispatcher()
            dispatcher.addCallback(root)
            dispatcher.onBackPressed()
            if (shouldResetRootBackOnKeyEvent(action, keyCode)) root.reset()
            dispatcher.onBackPressed()
            assertEquals(
                "action=$action keyCode=$keyCode",
                listOf("hint", if (shouldReset) "hint" else "background"),
                actions,
            )
        }
    }

    @Test
    fun keyboardDismissalDoesNotArmOrConfirmRootExit() {
        var keyboardVisible = false
        val actions = mutableListOf<String>()
        val callback = RootBackCallback(
            nowMillis = { 100L },
            showHint = { actions += "hint" },
            moveTaskToBack = { actions += "background" },
            dismissKeyboard = {
                if (keyboardVisible) {
                    keyboardVisible = false
                    actions += "keyboard"
                    true
                } else false
            },
        )
        callback.handleOnBackPressed()
        keyboardVisible = true
        callback.handleOnBackPressed()
        callback.handleOnBackPressed()
        assertEquals(listOf("hint", "keyboard", "hint"), actions)
    }

    @Test
    fun resetDiscardsArmedConfirmationEvenWithinWindow() {
        val actions = mutableListOf<String>()
        val callback = RootBackCallback({ 100L }, { actions += "hint" }, { actions += "background" })
        callback.handleOnBackPressed()
        callback.reset()
        callback.reset()
        callback.handleOnBackPressed()
        assertEquals(listOf("hint", "hint"), actions)
    }

    @Test
    fun laterEnabledPageHandlersTakePriorityOverRootFallback() {
        val actions = mutableListOf<String>()
        val root = RootBackCallback({ 100L }, { actions += "hint" }, { actions += "background" })
        val dispatcher = OnBackPressedDispatcher()
        dispatcher.addCallback(root)
        dispatcher.onBackPressed()
        val page = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                root.reset()
                actions += "page"
            }
        }
        dispatcher.addCallback(page)
        dispatcher.onBackPressed()
        dispatcher.onBackPressed()
        page.isEnabled = false
        dispatcher.onBackPressed()
        dispatcher.onBackPressed()
        assertEquals(listOf("hint", "page", "page", "hint", "background"), actions)
    }

    @Test
    fun expiredOrInvalidClockWindowShowsFreshHintThenAllowsConfirmation() {
        for (elapsed in listOf(2_000L, 2_001L, 60_000L, -1L)) {
            var now = 10_000L
            val actions = mutableListOf<String>()
            val callback = RootBackCallback({ now }, { actions += "hint" }, { actions += "background" })
            callback.handleOnBackPressed()
            now += elapsed
            callback.handleOnBackPressed()
            now += 1L
            callback.handleOnBackPressed()
            assertEquals("elapsed=$elapsed", listOf("hint", "hint", "background"), actions)
        }
    }

    @Test
    fun secondBackWithinTwoSecondsBackgroundsTaskAndConsumesConfirmation() {
        var now = 0L
        val actions = mutableListOf<String>()
        val callback = RootBackCallback({ now }, { actions += "hint" }, { actions += "background" })

        callback.handleOnBackPressed()
        now = 1_999L
        callback.handleOnBackPressed()
        callback.handleOnBackPressed()

        assertEquals(listOf("hint", "background", "hint"), actions)
    }

    @Test
    fun firstBackOnlyShowsHint() {
        val actions = mutableListOf<String>()
        val callback = RootBackCallback(
            nowMillis = { 0L },
            showHint = { actions += "hint" },
            moveTaskToBack = { actions += "background" },
        )

        callback.handleOnBackPressed()

        assertEquals(listOf("hint"), actions)
    }
}
