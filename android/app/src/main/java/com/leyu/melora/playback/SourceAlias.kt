package com.leyu.melora.playback

/**
 * 歌曲来源显示名称：按平台 id 映射为别名（默认启用），
 * 关闭开关后返回各源原始名称。
 */
object SourceAlias {
    fun display(id: String, original: String): String =
        if (MeloraSettings.sourceAliasEnabled.value) PlatformSource.fromId(id)?.alias ?: original else original

    /** 搜索平台下拉的「XX音乐」：别名本身已含「音乐」，避免重复拼接。 */
    fun displayMusic(id: String, originalLabel: String): String = display(id, originalLabel.ensureMusicSuffix())

    private fun String.ensureMusicSuffix(): String =
        if (endsWith("音乐")) this else "${this}音乐"
}
