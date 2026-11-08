package com.leyu.melora.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDataDiagnosticsTest {
    @Test fun resourceIdentityIsStableWithoutExposingSignedUrl() {
        val url = "https://cdn.example/audio.mp3?token=private-token"
        val id = playbackDiagnosticId(url)
        assertEquals(id, playbackDiagnosticId(url))
        assertTrue(id.matches(Regex("[0-9a-f]{16}")))
        assertNotEquals(id, playbackDiagnosticId("https://cdn.example/other.mp3?token=private-token"))
    }

    @Test fun absentIdentityIsExplicitRatherThanAnEmptyHash() {
        assertEquals("unknown", playbackDiagnosticId(null))
        assertNotEquals("unknown", playbackDiagnosticId(""))
    }
}
