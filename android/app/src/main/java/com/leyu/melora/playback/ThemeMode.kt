package com.leyu.melora.playback

import android.content.SharedPreferences
import androidx.core.content.edit

enum class ThemeMode(val storageValue: String) {
    Auto("auto"), Light("light"), Dark("dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        Auto -> systemDark
        Light -> false
        Dark -> true
    }

    companion object {
        /** 新值优先；历史关闭“跟随系统”实际表示固定浅色。缺字段不覆盖当前选择。 */
        fun restore(value: String?, legacyFollowSystem: Boolean?, fallback: ThemeMode = Auto): ThemeMode =
            entries.firstOrNull { it.storageValue == value }
                ?: legacyFollowSystem?.let { if (it) Auto else Light }
                ?: fallback
    }
}

internal const val THEME_MODE_PREFERENCE = "basic.themeMode"
private const val LEGACY_FOLLOW_THEME = "basic.followSystemTheme"

/** 仅在载入边界迁移旧布尔值；完成后偏好文件只保留三档主题键。 */
internal fun loadThemeMode(preferences: SharedPreferences): ThemeMode {
    val stored = preferences.getString(THEME_MODE_PREFERENCE, null)
    val hasLegacy = preferences.contains(LEGACY_FOLLOW_THEME)
    val legacy = if (hasLegacy) preferences.getBoolean(LEGACY_FOLLOW_THEME, true) else null
    val mode = ThemeMode.restore(stored, legacy)
    if (stored != mode.storageValue || hasLegacy) {
        preferences.edit {
            putString(THEME_MODE_PREFERENCE, mode.storageValue)
            remove(LEGACY_FOLLOW_THEME)
        }
    }
    return mode
}
