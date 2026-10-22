package top.ppparty.stronghold_client

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ParallelDownloadsTest {
    @Test fun runsFourDownloadsAtOnceAndConsumesEveryResultOnOwnerThread() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val firstWave = CountDownLatch(4)
        val consumed = ArrayList<Int>()
        val owner = Thread.currentThread()
        ParallelDownloads.run((0 until 12).toList(), {}, { fail("Unexpected cancellation") }, { item ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            try {
                if (item < 4) { firstWave.countDown(); assertTrue(firstWave.await(3, TimeUnit.SECONDS)) }
                Thread.sleep(5)
            } finally { active.decrementAndGet() }
        }, { item -> assertSame(owner, Thread.currentThread()); consumed.add(item) })
        assertEquals(4, maximum.get())
        assertEquals((0 until 12).toSet(), consumed.toSet())
    }
    @Test fun failureStopsOtherDownloadsAndDoesNotConsumeFailedContent() {
        val stopped = AtomicBoolean()
        val started = CountDownLatch(4)
        val consumed = ArrayList<Int>()
        try {
            ParallelDownloads.run((0 until 8).toList(), {}, { stopped.set(true) }, { item ->
                started.countDown(); assertTrue(started.await(3, TimeUnit.SECONDS))
                if (item == 0) error("corrupt")
                while (!stopped.get()) Thread.sleep(5)
            }, { consumed.add(it) })
            fail("Worker error was swallowed")
        } catch (error: IllegalStateException) { assertEquals("corrupt", error.message) }
        assertTrue(stopped.get()); assertTrue(consumed.isEmpty())
    }
}
