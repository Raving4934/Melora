package com.leyu.melora.ui.my

import com.leyu.melora.playback.*
import com.leyu.melora.playback.sdk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaylistUpdateActionsTest {
    private val link = PlaylistImportLink.parse("https://music.163.com/#/playlist?id=123")
    private fun song(id: String) = OnlineSong(JSONObject().put("source", "wy").put("songmid", id).put("name", id))
    private val local get() = UserLibrary.UserPlaylist("local", "本地名", listOf(song("a")), link, setOf("wy_a"))
    private val unchanged get() = PlaylistSyncPreview(local, local)

    @Test fun permanentRulesContainTheWholeManualMergePolicyInOneShortNote() {
        assertEquals("保留本地名称和额外歌曲，按原歌单排序；本地删除但原歌单仍有的歌曲会恢复。仅手动更新，不定时同步。", PLAYLIST_UPDATE_RULES)
    }

    @Test fun statesChooseSafeActionsAndBusyStatesCannotSubmitTwice() {
        val changed = PlaylistSyncPreview(local, local.copy(songs = listOf(song("b"))))
        val binding = PlaylistSyncPreview(local.copy(importSource = null), local)
        data class Case(
            val state: PlaylistUpdateState,
            val canRead: Boolean,
            val primary: PlaylistUpdateAction,
            val label: String,
            val enabled: Boolean,
            val secondary: PlaylistUpdateAction,
            val secondaryEnabled: Boolean,
        )
        val cases = listOf(
            Case(PlaylistUpdateState(), false, PlaylistUpdateAction.READ, "读取更新", false, PlaylistUpdateAction.DISMISS, true),
            Case(PlaylistUpdateState(), true, PlaylistUpdateAction.READ, "读取更新", true, PlaylistUpdateAction.DISMISS, true),
            Case(PlaylistUpdateState(error = "网络失败"), true, PlaylistUpdateAction.READ, "重试读取", true, PlaylistUpdateAction.DISMISS, true),
            Case(PlaylistUpdateState(preview = changed), true, PlaylistUpdateAction.CONFIRM, "确认更新", true, PlaylistUpdateAction.READ, true),
            Case(PlaylistUpdateState(preview = binding), true, PlaylistUpdateAction.CONFIRM, "确认绑定并更新", true, PlaylistUpdateAction.READ, true),
            Case(PlaylistUpdateState(preview = changed, error = "磁盘已满"), true, PlaylistUpdateAction.CONFIRM, "重试保存", true, PlaylistUpdateAction.READ, true),
            Case(PlaylistUpdateState(loading = true), true, PlaylistUpdateAction.READ, "读取中…", false, PlaylistUpdateAction.DISMISS, true),
            Case(PlaylistUpdateState(preview = changed, saving = true), true, PlaylistUpdateAction.CONFIRM, "保存中…", false, PlaylistUpdateAction.READ, false),
            Case(PlaylistUpdateState(preview = unchanged, loading = true), true, PlaylistUpdateAction.READ, "读取中…", false, PlaylistUpdateAction.DISMISS, true),
        )
        cases.forEach { case ->
            val actions = playlistUpdateActions(case.state, case.canRead)
            assertEquals(case.label, case.primary, actions.primary)
            assertEquals(case.label, actions.primaryLabel)
            assertEquals(case.label, case.enabled, actions.primaryEnabled)
            assertEquals(case.label, case.secondary, actions.secondary)
            assertEquals(case.label, case.secondaryEnabled, actions.secondaryEnabled)
            assertEquals(if (case.secondary == PlaylistUpdateAction.READ) "重新读取" else "取消", actions.secondaryLabel)
        }
    }

    @Test fun unchangedPreviewOffersEnabledDismissInsteadOfCommitAndSecondaryRead() {
        val actions = playlistUpdateActions(PlaylistUpdateState(preview = unchanged), canRead = true)
        assertEquals("完成", actions.primaryLabel)
        assertEquals(PlaylistUpdateAction.DISMISS, actions.primary)
        assertTrue(actions.primaryEnabled)
        assertEquals("重新读取", actions.secondaryLabel)
        assertEquals(PlaylistUpdateAction.READ, actions.secondary)
        assertTrue(actions.secondaryEnabled)
    }
}
