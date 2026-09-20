package com.leyu.melora.playback

enum class PlayerCoverStyle(val storageValue: String) {
    Default("default"),
    Circle("circle"),
    Vinyl("vinyl");

    companion object {
        fun restore(value: String?, fallback: PlayerCoverStyle = Default): PlayerCoverStyle =
            entries.firstOrNull { it.storageValue == value } ?: fallback
    }
}
