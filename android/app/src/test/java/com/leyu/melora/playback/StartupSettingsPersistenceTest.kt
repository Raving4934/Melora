package com.leyu.melora.playback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSettingsPersistenceTest {
    @Test
    fun defaultsEnableLastPageRestore() {
        assertTrue(MeloraSettings.restoreLastPage.value)
        assertEquals("basic.restoreLastPage", MeloraSettings.KEY_RESTORE_LAST_PAGE)
    }

    @Test
    fun updatingTheSwitchDoesNotChangeTheCurrentOrRememberedTab() = withStartupSettings {
        MeloraSettings.updateLastTab(3)
        MeloraSettings.updateRestoreLastPage(false)
        assertFalse(MeloraSettings.restoreLastPage.value)
        assertEquals(3, MeloraSettings.lastTab.value)
        // 关闭时仍记录正常导航，重建与再次开启恢复都可使用当前页面。
        MeloraSettings.updateLastTab(4)
        MeloraSettings.updateRestoreLastPage(true)
        assertTrue(MeloraSettings.restoreLastPage.value)
        assertEquals(4, MeloraSettings.lastTab.value)
    }

    @Test
    fun exportIncludesANativeBooleanForEitherSwitchValue() = withStartupSettings {
        for (enabled in listOf(false, true)) {
            MeloraSettings.updateRestoreLastPage(enabled)
            val exported = BackupSettings.collect()
            assertTrue(exported.get("restoreLastPage") is Boolean)
            assertEquals(enabled, exported.getBoolean("restoreLastPage"))
        }
    }

    @Test
    fun exportedFlagAndLastTabRoundTripTogether() = withStartupSettings {
        for (enabled in listOf(false, true)) {
            MeloraSettings.updateRestoreLastPage(enabled)
            MeloraSettings.updateLastTab(7)
            val exported = BackupSettings.collect()
            val startupFields = JSONObject()
                .put("restoreLastPage", exported.get("restoreLastPage"))
                .put("lastTab", exported.get("lastTab"))
            val backup = JSONObject(startupFields.toString())
            MeloraSettings.updateRestoreLastPage(!enabled)
            MeloraSettings.updateLastTab(5)

            val (prepared, notice) = BackupSettings.prepare(backup, emptySet(), emptySet())
            assertEquals("", notice)
            BackupSettings.apply(prepared)

            assertEquals(enabled, MeloraSettings.restoreLastPage.value)
            assertEquals(7, MeloraSettings.lastTab.value)
        }
    }

    @Test
    fun oldOrPartialBackupsWithoutTheSwitchKeepItsCurrentValue() = withStartupSettings {
        for (enabled in listOf(false, true)) {
            MeloraSettings.updateRestoreLastPage(enabled)
            for (legacy in listOf(JSONObject(), JSONObject().put("lastTab", 2))) {
                val prepared = BackupSettings.prepare(legacy, emptySet(), emptySet()).first
                assertFalse(prepared.has("restoreLastPage"))
                BackupSettings.apply(prepared)
                assertEquals(enabled, MeloraSettings.restoreLastPage.value)
            }
            assertEquals(2, MeloraSettings.lastTab.value)
        }
    }

    @Test
    fun legacyStringBooleansUseTheExistingBackupDecoder() = withStartupSettings {
        for (value in listOf("true", "TRUE", "false", "FALSE")) {
            val prepared = BackupSettings.prepare(
                JSONObject().put("restoreLastPage", value), emptySet(), emptySet(),
            ).first
            assertTrue(prepared.get("restoreLastPage") is Boolean)
            BackupSettings.apply(prepared)
            assertEquals(value.equals("true", ignoreCase = true), MeloraSettings.restoreLastPage.value)
        }
    }

    @Test
    fun invalidBooleanFieldsRejectTheBackupWithoutPublishingAnySettings() = withStartupSettings {
        MeloraSettings.updateRestoreLastPage(false)
        MeloraSettings.updateLastTab(6)
        for (value in listOf("invalid", "", 0, 1, JSONObject.NULL, JSONArray(), JSONObject())) {
            val backup = JSONObject().put("restoreLastPage", value).put("lastTab", 2)
            assertThrows(RuntimeException::class.java) { BackupSettings.validate(backup) }
            assertFalse(MeloraSettings.restoreLastPage.value)
            assertEquals(6, MeloraSettings.lastTab.value)
        }
    }

    private inline fun withStartupSettings(block: () -> Unit) {
        val originalRestore = MeloraSettings.restoreLastPage.value
        val originalTab = MeloraSettings.lastTab.value
        val originalTheme = MeloraSettings.themeMode.value
        try {
            block()
        } finally {
            MeloraSettings.restoreLastPage.value = originalRestore
            MeloraSettings.lastTab.value = originalTab
            MeloraSettings.themeMode.value = originalTheme
        }
    }
}
