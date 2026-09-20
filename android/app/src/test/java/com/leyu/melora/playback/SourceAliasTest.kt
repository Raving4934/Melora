package com.leyu.melora.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAliasTest {
    private val original = MeloraSettings.sourceAliasEnabled.value

    @After
    fun restoreSourceAliasSetting() {
        MeloraSettings.sourceAliasEnabled.value = original
    }

    @Test
    fun knownPlatformsUseAliasesWhenEnabled() {
        MeloraSettings.sourceAliasEnabled.value = true

        assertEquals("盒子音乐", SourceAlias.display("kw", "酷我"))
        assertEquals("小狗音乐", SourceAlias.display("kg", "酷狗"))
        assertEquals("企鹅音乐", SourceAlias.display("tx", "企鹅"))
        assertEquals("小云音乐", SourceAlias.display("wy", "网易云"))
        assertEquals("咕咕音乐", SourceAlias.display("mg", "咪咕"))
    }

    @Test
    fun originalNamesAndUnknownPlatformsRemainStableWhenDisabled() {
        MeloraSettings.sourceAliasEnabled.value = false

        assertEquals("酷我", SourceAlias.display("kw", "酷我"))
        assertEquals("自定义源", SourceAlias.display("custom", "自定义源"))
    }

    @Test
    fun displayMusicAddsSuffixOnlyWhenMissing() {
        MeloraSettings.sourceAliasEnabled.value = false

        assertEquals("酷狗音乐", SourceAlias.displayMusic("kg", "酷狗"))
        assertEquals("QQ音乐", SourceAlias.displayMusic("tx", "QQ音乐"))
        assertEquals("自定义音乐", SourceAlias.displayMusic("custom", "自定义"))

        MeloraSettings.sourceAliasEnabled.value = true
        assertEquals("小狗音乐", SourceAlias.displayMusic("kg", "酷狗音乐"))
        assertEquals("自定义音乐", SourceAlias.displayMusic("custom", "自定义音乐"))
    }
}
