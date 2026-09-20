package com.leyu.melora.playback.sdk

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MusicSdkPriorityTest {
    @Test
    fun highPriorityWinsWhenBothQueuesContainWork() = runBlocking {
        val high = Channel<String>(Channel.UNLIMITED)
        val low = Channel<String>(Channel.UNLIMITED)
        low.send("prefetch")
        high.send("interactive")

        assertEquals("interactive", receivePriority(high, low))
        assertEquals("prefetch", receivePriority(high, low))
    }

    @Test
    fun loadBalancerDistributesConcurrentReservationsEvenly() {
        val balancer = SlotLoadBalancer(3)
        val workers = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(300)
        try {
            repeat(300) {
                workers.execute {
                    balancer.reserve()
                    done.countDown()
                }
            }
            check(done.await(2, TimeUnit.SECONDS)) { "reservations did not finish" }

            assertEquals(listOf(100, 100, 100), balancer.snapshot())
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun releasedSlotBecomesAvailableAgain() {
        val balancer = SlotLoadBalancer(3)
        assertEquals(listOf(0, 1, 2, 0), List(4) { balancer.reserve() })

        balancer.release(2)

        assertEquals(2, balancer.reserve())
    }
}
