package com.leyu.melora.ui.player

import com.leyu.melora.playback.AudioSpecification
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerAudioSpecificationTest {
    @Test
    fun showsMeasuredFieldsInPlaybackOrder() {
        val specification = AudioSpecification("audio/flac", 192_000, 4_812_000, bitDepth = 24)

        assertEquals("24-bit · 192 kHz · FLAC · 4,812 kbps", specification.playerSpecificationLabel())
    }

    @Test
    fun skipsUnknownFieldsWithoutAddingEmptySeparators() {
        val mp3 = AudioSpecification("audio/mpeg", 44_100, 128_000)
        val lossless = AudioSpecification("audio/flac", -1, -1)
        val unknown = AudioSpecification(null, -1, -1)

        assertEquals("44.1 kHz · MP3 · 128 kbps", mp3.playerSpecificationLabel())
        assertEquals("FLAC", lossless.playerSpecificationLabel())
        assertEquals("", unknown.playerSpecificationLabel())
    }
}
