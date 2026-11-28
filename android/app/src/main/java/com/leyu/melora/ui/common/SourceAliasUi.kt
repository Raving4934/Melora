package com.leyu.melora.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leyu.melora.playback.MeloraSettings
import com.leyu.melora.playback.PlatformSource
import com.leyu.melora.playback.SourceAlias

internal val PlatformSource.dotColor: Color
    get() = when (this) {
        PlatformSource.Kuwo -> Color(0xFFFFB300)
        PlatformSource.Kugou -> Color(0xFF0091EA)
        PlatformSource.Netease -> Color(0xFFE53935)
        PlatformSource.Tencent -> Color(0xFF00C853)
        PlatformSource.Migu -> Color(0xFF8E24AA)
        PlatformSource.All -> Color(0xFF2E7D32)
    }

/**
 * 为整棵 Compose UI 订阅来源别名开关，避免各个文本调用方直接读取 StateFlow.value。
 * 具体文案仍复用播放层 SourceAlias，保持别名映射的唯一来源。
 */
private val LocalSourceAliasEnabled = compositionLocalOf { MeloraSettings.sourceAliasEnabled.value }

@Composable
internal fun SourceAliasProvider(content: @Composable () -> Unit) {
    val enabled by MeloraSettings.sourceAliasEnabled.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalSourceAliasEnabled provides enabled, content = content)
}

@Composable
internal fun sourceAliasDisplay(id: String, original: String): String {
    val enabled = LocalSourceAliasEnabled.current
    return remember(enabled, id, original) { SourceAlias.display(id, original) }
}

@Composable
internal fun sourceAliasDisplayMusic(id: String, originalLabel: String): String {
    val enabled = LocalSourceAliasEnabled.current
    return remember(enabled, id, originalLabel) { SourceAlias.displayMusic(id, originalLabel) }
}
