package com.leyu.melora.ui.my

internal const val PLAYLIST_UPDATE_RULES = "按原歌单更新歌曲，保留本地名称、额外添加的歌曲和手动移除记录。可在「其他设置 → 自动更新」开启自动更新。"

internal enum class PlaylistUpdateAction { READ, CONFIRM, DISMISS }

internal data class PlaylistUpdateActions(
    val primary: PlaylistUpdateAction,
    val primaryLabel: String,
    val primaryEnabled: Boolean,
    val secondary: PlaylistUpdateAction,
    val secondaryLabel: String,
    val secondaryEnabled: Boolean,
)

/** Presentational policy only: a no-op preview closes without entering the commit path. */
internal fun playlistUpdateActions(state: PlaylistUpdateState, canRead: Boolean): PlaylistUpdateActions {
    val preview = state.preview
    val primary = when {
        state.saving -> PlaylistUpdateAction.CONFIRM
        state.loading || preview == null -> PlaylistUpdateAction.READ
        preview.hasChanges -> PlaylistUpdateAction.CONFIRM
        else -> PlaylistUpdateAction.DISMISS
    }
    val secondary = if (preview != null && !state.loading) PlaylistUpdateAction.READ else PlaylistUpdateAction.DISMISS
    return PlaylistUpdateActions(
        primary = primary,
        primaryLabel = when {
            state.saving -> "保存中…"
            state.loading -> "读取中…"
            primary == PlaylistUpdateAction.DISMISS -> "完成"
            preview != null && state.error != null -> "重试保存"
            preview != null -> "确认更新"
            state.error != null -> "重试读取"
            else -> "读取更新"
        },
        primaryEnabled = !state.busy && (primary != PlaylistUpdateAction.READ || canRead),
        secondary = secondary,
        secondaryLabel = if (secondary == PlaylistUpdateAction.READ) "重新读取" else "取消",
        secondaryEnabled = if (secondary == PlaylistUpdateAction.READ) !state.busy && canRead else !state.saving,
    )
}
