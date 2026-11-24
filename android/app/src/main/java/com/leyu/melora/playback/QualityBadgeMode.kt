package com.leyu.melora.playback

enum class QualityBadgeMode(val storageValue: String, val label: String) {
    Compact("compact", "短标"),
    IconText("icon-label", "图标+文字"),
    Split("split", "双段规格");

    companion object {
        fun restore(value: String?, fallback: QualityBadgeMode = Compact): QualityBadgeMode = when (value) {
            "text-only" -> Compact
            else -> entries.firstOrNull { it.storageValue == value } ?: fallback
        }
    }
}
