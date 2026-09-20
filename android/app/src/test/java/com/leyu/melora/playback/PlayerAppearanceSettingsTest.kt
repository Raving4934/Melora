package com.leyu.melora.playback

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAppearanceSettingsTest {
    @Test
    fun defaultsPreserveExistingSettingsBehavior() {
        assertEquals(ThemeMode.Auto, MeloraSettings.themeMode.value)
        assertFalse(MeloraSettings.hideStatusBar.value)
        assertFalse(MeloraSettings.blurTopBar.value)
        assertFalse(MeloraSettings.showSongCovers.value)
        assertTrue(MeloraSettings.showExitButton.value)
        assertTrue(MeloraSettings.pullToRefresh.value)
        assertEquals(ThemeMode.Auto, MeloraSettings.playerThemeMode.value)
        assertFalse(MeloraSettings.keepScreenAwake.value)
        assertTrue(MeloraSettings.miniLyricsEnabled.value)
        assertEquals(PlayerCoverStyle.Default, MeloraSettings.playerCoverStyle.value)
    }

    @Test
    fun exportIncludesAllNewAppearanceFields() = withAppearanceSettings {
        MeloraSettings.hideStatusBar.value = true
        MeloraSettings.blurTopBar.value = true
        MeloraSettings.playerThemeMode.value = ThemeMode.Light
        MeloraSettings.keepScreenAwake.value = true
        MeloraSettings.miniLyricsEnabled.value = false
        MeloraSettings.playerCoverStyle.value = PlayerCoverStyle.Vinyl

        val settings = BackupSettings.collect()

        assertTrue(settings.getBoolean("hideStatusBar"))
        assertTrue(settings.getBoolean("blurTopBar"))
        assertEquals("light", settings.getString("playerThemeMode"))
        assertTrue(settings.getBoolean("keepScreenAwake"))
        assertFalse(settings.getBoolean("miniLyricsEnabled"))
        assertEquals("vinyl", settings.getString("playerCoverStyle"))
    }

    @Test
    fun coverStyleRestoreParsesAllValuesAndUsesFallback() {
        assertEquals(PlayerCoverStyle.Default, PlayerCoverStyle.restore("default"))
        assertEquals(PlayerCoverStyle.Circle, PlayerCoverStyle.restore("circle"))
        assertEquals(PlayerCoverStyle.Vinyl, PlayerCoverStyle.restore("vinyl"))
        assertEquals(PlayerCoverStyle.Circle, PlayerCoverStyle.restore("unknown", PlayerCoverStyle.Circle))
        assertEquals(PlayerCoverStyle.Vinyl, PlayerCoverStyle.restore(null, PlayerCoverStyle.Vinyl))
    }

    @Test
    fun missingAndInvalidImportValuesKeepCurrentSelections() = withAppearanceSettings {
        MeloraSettings.hideStatusBar.value = true
        MeloraSettings.blurTopBar.value = true
        MeloraSettings.playerThemeMode.value = ThemeMode.Light
        MeloraSettings.keepScreenAwake.value = true
        MeloraSettings.miniLyricsEnabled.value = false
        MeloraSettings.playerCoverStyle.value = PlayerCoverStyle.Circle

        BackupSettings.apply(BackupSettings.prepare(JSONObject(), emptySet(), emptySet()).first)
        assertAppearance(
            hideStatusBar = true,
            blurTopBar = true,
            playerThemeMode = ThemeMode.Light,
            keepScreenAwake = true,
            miniLyricsEnabled = false,
            playerCoverStyle = PlayerCoverStyle.Circle,
        )

        BackupSettings.apply(BackupSettings.prepare(
            JSONObject()
                .put("playerThemeMode", "sepia")
                .put("playerCoverStyle", "square"), emptySet(), emptySet()).first)
        assertEquals(ThemeMode.Light, MeloraSettings.playerThemeMode.value)
        assertEquals(PlayerCoverStyle.Circle, MeloraSettings.playerCoverStyle.value)
    }

    @Test
    fun allNewSettingsRoundTripThroughTheRealImportFunction() = withAppearanceSettings {
        MeloraSettings.hideStatusBar.value = true
        MeloraSettings.blurTopBar.value = false
        MeloraSettings.playerThemeMode.value = ThemeMode.Dark
        MeloraSettings.keepScreenAwake.value = true
        MeloraSettings.miniLyricsEnabled.value = false
        MeloraSettings.playerCoverStyle.value = PlayerCoverStyle.Vinyl
        val exported = BackupSettings.collect()

        MeloraSettings.hideStatusBar.value = false
        MeloraSettings.blurTopBar.value = true
        MeloraSettings.playerThemeMode.value = ThemeMode.Light
        MeloraSettings.keepScreenAwake.value = false
        MeloraSettings.miniLyricsEnabled.value = true
        MeloraSettings.playerCoverStyle.value = PlayerCoverStyle.Circle

        BackupSettings.apply(BackupSettings.prepare(exported, emptySet(), emptySet()).first)

        assertAppearance(
            hideStatusBar = true,
            blurTopBar = false,
            playerThemeMode = ThemeMode.Dark,
            keepScreenAwake = true,
            miniLyricsEnabled = false,
            playerCoverStyle = PlayerCoverStyle.Vinyl,
        )
    }

    private fun assertAppearance(
        hideStatusBar: Boolean,
        blurTopBar: Boolean,
        playerThemeMode: ThemeMode,
        keepScreenAwake: Boolean,
        miniLyricsEnabled: Boolean,
        playerCoverStyle: PlayerCoverStyle,
    ) {
        assertEquals(hideStatusBar, MeloraSettings.hideStatusBar.value)
        assertEquals(blurTopBar, MeloraSettings.blurTopBar.value)
        assertEquals(playerThemeMode, MeloraSettings.playerThemeMode.value)
        assertEquals(keepScreenAwake, MeloraSettings.keepScreenAwake.value)
        assertEquals(miniLyricsEnabled, MeloraSettings.miniLyricsEnabled.value)
        assertEquals(playerCoverStyle, MeloraSettings.playerCoverStyle.value)
    }

    private inline fun withAppearanceSettings(block: () -> Unit) {
        val originalHideStatusBar = MeloraSettings.hideStatusBar.value
        val originalBlurTopBar = MeloraSettings.blurTopBar.value
        val originalPlayerThemeMode = MeloraSettings.playerThemeMode.value
        val originalKeepScreenAwake = MeloraSettings.keepScreenAwake.value
        val originalMiniLyricsEnabled = MeloraSettings.miniLyricsEnabled.value
        val originalPlayerCoverStyle = MeloraSettings.playerCoverStyle.value
        try {
            block()
        } finally {
            MeloraSettings.hideStatusBar.value = originalHideStatusBar
            MeloraSettings.blurTopBar.value = originalBlurTopBar
            MeloraSettings.playerThemeMode.value = originalPlayerThemeMode
            MeloraSettings.keepScreenAwake.value = originalKeepScreenAwake
            MeloraSettings.miniLyricsEnabled.value = originalMiniLyricsEnabled
            MeloraSettings.playerCoverStyle.value = originalPlayerCoverStyle
        }
    }
}
