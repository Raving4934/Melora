package com.leyu.melora.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.leyu.melora.MainActivity

@Composable
internal fun rememberDesktopLyricsToggle(): (Boolean) -> Unit {
    val activity = LocalActivity.current as? MainActivity
    return remember(activity) { { enabled -> activity?.changeDesktopLyrics(enabled) } }
}
