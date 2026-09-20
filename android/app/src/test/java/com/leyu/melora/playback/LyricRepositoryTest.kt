package com.leyu.melora.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LyricRepositoryTest {
    @Test
    fun recoverableFailureReturnsNull() = runBlocking {
        assertNull(recoverableOrNull<String> { error("offline") })
    }

    @Test
    fun cancellationIsNeverConvertedToMissingLyric() {
        val error = assertThrows(CancellationException::class.java) {
            runBlocking {
                recoverableOrNull<String> { throw CancellationException("stopped") }
            }
        }

        assertEquals("stopped", error.message)
    }

    @Test
    fun parserKeepsMultipleTagsTranslationsAndOffset() {
        val lyric = "[offset:100]\n[00:01.00][00:02.000]Line"
        val translation = "[00:01.00]译文"

        val lines = LrcParser.parse(lyric, translation)

        assertEquals(listOf(900L, 1_900L), lines.map { it.timeMs })
        assertEquals("Line", lines.first().text)
        assertEquals("译文", lines.first().translation)
    }
}
