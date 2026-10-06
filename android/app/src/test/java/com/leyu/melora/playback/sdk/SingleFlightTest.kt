package com.leyu.melora.playback.sdk

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SingleFlightTest {
    @Test
    fun completedFailureAllowsRetryBeforeCompletionCleanup() =
        assertCompletedRequestAllowsRetry(Result.failure(IllegalStateException("offline")))

    @Test
    fun completedEmptyResultAllowsRetryBeforeCompletionCleanup() =
        assertCompletedRequestAllowsRetry(Result.success(emptyList()))

    private fun assertCompletedRequestAllowsRetry(previousResult: Result<List<String>>) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lock = Any()
        val flight = SingleFlight<String, List<String>>(scope, lock)
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    flight.run("song", cancelWhenUnobserved = false) {
                        calls.incrementAndGet()
                        releaseFirst.await()
                        previousResult.getOrThrow()
                    }
                }
            }
            val completedTask = requireNotNull(flight.pending("song"))
            val cleanupFinished = CompletableDeferred<Unit>()
            // Registered after SingleFlight's handler, so this proves old cleanup ran.
            completedTask.invokeOnCompletion { cleanupFinished.complete(Unit) }
            val retry = synchronized(lock) {
                releaseFirst.complete(Unit)
                // Hold the registry lock so completion cleanup cannot run. The task
                // can still become terminal, exposing the immediate-retry window.
                awaitTerminalState(completedTask)
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        flight.run("song", cancelWhenUnobserved = false) {
                            calls.incrementAndGet()
                            releaseRetry.await()
                            listOf("recovered")
                        }
                    }
                }
            }
            val retryTask = requireNotNull(flight.pending("song"))
            val firstResult = withTimeout(5_000) { first.await() }
            assertEquals(previousResult.getOrNull(), firstResult.getOrNull())
            assertEquals(previousResult.exceptionOrNull()?.message, firstResult.exceptionOrNull()?.message)
            assertNotSame("retry must not reuse the terminal task", completedTask, retryTask)
            withTimeout(5_000) { cleanupFinished.await() }
            assertSame("old completion cleanup must preserve the replacement", retryTask, flight.pending("song"))
            val follower = async(start = CoroutineStart.UNDISPATCHED) {
                flight.run("song", cancelWhenUnobserved = false) {
                    error("the running retry should still be shared")
                }
            }
            releaseRetry.complete(Unit)
            assertEquals(listOf("recovered"), withTimeout(5_000) { retry.await() }.getOrThrow())
            assertEquals(listOf("recovered"), withTimeout(5_000) { follower.await() })
            assertEquals(2, calls.get())
        } finally {
            releaseFirst.complete(Unit)
            releaseRetry.complete(Unit)
            scope.cancel()
        }
    }

    @Test fun retiredRequestCancelsWhenItsLastWaiterLeaves() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val flight = SingleFlight<String, String>(scope)
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val caller = async {
            flight.run("song") {
                try { started.complete(Unit); awaitCancellation() }
                finally { stopped.complete(Unit) }
            }
        }
        try {
            withTimeout(5_000) { started.await() }
            assertEquals("new", flight.run("song", restart = true, cancelReplaced = false) { "new" })
            assertTrue("replacement must preserve an existing waiter", caller.isActive)
            caller.cancelAndJoin()
            withTimeout(5_000) { stopped.await() }
        } finally { scope.cancel(); caller.cancelAndJoin() }
    }

    @Test fun retiredRequestsStayOwnedByGlobalAndKeyScopedInvalidation() = runBlocking {
        for (global in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val flight = SingleFlight<String, String>(scope)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val caller = async {
                flight.run("lyrics:song") {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { stopped.complete(Unit) }
                }
            }
            val other = async(start = CoroutineStart.UNDISPATCHED) {
                flight.run("other:song") { awaitCancellation() }
            }
            try {
                withTimeout(5_000) { started.await() }
                flight.run("lyrics:song", restart = true, cancelReplaced = false) { "new" }
                if (global) flight.fence() else flight.cancelWhere { it.startsWith("lyrics:") }
                withTimeout(5_000) { stopped.await(); caller.join() }
                assertTrue(caller.isCancelled)
                if (global) {
                    withTimeout(5_000) { other.join() }
                    assertTrue(other.isCancelled)
                } else assertTrue("prefix clear must preserve unrelated work", other.isActive)
            } finally { scope.cancel(); caller.cancelAndJoin(); other.cancelAndJoin() }
        }
    }

    private fun awaitTerminalState(task: Deferred<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!task.isCompleted && System.nanoTime() < deadline) Thread.yield()
        assertTrue("loader should finish even while registry cleanup is blocked", task.isCompleted)
    }
}
