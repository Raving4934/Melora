package com.leyu.melora.playback

/**
 * 歌曲来源显示名称：按平台 id 映射为别名（默认启用），
 * 关闭开关后返回各源原始名称。
 */
object SourceAlias {
    private val aliases = mapOf(
        "kw" to "盒子音乐",
        "kg" to "小狗音乐",
        "tx" to "企鹅音乐",
        "wy" to "小云音乐",
        "mg" to "咕咕音乐",
    )

    fun display(id: String, original: String): String =
        if (MeloraSettings.sourceAliasEnabled.value) aliases[id] ?: original else original

    /** 搜索平台下拉的「XX音乐」：别名本身已含「音乐」，避免重复拼接。 */
    fun displayMusic(id: String, originalLabel: String): String {
        val original = originalLabel.ensureMusicSuffix()
        return if (MeloraSettings.sourceAliasEnabled.value) aliases[id] ?: original else original
    }

    private fun String.ensureMusicSuffix(): String =
        if (endsWith("音乐")) this else "${this}音乐"
}
