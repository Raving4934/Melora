package com.leyu.melora.ui.common

import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback

internal fun shouldResetRootBackOnKeyEvent(action: Int, keyCode: Int): Boolean =
    action == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK

/** Activity 最先注册的兜底返回；页面/弹层的回调后注册，优先消费返回。 */
internal class RootBackCallback(
    private val nowMillis: () -> Long,
    private val showHint: () -> Unit,
    private val moveTaskToBack: () -> Unit,
    private val dismissKeyboard: () -> Boolean = { false },
) : OnBackPressedCallback(true) {
    private var armedAt: Long? = null

    fun reset() {
        armedAt = null
    }

    override fun handleOnBackPressed() {
        if (dismissKeyboard()) {
            reset()
            return
        }
        val now = nowMillis()
        val previous = armedAt
        if (previous != null && now - previous in 0L until 2_000L) {
            armedAt = null
            moveTaskToBack()
        } else {
            armedAt = now
            showHint()
        }
    }
}
