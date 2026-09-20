package com.leyu.melora.ui.common

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableResultTest {
    @Test(expected = CancellationException::class)
    fun `cancellation is never converted into failure`() {
        runCatchingCancellable<Unit> { throw CancellationException("cancel") }
    }

    @Test
    fun `ordinary failures remain inspectable results`() {
        val result = runCatchingCancellable<Int> { error("broken") }
        assertTrue(result.isFailure)
        assertEquals("broken", result.exceptionOrNull()?.message)
    }
}
