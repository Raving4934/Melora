package com.leyu.melora.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leyu.melora.playback.RecordConflict
import com.leyu.melora.ui.common.MeloraBottomSheet
import com.leyu.melora.ui.common.rememberMeloraBottomSheetState
import com.leyu.melora.ui.common.rememberSheetDismiss
import com.leyu.melora.ui.common.runCatchingCancellable
import com.leyu.melora.ui.theme.MeloraAppearance
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordConflictSheet(
    conflicts: List<RecordConflict>,
    onDismiss: () -> Unit,
    onResolve: suspend (Map<String, Boolean>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var lastConflicts by remember { mutableStateOf(conflicts) }
    val activeKeys = remember(conflicts) { conflicts.mapTo(hashSetOf()) { it.key } }
    val selected = choices.filterKeys(activeKeys::contains)
    val sheet = rememberMeloraBottomSheetState(skipPartiallyExpanded = true) { it != SheetValue.Hidden || !working }
    val close = rememberSheetDismiss(sheet)
    LaunchedEffect(conflicts) {
        choices = choices.filterKeys(activeKeys::contains)
        if (conflicts.isNotEmpty()) lastConflicts = conflicts
    }
    LaunchedEffect(conflicts.isEmpty(), working) {
        if (conflicts.isEmpty() && !working) close(onDismiss)
    }

    val title = "选择要保留的记录"
    val visible = conflicts.ifEmpty { lastConflicts }
    val enabled = !working && sheet.targetValue != SheetValue.Hidden
    MeloraBottomSheet(
        onDismissRequest = { if (!working) onDismiss() },
        sheetState = sheet,
        containerColor = MeloraAppearance.canvas,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
                .semantics { paneTitle = title },
        ) {
            Text(title, modifier = Modifier.semantics { heading() }, fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold, color = SettingsTextMain)
            Text("${visible.size} 项待确认，其他改动照常同步。", color = SettingsTextSub, fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = RecordConflict::key) { conflict ->
                    SettingsCard(contentPadding = PaddingValues(0.dp)) {
                        Column(Modifier.selectableGroup()) {
                            Text(
                                conflict.title,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SettingsTextMain,
                            )
                            ConflictChoice(conflict.localLabel, choices[conflict.key] == true, enabled) {
                                choices = choices + (conflict.key to true)
                            }
                            SettingsDivider(Modifier.padding(horizontal = 14.dp))
                            ConflictChoice(conflict.remoteLabel, choices[conflict.key] == false, enabled) {
                                choices = choices + (conflict.key to false)
                            }
                        }
                    }
                }
            }
            Text(
                text = failure ?: "可以先应用已选的记录，其余稍后处理。",
                modifier = Modifier.padding(bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = if (failure == null) SettingsTextSub else MaterialTheme.colorScheme.error,
                fontSize = 12.sp, lineHeight = 18.sp, minLines = 2,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SyncSettingsSheetAction("稍后处理", enabled, working = false, primary = false,
                    modifier = Modifier.weight(1f), onClick = { close(onDismiss) })
                SyncSettingsSheetAction("应用选择", enabled && selected.isNotEmpty(), working, primary = true,
                    modifier = Modifier.weight(1f), onClick = {
                        if (!working && selected.isNotEmpty() && sheet.targetValue != SheetValue.Hidden) {
                            working = true
                            failure = null
                            scope.launch {
                                try {
                                    runCatchingCancellable { onResolve(selected) }
                                        .onSuccess { choices = choices - selected.keys }
                                        .onFailure { failure = it.localizedMessage ?: "无法应用选择，请重试" }
                                } finally {
                                    working = false
                                }
                            }
                        }
                    })
            }
        }
    }
}

@Composable
private fun ConflictChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) MeloraAppearance.tintBlue else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(
            selectedColor = SettingsBrandBlue, unselectedColor = SettingsTextSub,
        ))
        Text(label, fontSize = 14.sp, lineHeight = 20.sp, color = SettingsTextMain, modifier = Modifier.weight(1f))
    }
}
