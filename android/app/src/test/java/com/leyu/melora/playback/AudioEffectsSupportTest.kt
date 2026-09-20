package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AudioEffectsSupportTest {
    @Test
    fun presetCurvesDescribeToneRatherThanHiddenGlobalAttenuation() {
        AudioEffects.effectPresets.forEach { preset ->
            val curve = preset.curve
            assertNotNull("${preset.id} 应有曲线", curve.takeIf { it.isNotEmpty() })
            assertTrue(curve.zipWithNext().all { (a, b) -> a.first < b.first })
            assertTrue(curve.all { it.second.isFinite() && it.second in -12f..6f })
            assertTrue("${preset.id} 应保留可感知的相对音色", curve.maxOf { it.second } > curve.minOf { it.second })
        }
    }

    @Test
    fun offHasNoFiltersOrRoomAndPresetsAreNotDuplicateCurves() {
        assertTrue(AudioEffects.preset("off").curve.isEmpty())
        assertFalse(AudioEffects.preset("off").concert)
        assertEquals(AudioEffects.effectPresets.size, AudioEffects.effectPresets.map { it.curve }.toSet().size)
    }
    @Test
    fun speechHasOneCatalogEntryAndReducesBassWithoutReverb() {
        assertEquals(AudioEffects.presets.size, AudioEffects.presets.map { it.id }.toSet().size)
        val speech = AudioEffects.preset("speech")
        val curve = speech.curve.toMap()
        assertFalse(speech.concert)
        assertTrue(curve.getValue(62f) < curve.getValue(2000f))
        assertTrue(curve.getValue(8000f) < curve.getValue(2000f))
    }

    @Test
    fun oldPresetIdsArePreservedAndUnknownSettingsHaveADefinedFallback() {
        for (id in listOf("off", "pop", "vocal", "rock", "concert", "bass", "speech")) {
            assertEquals(id, AudioEffects.restoreId(id))
        }
        assertEquals("off", AudioEffects.restoreId("broken"))
        assertEquals("vocal", AudioEffects.restoreId(null, "vocal"))
        assertEquals("speech", AudioEffects.restoreId("unknown", "speech"))
        assertEquals("off", AudioEffects.restoreId(null, "unknown"))
    }

    @Test
    fun selectedPresetIsNotPresentedAsActiveUntilApplicationSucceeds() {
        for (phase in listOf(AudioEffects.Phase.Idle, AudioEffects.Phase.Applying, AudioEffects.Phase.Failed)) {
            assertFalse(AudioEffects.State("speech", phase).active)
        }
        assertTrue(AudioEffects.State("speech", AudioEffects.Phase.Applied).active)
        assertFalse(AudioEffects.State("off", AudioEffects.Phase.Applied).active)
        assertTrue(AudioEffects.statusText("speech", AudioEffects.State("pop", AudioEffects.Phase.Applied)).contains("等待"))
        assertTrue(AudioEffects.statusText("speech", AudioEffects.State("speech", AudioEffects.Phase.Failed)).contains("重试"))
    }

    @Test
    fun visibleCardsAreSixEffectsWhileOffRemainsAValidStoredPreset() {
        assertEquals(listOf("pop", "vocal", "speech", "rock", "concert", "bass"), AudioEffects.effectPresets.map { it.id })
        assertEquals(7, AudioEffects.presets.size)
        assertEquals("off", AudioEffects.restoreId("off"))
        assertFalse(AudioEffects.effectPresets.any { it.id == AudioEffects.OFF })
    }

    @Test
    fun cardsToggleImmediatelyAndSelectingAnotherEffectRemainsExclusive() {
        val original = MeloraSettings.audioEffectPreset.value
        try {
            MeloraSettings.updateAudioEffectPreset("off")
            for (effect in AudioEffects.effectPresets) {
                repeat(3) {
                    MeloraSettings.toggleAudioEffectPreset(effect.id)
                    assertEquals(effect.id, MeloraSettings.audioEffectPreset.value)
                    MeloraSettings.toggleAudioEffectPreset(effect.id)
                    assertEquals("off", MeloraSettings.audioEffectPreset.value)
                }
            }
            MeloraSettings.toggleAudioEffectPreset("vocal")
            MeloraSettings.toggleAudioEffectPreset("speech")
            assertEquals("speech", MeloraSettings.audioEffectPreset.value)
            MeloraSettings.toggleAudioEffectPreset("speech")
            val offBackup = BackupSettings.collect()
            assertEquals("off", offBackup.getString("audioEffectPreset"))
            MeloraSettings.toggleAudioEffectPreset("bass")
            BackupSettings.apply(BackupSettings.prepare(offBackup, emptySet(), emptySet()).first)
            assertEquals("off", MeloraSettings.audioEffectPreset.value)
            MeloraSettings.toggleAudioEffectPreset("unknown")
            assertEquals("off", MeloraSettings.audioEffectPreset.value)
        } finally { MeloraSettings.updateAudioEffectPreset(original) }
    }

    @Test
    fun presetBackupRoundTripsAndOldOrInvalidBackupsKeepTheCurrentChoice() {
        val original = MeloraSettings.audioEffectPreset.value
        try {
            MeloraSettings.updateAudioEffectPreset("speech")
            val backup = BackupSettings.collect()
            assertEquals("speech", backup.getString("audioEffectPreset"))
            MeloraSettings.updateAudioEffectPreset("pop")
            BackupSettings.apply(BackupSettings.prepare(backup, emptySet(), emptySet()).first)
            assertEquals("speech", MeloraSettings.audioEffectPreset.value)
            BackupSettings.apply(BackupSettings.prepare(backup, emptySet(), emptySet()).first)
            BackupSettings.apply(BackupSettings.prepare(JSONObject(), emptySet(), emptySet()).first)
            BackupSettings.apply(BackupSettings.prepare(JSONObject().put("audioEffectPreset", "invalid"), emptySet(), emptySet()).first)
            BackupSettings.apply(BackupSettings.prepare(JSONObject().put("audioEffectPreset", 17), emptySet(), emptySet()).first)
            assertEquals("speech", MeloraSettings.audioEffectPreset.value)
            MeloraSettings.updateAudioEffectPreset("invalid")
            assertEquals("off", MeloraSettings.audioEffectPreset.value)
        } finally { MeloraSettings.updateAudioEffectPreset(original) }
    }

    @Test
    fun processorReportsAreTheOnlySourceOfAppliedStateAndResetClearsThem() {
        AudioEffects.report(AudioEffects.State("speech", AudioEffects.Phase.Applied))
        assertTrue(AudioEffects.state.value.active)
        AudioEffects.resetState()
        assertFalse(AudioEffects.state.value.active)
        assertEquals(AudioEffects.Phase.Idle, AudioEffects.state.value.phase)
    }
}
