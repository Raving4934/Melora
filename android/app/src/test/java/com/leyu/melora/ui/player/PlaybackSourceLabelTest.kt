package com.leyu.melora.ui.player

import com.leyu.melora.playback.PlayerUiState
import com.leyu.melora.playback.UiTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSourceLabelTest {
    @Test fun completeCacheDoesNotExposeScriptPlatformOrAutoSwitchHint() {
        listOf(null, "legacy", "lx:example.js:hash").forEach { id ->
            listOf(false, true).forEach { autoSwitch ->
                val state = state(id, "tx").copy(fromCompleteCache = true)
                assertEquals("播放源：缓存", playbackSourceLabel(state, autoSwitch))
            }
        }
    }

    @Test fun missingOrLegacyIdentityRetainsTheUnknownFallbackUnlessCacheHitIsConfirmed() {
        listOf(null, "legacy", "unverified").forEach { id ->
            assertEquals("播放源：未知/缓存", playbackSourceLabel(state(id), false))
        }
    }

    @Test fun unknownResolversDoNotClaimAScriptOrConfirmedCacheHit() {
        listOf("retired-provider:cached-file", "resolver-without-display-name", "lx::hash").forEach { id ->
            assertEquals("播放源：未知音源", playbackSourceLabel(state(id), false))
        }
    }

    @Test fun localPlaybackKeepsItsLabelWithoutTheAutoSwitchHint() {
        assertEquals("播放源：本地下载", playbackSourceLabel(state("local"), true))
        assertEquals("播放源：本地媒体", playbackSourceLabel(state("localmedia"), true))
    }

    @Test fun networkPlaybackRetainsActualPlatformAndAutoSwitchHint() {
        val state = state("lx:label-test.js:hash", "tx")
        assertEquals("播放源：label-test · QQ 音乐 · 已启用自动换源", playbackSourceLabel(state, true))
        assertEquals("播放源：label-test · QQ 音乐", playbackSourceLabel(state, false))
        assertEquals("播放源：label-test", playbackSourceLabel(state.copy(resolvedPlatform = "kw"), false))
    }

    @Test fun switchingBackToNetworkDoesNotKeepTheCacheLabel() {
        val cached = state("lx:label-test.js:hash", "tx").copy(fromCompleteCache = true)
        assertEquals("播放源：缓存", playbackSourceLabel(cached, true))
        val network = cached.copy(fromCompleteCache = false)
        assertEquals("播放源：label-test · QQ 音乐 · 已启用自动换源", playbackSourceLabel(network, true))
        assertEquals("播放源：未知/缓存", playbackSourceLabel(PlayerUiState(), false))
    }

    private fun state(id: String?, platform: String? = null) = PlayerUiState(
        current = UiTrack("kw_track", "歌曲", "歌手", "专辑", source = "kw"),
        resolvedBy = id,
        resolvedPlatform = platform,
    )
}
