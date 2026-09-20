package com.leyu.melora.playback.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CoverLoaderTest {
    @Test
    fun retriesTransientNullAndReturnsSecondResult() = runBlocking {
        val attempts = mutableListOf<Int>()

        val result = retryNullable(maxAttempts = 2, retryDelayMs = 0) { attempt ->
            attempts += attempt
            if (attempt == 2) "https://example.com/cover.jpg" else null
        }

        assertEquals("https://example.com/cover.jpg", result)
        assertEquals(listOf(1, 2), attempts)
    }

    @Test
    fun stopsAfterFirstSuccessfulResult() = runBlocking {
        var calls = 0

        val result = retryNullable(maxAttempts = 3, retryDelayMs = 0) {
            calls++
            "cover"
        }

        assertEquals("cover", result)
        assertEquals(1, calls)
    }

    @Test
    fun returnsNullAfterBoundedFailures() = runBlocking {
        var calls = 0

        val result = retryNullable<String>(maxAttempts = 2, retryDelayMs = 0) {
            calls++
            error("temporary failure")
        }

        assertNull(result)
        assertEquals(2, calls)
    }

    @Test
    fun propagatesCancellationWithoutRetrying() {
        var calls = 0

        assertThrows(CancellationException::class.java) {
            runBlocking {
                retryNullable<String>(maxAttempts = 2, retryDelayMs = 0) {
                    calls++
                    throw CancellationException("cancelled")
                }
            }
        }
        assertEquals(1, calls)
    }
}
