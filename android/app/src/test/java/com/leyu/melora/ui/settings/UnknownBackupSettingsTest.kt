package com.leyu.melora.ui.settings

import com.leyu.melora.playback.BackupSettings
import com.leyu.melora.playback.MeloraSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UnknownBackupSettingsTest {
    @Test
    fun unknownFieldsAreIgnoredWhileKnownSettingsStillRestore() {
        val originalShowExit = MeloraSettings.showExitButton.value
        try {
            val legacy = JSONObject()
                .put("unknownObjectSetting", JSONObject())
                .put("unknownArraySetting", JSONArray())
                .put("showExitButton", false)
                .put("dataChannel", "app")
            val (prepared, notice) = BackupSettings.prepare(legacy, emptySet(), emptySet())

            assertEquals("", notice)
            assertFalse(prepared.has("unknownObjectSetting"))
            assertFalse(prepared.has("unknownArraySetting"))
            assertEquals(false, prepared.getBoolean("showExitButton"))
            assertFalse(prepared.has("dataChannel"))

            BackupSettings.apply(prepared)
            assertFalse(MeloraSettings.showExitButton.value)
            assertFalse(BackupSettings.collect().has("dataChannel"))
            assertFalse(BackupSettings.collect().has("unknownObjectSetting"))
            assertFalse(BackupSettings.collect().has("unknownArraySetting"))
        } finally {
            MeloraSettings.updateShowExit(originalShowExit)
        }
    }
}
