package com.leyu.melora.ui.settings

import com.leyu.melora.playback.BackupSettings
import com.leyu.melora.playback.MeloraSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioCacheSizeSettingTest {
    @Test fun acceptsZeroAndDecimalGigabytes() {
        assertEquals(0, audioCacheMbFromGb("0"))
        assertEquals(256, audioCacheMbFromGb(" 0.25 "))
        assertEquals(256, audioCacheMbFromGb(".25"))
        assertEquals(1536, audioCacheMbFromGb("1,5"))
        assertEquals(102, audioCacheMbFromGb("0.1"))
        assertEquals(20 * 1024, audioCacheMbFromGb("20"))
    }

    @Test fun rejectsInvalidOverflowingAndPositiveAmountsThatWouldBecomeUnlimited() {
        listOf("", "NaN", "-1", ".", "0.0001", "2e1", "2097152", "9".repeat(100)).forEach {
            assertNull(it, audioCacheMbFromGb(it))
        }
    }

    @Test fun displayedCapacityKeepsTheExistingValueAndUsesCompactDecimals() {
        assertEquals("0", audioCacheSizeGb(0))
        assertEquals("0.1", audioCacheSizeGb(102))
        assertEquals("0.25", audioCacheSizeGb(256))
        assertEquals("1.5", audioCacheSizeGb(1536))
        for (mb in (0..1024) + listOf(8704, 1_048_576, Int.MAX_VALUE)) {
            assertEquals(mb, audioCacheMbFromGb(audioCacheSizeGb(mb)))
        }
    }

    @Test fun newAndPreviousBackupsUseTheSameIntegerMegabyteField() {
        val before = MeloraSettings.maxCacheMb.value
        try {
            for (mb in listOf(0, 8704, Int.MAX_VALUE)) {
                val prepared = BackupSettings.prepare(
                    JSONObject().put("maxCacheMb", mb), emptySet(), emptySet(),
                ).first
                BackupSettings.apply(prepared)
                assertEquals(mb, MeloraSettings.maxCacheMb.value)
                assertEquals(mb, BackupSettings.collect().getInt("maxCacheMb"))
            }
        } finally {
            MeloraSettings.updateMaxCacheMb(before)
        }
    }
}
