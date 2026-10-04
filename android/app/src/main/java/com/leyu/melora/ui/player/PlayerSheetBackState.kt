package com.leyu.melora.ui.player

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** 底页和覆盖层直接读取同一状态，不经展开阈值或异步回调中转。 */
internal class PlayerSheetBackState(val sheet: AnchoredDraggableState<PlayerSheetAnchor>) {
    var transition by mutableStateOf<PlayerSheetTransition?>(null)
    private var transitionJob: Job? = null
    var expandRequest by mutableIntStateOf(0)
        private set

    fun requestExpand() { expandRequest++ }

    /** 新意图接替旧任务；被取消任务的finally不能清掉后继返回的所有权。 */
    fun transitionTo(scope: CoroutineScope, intent: PlayerSheetTransition, block: suspend () -> Unit) {
        if (transition == intent) return
        transitionJob?.cancel()
        transition = intent
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (transitionJob === coroutineContext.job) {
                    transitionJob = null
                    transition = null
                }
            }
        }
        transitionJob = job
        job.start()
    }

    val ownsBack by derivedStateOf { action() != PlayerSheetBackAction.PassThrough }

    fun action(queueReturnTarget: PlayerSheetAnchor? = null): PlayerSheetBackAction =
        playerSheetBackAction(
            settled = sheet.settledValue,
            target = sheet.targetValue,
            progress = playerSheetProgress(sheet.offset, sheet.anchors.positionOf(PlayerSheetAnchor.Collapsed)),
            animationRunning = sheet.isAnimationRunning,
            transition = transition,
            queueReturnTarget = queueReturnTarget,
        )
}

@Composable
internal fun rememberPlayerSheetBackState(): PlayerSheetBackState {
    val sheet = rememberSaveable(saver = AnchoredDraggableState.Saver<PlayerSheetAnchor>()) {
        AnchoredDraggableState(PlayerSheetAnchor.Collapsed)
    }
    return remember(sheet) { PlayerSheetBackState(sheet) }
}
