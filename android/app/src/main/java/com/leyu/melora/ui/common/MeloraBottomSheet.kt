package com.leyu.melora.ui.common

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.leyu.melora.ui.theme.SystemBarsVisibility
import kotlinx.coroutines.launch

/** 内容滚动到下边界后，不能将剩余上抛速度交给抽屉的弹簧动画。下拉关闭仍由抽屉处理。 */
internal object SheetBoundaryFlingConnection : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y.coerceAtMost(0f))
}

/** 显式关闭与遮罩/返回一样先退场，再交还业务；重复点击不启动第二次关闭。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSheetDismiss(sheetState: SheetState): (() -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    var closing by remember(sheetState) { mutableStateOf(false) }
    return remember(sheetState, scope) {
        { onHidden ->
            if (!closing) {
                closing = true
                scope.launch {
                    try {
                        sheetState.hide()
                        if (!sheetState.isVisible) onHidden()
                    } finally {
                        closing = false
                    }
                }
            }
        }
    }
}

/** 统一抽屉内容的边界行为，不修改开关动画、展开档位或下拉关闭手势。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeloraBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    containerColor: Color,
    tonalElevation: Dp = 0.dp,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!LocalPageActive.current) return
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        scrimColor = scrimColor,
        tonalElevation = tonalElevation,
        shape = shape,
        dragHandle = dragHandle,
    ) {
        SystemBarsVisibility()
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Column(
                Modifier.fillMaxWidth()
                    .nestedScroll(SheetBoundaryFlingConnection)
                    // 静态菜单也通过嵌套滚动交接手势，避免直接拖拽将越界速度注入抽屉。
                    // 实际滚动距离仍由子列表消费，这里不增加第二份滚动位置。
                    .scrollable(rememberScrollableState { 0f }, Orientation.Vertical),
                content = content,
            )
        }
    }
}
