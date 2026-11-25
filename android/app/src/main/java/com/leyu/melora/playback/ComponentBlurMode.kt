package com.leyu.melora.playback

import android.content.SharedPreferences
import androidx.core.content.edit

enum class ComponentBlurMode(val storageValue: String) {
    Off("off"), Header("header"), All("all");

    companion object {
        internal fun restore(value: String?, fallback: ComponentBlurMode = Off): ComponentBlurMode =
            entries.firstOrNull { it.storageValue == value } ?: fallback

        internal fun fromLegacy(enabled: Boolean): ComponentBlurMode = if (enabled) Header else Off
    }
}

private const val LEGACY_BLUR_TOP_BAR = "basic.blurTopBar"

internal fun loadComponentBlurMode(preferences: SharedPreferences): ComponentBlurMode {
    val key = MeloraSettings.KEY_COMPONENT_BLUR_MODE
    val stored = preferences.getString(key, null)
    if (!preferences.contains(LEGACY_BLUR_TOP_BAR)) return ComponentBlurMode.restore(stored)

    val mode = if (stored == null) {
        ComponentBlurMode.fromLegacy(preferences.getBoolean(LEGACY_BLUR_TOP_BAR, false))
    } else {
        ComponentBlurMode.restore(stored)
    }
    preferences.edit {
        if (stored == null) putString(key, mode.storageValue)
        remove(LEGACY_BLUR_TOP_BAR)
    }
    return mode
}
