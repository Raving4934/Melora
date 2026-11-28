package com.leyu.melora.playback

enum class PlatformSource(
    val id: String,
    val label: String,
    internal val alias: String? = null,
    internal val boardLabel: String = label,
    internal val sdkLabel: String = boardLabel,
    internal val officialLabel: String? = if (sdkLabel.endsWith("音乐")) sdkLabel else "${sdkLabel}音乐",
) {
    Kuwo("kw", "酷我", "盒子音乐"),
    Kugou("kg", "酷狗", "小狗音乐"),
    Netease("wy", "网易", "小云音乐", boardLabel = "网易云"),
    Tencent("tx", "企鹅", "企鹅音乐", sdkLabel = "QQ音乐"),
    Migu("mg", "咪咕", "咕咕音乐"),
    All("all", "聚合", officialLabel = null);

    companion object {
        internal val musicPlatforms = entries.filterNot { it == All }
        internal val musicIds = musicPlatforms.map(PlatformSource::id)
        internal val boardOrder = listOf(Kuwo, Kugou, Tencent, Netease, Migu)

        internal fun fromId(id: String?): PlatformSource? = entries.firstOrNull { it.id == id }
        internal fun musicPlatform(id: String?): PlatformSource? = musicPlatforms.firstOrNull { it.id == id }
    }
}
