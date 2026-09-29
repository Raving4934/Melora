package com.leyu.melora.ui.player

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** 底页和覆盖层直接读取同一状态，不经展开阈值或异步回调中转。 */
internal class PlayerSheetBackState(val sheet: AnchoredDraggableState<PlayerSheetAnchor>) {
    var pending by mutableStateOf(false)

    val ownsBack by derivedStateOf { action() != PlayerSheetBackAction.PassThrough }

    fun action(queueVisible: Boolean = false, pagerScrolling: Boolean = false): PlayerSheetBackAction =
        playerSheetBackAction(
            settled = sheet.settledValue,
            target = sheet.targetValue,
            progress = playerSheetProgress(sheet.offset, sheet.anchors.positionOf(PlayerSheetAnchor.Collapsed)),
            animationRunning = sheet.isAnimationRunning,
            backInProgress = pending || pagerScrolling,
            queueVisible = queueVisible,
        )
}

@Composable
internal fun rememberPlayerSheetBackState(): PlayerSheetBackState {
    val sheet = rememberSaveable(saver = AnchoredDraggableState.Saver<PlayerSheetAnchor>()) {
        AnchoredDraggableState(PlayerSheetAnchor.Collapsed)
    }
    return remember(sheet) { PlayerSheetBackState(sheet) }
}
