package com.leyu.melora.ui.player

// mm:ss 显示。
fun formatClock(ms: Long, padMinutes: Boolean = true): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return (if (padMinutes) "%02d:%02d" else "%d:%02d").format(minutes, seconds)
}
