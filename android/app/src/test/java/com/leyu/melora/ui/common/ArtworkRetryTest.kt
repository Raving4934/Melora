package com.leyu.melora.ui.common

import org.junit.Assert.*
import org.junit.Test

class ArtworkRetryTest {
    @Test
    fun localArtworkSchemesAreNotReplacedByPlaceholders() {
        listOf("file:/cover.jpg", "file:///cache/cover.jpg", "content://media/cover/1",
            "android.resource://com.leyu.melora/drawable/cover", "https://example.test/a.jpg",
            "HTTP://example.test/a.jpg").forEach { uri -> assertTrue(uri, isLoadableArtworkUri(uri)) }
    }

    @Test
    fun unknownArtworkSchemesStillUsePlaceholders() {
        listOf("", "/cache/cover.jpg", "ftp://example.test/a.jpg", "data:image/png;base64,xyz")
            .forEach { uri -> assertFalse(uri, isLoadableArtworkUri(uri)) }
    }

    @Test
    fun transientImageFailuresHaveOnlyTwoDelayedRetries() {
        assertEquals(600L, artworkRetryDelayMs(0))
        assertEquals(1800L, artworkRetryDelayMs(1))
        assertNull(artworkRetryDelayMs(2))
        assertNull(artworkRetryDelayMs(Int.MAX_VALUE))
        assertNull(artworkRetryDelayMs(-1))
    }

    @Test
    fun localArtworkUsesPlaybackCoverUntilIndexedCoverArrives() {
        assertEquals("https://cover/current.jpg", preferredLocalArtwork(null, "https://cover/current.jpg"))
        assertEquals("file:///cache/local.jpg", preferredLocalArtwork("file:///cache/local.jpg", "https://cover/current.jpg"))
        assertNull(preferredLocalArtwork("", ""))
    }
}
