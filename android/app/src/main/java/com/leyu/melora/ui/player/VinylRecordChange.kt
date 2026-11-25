package com.leyu.melora.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/** 歌名随播放状态更新，纸标在盘面轻缩到位时交接。 */
internal class VinylRecordChange(uid: String, artwork: String?) {
    var displayedUid by mutableStateOf(uid)
        private set
    var displayedArtwork by mutableStateOf(artwork)
        private set

    private val discScale = Animatable(1f)
    private val lift = Animatable(0f)
    val scale get() = discScale.value
    val armLift get() = lift.value

    fun show(uid: String, artwork: String?) {
        displayedUid = uid
        displayedArtwork = artwork
    }

    suspend fun changeTo(uid: String, artwork: () -> String?) {
        if (displayedUid != uid) {
            // 按切歌时已知的封面决定是否缩盘；抬臂期间回填的图片只更新纸标。
            val differentCover = displayedArtwork != artwork()
            if (lift.value < 0.45f) lift.animateTo(0.45f, tween(170, easing = FastOutSlowInEasing))
            if (differentCover && discScale.value != 0.97f) {
                discScale.animateTo(0.97f, tween(140, easing = FastOutSlowInEasing))
            }
            show(uid, artwork())
        }
        if (discScale.value != 1f) discScale.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        if (lift.value != 0f) lift.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
    }
}

@Composable
internal fun rememberVinylRecordChange(uid: String, artwork: String?, enabled: Boolean): VinylRecordChange {
    val record = remember(enabled) { VinylRecordChange(uid, artwork) }
    val latestArtwork by rememberUpdatedState(artwork)
    SideEffect {
        // 同曲封面回填或切到后台时立即同步，不触发下一次换碟。
        if (!enabled || record.displayedUid == uid) record.show(uid, artwork)
    }
    if (enabled) LaunchedEffect(uid) { record.changeTo(uid) { latestArtwork } }
    return record
}
