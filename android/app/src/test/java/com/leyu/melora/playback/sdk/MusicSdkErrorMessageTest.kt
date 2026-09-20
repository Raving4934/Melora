package com.leyu.melora.playback.sdk

import com.leyu.melora.playback.MeloraSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicSdkErrorMessageTest {
    @Test
    fun `retry exhaustion is converted to a platform-specific message`() {
        val original = MeloraSettings.sourceAliasEnabled.value
        try {
            for (aliases in listOf(false, true)) {
                MeloraSettings.sourceAliasEnabled.value = aliases
                assertEquals(
                    "${if (aliases) "小狗音乐" else "酷狗"}歌单暂时无法加载，请稍后重试",
                    userFacingMusicSdkError("playlistSongs", "kg", IllegalStateException("try max num")),
                )
                assertEquals(
                    "${if (aliases) "咕咕音乐" else "咪咕"}封面暂时无法加载，请稍后重试",
                    userFacingMusicSdkError("pic", "mg", IllegalStateException("link get failed")),
                )
            }
        } finally {
            MeloraSettings.sourceAliasEnabled.value = original
        }
    }

    @Test
    fun `existing business messages remain unchanged`() {
        assertEquals(
            "目录请求超时",
            userFacingMusicSdkError("playlistSongs", "kg", IllegalStateException("目录请求超时")),
        )
    }

    @Test
    fun `missing exception messages use a stable fallback`() {
        assertEquals(
            "在线内容请求失败，请稍后重试",
            userFacingMusicSdkError("search", "kw", IllegalStateException()),
        )
    }
}
