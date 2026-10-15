package com.leyu.melora.ui.settings

import com.leyu.melora.playback.BackupSettings
import com.leyu.melora.playback.MeloraSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioCacheSizeSettingTest {
    @Test fun nineteenStopsCoverOneToTenGigabytesInHalfGigabyteSteps() {
        for (step in 0..18) {
            val mb = 1024 + 512 * step
            assertEquals(mb, audioCacheMbAtStep(step))
            assertEquals(step, audioCacheStep(mb))
        }
    }

    @Test fun boundsAndLegacyValuesSnapOnlyForSliderPresentation() {
        assertEquals(0, audioCacheStep(Int.MIN_VALUE))
        assertEquals(18, audioCacheStep(Int.MAX_VALUE))
        assertEquals(1024, audioCacheMbAtStep(-1))
        assertEquals(10240, audioCacheMbAtStep(19))
        assertEquals(0, audioCacheStep(1279))
        assertEquals(1, audioCacheStep(1280))
    }

    @Test fun displayedCapacityKeepsOriginalValueUntilUserMovesSlider() {
        assertEquals("1", audioCacheSizeGb(1024))
        assertEquals("1.5", audioCacheSizeGb(1536))
        assertEquals("10", audioCacheSizeGb(10240))
        assertEquals("0.25", audioCacheSizeGb(256))
    }

    @Test fun saveAndBackupKeepTheExistingMbContract() {
        val before = MeloraSettings.maxCacheMb.value
        try {
            MeloraSettings.updateMaxCacheMb(audioCacheMbAtStep(15))
            assertEquals(8704, MeloraSettings.maxCacheMb.value)
            assertEquals(8704, BackupSettings.collect().getInt("maxCacheMb"))
        } finally { MeloraSettings.updateMaxCacheMb(before) }
    }
}
