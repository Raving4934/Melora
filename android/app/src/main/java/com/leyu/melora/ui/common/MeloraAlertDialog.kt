package com.leyu.melora.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** 确认弹窗统一沿用调用页面的尺寸；原生 Dialog 仍负责返回、焦点和窗口安全区。 */
@Composable
internal fun MeloraAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    containerColor: Color,
    shape: Shape = AlertDialogDefaults.shape,
) {
    val density = LocalDensity.current
    val dialogPaneTitle = stringResource(android.R.string.dialog_alert_title)
    Dialog(onDismissRequest = onDismissRequest) {
        CompositionLocalProvider(LocalDensity provides density) {
            Surface(
                modifier = Modifier.widthIn(min = 280.dp, max = 560.dp)
                    .semantics { paneTitle = dialogPaneTitle },
                shape = shape,
                color = containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(Modifier.padding(24.dp)) {
                    CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.weight(1f, fill = false).padding(bottom = 24.dp)) {
                        CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                        }
                    }
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                            // 保持 Material 的紧凑宽度；放不下时确认按钮排在取消按钮上方。
                            Layout(
                                modifier = Modifier.align(Alignment.End),
                                content = { Box { dismissButton() }; Box { confirmButton() } },
                            ) { measurables, constraints ->
                                val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                                val dismiss = measurables[0].measure(childConstraints)
                                val confirm = measurables[1].measure(childConstraints)
                                val rowWidth = dismiss.width + 8.dp.roundToPx() + confirm.width
                                val stacked = rowWidth > constraints.maxWidth
                                val width = constraints.constrainWidth(
                                    if (stacked) maxOf(dismiss.width, confirm.width) else rowWidth,
                                )
                                val height = constraints.constrainHeight(
                                    if (stacked) confirm.height + 12.dp.roundToPx() + dismiss.height
                                    else maxOf(dismiss.height, confirm.height),
                                )
                                layout(width, height) {
                                    confirm.placeRelative(width - confirm.width, 0)
                                    dismiss.placeRelative(
                                        if (stacked) width - dismiss.width else 0,
                                        if (stacked) confirm.height + 12.dp.roundToPx() else 0,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
