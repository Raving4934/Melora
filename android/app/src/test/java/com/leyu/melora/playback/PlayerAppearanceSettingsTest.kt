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
        assertEquals(PlayMode.List, MeloraSettings.musicPlayMode.value)
    }

    @Test
    fun exportIncludesAllNewAppearanceFields() = withAppearanceSettings {
        MeloraSettings.hideStatusBar.value = true
        MeloraSettings.blurTopBar.value = true
        MeloraSettings.playerThemeMode.value = ThemeMode.Light
        MeloraSettings.keepScreenAwake.value = true
        MeloraSettings.miniLyricsEnabled.value = false
        MeloraSettings.playerCoverStyle.value = PlayerCoverStyle.Vinyl
        MeloraSettings.updateMusicPlayMode(PlayMode.Shuffle)

        val settings = BackupSettings.collect()

        assertTrue(settings.getBoolean("hideStatusBar"))
        assertTrue(settings.getBoolean("blurTopBar"))
        assertEquals("light", settings.getString("playerThemeMode"))
        assertTrue(settings.getBoolean("keepScreenAwake"))
        assertFalse(settings.getBoolean("miniLyricsEnabled"))
        assertEquals("vinyl", settings.getString("playerCoverStyle"))
        assertEquals("shuffle", settings.getString("musicPlayMode"))
    }

    @Test
    fun desktopLyricPositionIsScreenIndependentAndSerializesNullableFractions() {
        val position = DesktopLyricPosition(0.25f, 0.75f).normalized()
        val json = position.toJson()
        assertEquals(0.25, json.getDouble("xFraction"), 0.0)
        assertEquals(0.75, json.getDouble("yFraction"), 0.0)
        assertFalse(json.has("xPixels"))
        assertFalse(json.has("yPixels"))
        assertEquals(position, DesktopLyricPosition.fromJson(JSONObject(json.toString())))

        val partialJson = DesktopLyricPosition(xFraction = 0.25f).toJson()
        assertFalse(partialJson.has("yFraction"))
        assertEquals(1, partialJson.length())
        assertEquals(DesktopLyricPosition(0f, 1f), DesktopLyricPosition.fromJson(
            JSONObject().put("xFraction", -2f).put("yFraction", 3f)))

        assertEquals(DesktopLyricPosition(0f, 1f), DesktopLyricPosition(-2f, 3f).normalized())

        val aboveTop = DesktopLyricPosition(0.25f, -0.75f)
        val aboveTopJson = aboveTop.toJson()
        assertEquals(-0.75, aboveTopJson.getDouble("yFraction"), 0.0)
        assertEquals(aboveTop, DesktopLyricPosition.fromJson(JSONObject(aboveTopJson.toString())))
        assertEquals(DesktopLyricPosition(0.25f, -1f), DesktopLyricPosition(0.25f, -1.25f).normalized())
        assertEquals(DesktopLyricPosition(0.25f, -1f), DesktopLyricPosition.fromJson(
            JSONObject().put("xFraction", 0.25f).put("yFraction", -1.25f)))

        assertEquals(DesktopLyricPosition(), DesktopLyricPosition(Float.NaN, Float.POSITIVE_INFINITY).normalized())
        assertEquals(DesktopLyricPosition(), DesktopLyricPosition.fromJson(
            JSONObject().put("xFraction", "NaN").put("yFraction", "Infinity")))
    }

    @Test
    fun desktopLyricPositionBackupIsNestedAndOldBackupKeepsCurrentPosition() = withAppearanceSettings {
        val selected = DesktopLyricPosition(0.2f, 0.8f)
        MeloraSettings.updateDesktopLyricPosition(selected)
        val exported = BackupSettings.collect()
        assertEquals(0.2, exported.getJSONObject("desktopLyricPosition").getDouble("xFraction"), 1e-6)
        assertEquals(0.8, exported.getJSONObject("desktopLyricPosition").getDouble("yFraction"), 1e-6)

        BackupSettings.apply(BackupSettings.prepare(JSONObject().put("lyricFontSize", 20f), emptySet(), emptySet()).first)
        assertEquals(selected, MeloraSettings.desktopLyricPosition.value)
    }

    @Test
    fun alignmentChangesResetOnlyTheirPositionAxisAndOnlyWhenChanged() = withAppearanceSettings {
        val selected = DesktopLyricPosition(0.3f, 0.7f)
        MeloraSettings.lyricHAlign.value = 1
        MeloraSettings.lyricVAlign.value = 1
        MeloraSettings.updateDesktopLyricPosition(selected)

        MeloraSettings.updateLyricHAlign(1)
        assertEquals(selected, MeloraSettings.desktopLyricPosition.value)
        MeloraSettings.updateLyricHAlign(2)
        assertEquals(DesktopLyricPosition(null, 0.7f), MeloraSettings.desktopLyricPosition.value)
        MeloraSettings.updateLyricHAlign(2)
        assertEquals(DesktopLyricPosition(null, 0.7f), MeloraSettings.desktopLyricPosition.value)

        MeloraSettings.updateLyricVAlign(1)
        assertEquals(DesktopLyricPosition(null, 0.7f), MeloraSettings.desktopLyricPosition.value)
        MeloraSettings.updateLyricVAlign(0)
        assertEquals(DesktopLyricPosition(), MeloraSettings.desktopLyricPosition.value)
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
    fun musicPlayModeBackupRoundTripsEveryModeAndUnknownValuesKeepCurrentMode() = withAppearanceSettings {
        listOf(PlayMode.List, PlayMode.Single, PlayMode.Shuffle).forEach { selected ->
            MeloraSettings.updateMusicPlayMode(selected)
            val exported = BackupSettings.collect()
            assertEquals(selected.storageValue, exported.getString("musicPlayMode"))

            MeloraSettings.updateMusicPlayMode(PlayMode.List)
            BackupSettings.apply(BackupSettings.prepare(exported, emptySet(), emptySet()).first)
            assertEquals(selected, MeloraSettings.musicPlayMode.value)
        }

        MeloraSettings.updateMusicPlayMode(PlayMode.Shuffle)
        val oldBackup = BackupSettings.prepare(JSONObject(), emptySet(), emptySet()).first
        assertFalse(oldBackup.has("musicPlayMode"))
        BackupSettings.apply(oldBackup)
        assertEquals(PlayMode.Shuffle, MeloraSettings.musicPlayMode.value)

        val unknownMode = BackupSettings.prepare(
            JSONObject().put("musicPlayMode", "legacy-random"), emptySet(), emptySet(),
        ).first
        assertEquals("shuffle", unknownMode.getString("musicPlayMode"))
        BackupSettings.apply(unknownMode)
        assertEquals(PlayMode.Shuffle, MeloraSettings.musicPlayMode.value)
    }

    @Test
    fun missingAndInvalidImportValuesKeepCurrentSelections() = withAppearanceSettings {
        MeloraSettings.hideStatusBar.value = true
        MeloraSettings.blurTopBar.value = true
        MeloraSettings.playerThemeMode.value = ThemeMode.Light
        MeloraSettings.keepScreenAwake.value = true
        MeloraSettings.miniLyricsEnabled.value = false
        MeloraSettings.playerCoverStyle.value = PlayerCoverStyle.Circle
        MeloraSettings.updateMusicPlayMode(PlayMode.Single)

        BackupSettings.apply(BackupSettings.prepare(JSONObject(), emptySet(), emptySet()).first)
        assertAppearance(
            hideStatusBar = true,
            blurTopBar = true,
            playerThemeMode = ThemeMode.Light,
            keepScreenAwake = true,
            miniLyricsEnabled = false,
            playerCoverStyle = PlayerCoverStyle.Circle,
        )
        assertEquals(PlayMode.Single, MeloraSettings.musicPlayMode.value)

        BackupSettings.apply(BackupSettings.prepare(
            JSONObject()
                .put("playerThemeMode", "sepia")
                .put("playerCoverStyle", "square")
                .put("musicPlayMode", "unexpected"), emptySet(), emptySet()).first)
        assertEquals(ThemeMode.Light, MeloraSettings.playerThemeMode.value)
        assertEquals(PlayerCoverStyle.Circle, MeloraSettings.playerCoverStyle.value)
        assertEquals(PlayMode.Single, MeloraSettings.musicPlayMode.value)
    }

    @Test
    fun allNewSettingsRoundTripThroughTheRealImportFunction() = withAppearanceSettings {
        MeloraSettings.hideStatusBar.value = true
        MeloraSettings.blurTopBar.value = false
        MeloraSettings.playerThemeMode.value = ThemeMode.Dark
        MeloraSettings.keepScreenAwake.value = true
        MeloraSettings.miniLyricsEnabled.value = false
        MeloraSettings.playerCoverStyle.value = PlayerCoverStyle.Vinyl
        MeloraSettings.updateMusicPlayMode(PlayMode.Shuffle)
        val exported = BackupSettings.collect()

        MeloraSettings.hideStatusBar.value = false
        MeloraSettings.blurTopBar.value = true
        MeloraSettings.playerThemeMode.value = ThemeMode.Light
        MeloraSettings.keepScreenAwake.value = false
        MeloraSettings.miniLyricsEnabled.value = true
        MeloraSettings.playerCoverStyle.value = PlayerCoverStyle.Circle
        MeloraSettings.updateMusicPlayMode(PlayMode.List)

        BackupSettings.apply(BackupSettings.prepare(exported, emptySet(), emptySet()).first)

        assertAppearance(
            hideStatusBar = true,
            blurTopBar = false,
            playerThemeMode = ThemeMode.Dark,
            keepScreenAwake = true,
            miniLyricsEnabled = false,
            playerCoverStyle = PlayerCoverStyle.Vinyl,
        )
        assertEquals(PlayMode.Shuffle, MeloraSettings.musicPlayMode.value)
    }

    @Test
    fun obsoleteExperimentalUiFieldsDoNotAffectExistingCoverAndColorSettings() = withAppearanceSettings {
        val imported = JSONObject().put("playerVisualTheme", "archive")
            .put("playerCoverStyle", "circle").put("playerThemeMode", "dark")
        val prepared = BackupSettings.prepare(imported, emptySet(), emptySet()).first
        assertFalse(prepared.has("playerVisualTheme"))
        BackupSettings.apply(prepared)
        assertEquals(PlayerCoverStyle.Circle, MeloraSettings.playerCoverStyle.value)
        assertEquals(ThemeMode.Dark, MeloraSettings.playerThemeMode.value)
        assertFalse(BackupSettings.collect().has("playerVisualTheme"))
    }

    @Test
    fun playerLyricsRoundTripThroughBackupWithoutChangingDesktopLyrics() = withAppearanceSettings {
        val selected = LyricsUiConfig(28f, true, true, true)
        val desktopSize = MeloraSettings.lyricFontSize.value
        MeloraSettings.updatePlayerLyrics(selected)
        val backup = JSONObject(BackupSettings.collect().toString())
        assertEquals(28.0, backup.getJSONObject("playerLyrics").getDouble("fontSizeSp"), 0.0)
        MeloraSettings.updatePlayerLyrics(LyricsUiConfig())
        BackupSettings.apply(BackupSettings.prepare(backup, emptySet(), emptySet()).first)
        assertEquals(selected, MeloraSettings.playerLyrics.value)
        assertEquals(desktopSize, MeloraSettings.lyricFontSize.value, 0f)
    }

    @Test
    fun oldBackupWithoutPlayerLyricsKeepsCurrentPreference() = withAppearanceSettings {
        val selected = LyricsUiConfig(26f, true, false, true)
        MeloraSettings.updatePlayerLyrics(selected)
        BackupSettings.apply(BackupSettings.prepare(JSONObject().put("lyricFontSize", 20f), emptySet(), emptySet()).first)
        assertEquals(selected, MeloraSettings.playerLyrics.value)
    }

    @Test
    fun legacyDesktopLyricAnimationBackupFieldIsIgnoredWithoutAffectingOtherSettings() = withAppearanceSettings {
        val prepared = BackupSettings.prepare(
            JSONObject().put("lyricAnim", false).put("lyricFontSize", 24f),
            emptySet(),
            emptySet(),
        ).first

        assertFalse(prepared.has("lyricAnim"))
        BackupSettings.apply(prepared)
        assertEquals(24f, MeloraSettings.lyricFontSize.value, 0f)
        assertFalse(BackupSettings.collect().has("lyricAnim"))
    }

    @Test
    fun legacyDesktopLyricWindowPercentBackupFieldIsIgnoredWhileSupportedSettingsRestore() = withAppearanceSettings {
        val prepared = BackupSettings.prepare(
            JSONObject().put("lyricWindowPercent", "not-a-number").put("lyricFontSize", 24f),
            emptySet(),
            emptySet(),
        ).first

        assertFalse(prepared.has("lyricWindowPercent"))
        BackupSettings.apply(prepared)
        assertEquals(24f, MeloraSettings.lyricFontSize.value, 0f)
        assertFalse(BackupSettings.collect().has("lyricWindowPercent"))
    }

    @Test
    fun invalidPlayerLyricsBackupTypeIsRejectedBeforeApply() = withAppearanceSettings {
        val selected = LyricsUiConfig(24f, isBold = true)
        MeloraSettings.updatePlayerLyrics(selected)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            BackupSettings.prepare(JSONObject().put("playerLyrics", "invalid"), emptySet(), emptySet())
        }
        assertEquals(selected, MeloraSettings.playerLyrics.value)
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
        val originalPlayerLyrics = MeloraSettings.playerLyrics.value
        val originalDesktopSize = MeloraSettings.lyricFontSize.value
        val originalHAlign = MeloraSettings.lyricHAlign.value
        val originalVAlign = MeloraSettings.lyricVAlign.value
        val originalDesktopLyricPosition = MeloraSettings.desktopLyricPosition.value
        val originalHideStatusBar = MeloraSettings.hideStatusBar.value
        val originalBlurTopBar = MeloraSettings.blurTopBar.value
        val originalPlayerThemeMode = MeloraSettings.playerThemeMode.value
        val originalKeepScreenAwake = MeloraSettings.keepScreenAwake.value
        val originalMiniLyricsEnabled = MeloraSettings.miniLyricsEnabled.value
        val originalPlayerCoverStyle = MeloraSettings.playerCoverStyle.value
        val originalMusicPlayMode = MeloraSettings.musicPlayMode.value
        try {
            block()
        } finally {
            MeloraSettings.playerLyrics.value = originalPlayerLyrics
            MeloraSettings.lyricFontSize.value = originalDesktopSize
            MeloraSettings.lyricHAlign.value = originalHAlign
            MeloraSettings.lyricVAlign.value = originalVAlign
            MeloraSettings.desktopLyricPosition.value = originalDesktopLyricPosition
            MeloraSettings.hideStatusBar.value = originalHideStatusBar
            MeloraSettings.blurTopBar.value = originalBlurTopBar
            MeloraSettings.playerThemeMode.value = originalPlayerThemeMode
            MeloraSettings.keepScreenAwake.value = originalKeepScreenAwake
            MeloraSettings.miniLyricsEnabled.value = originalMiniLyricsEnabled
            MeloraSettings.playerCoverStyle.value = originalPlayerCoverStyle
            MeloraSettings.updateMusicPlayMode(originalMusicPlayMode)
        }
    }
}
