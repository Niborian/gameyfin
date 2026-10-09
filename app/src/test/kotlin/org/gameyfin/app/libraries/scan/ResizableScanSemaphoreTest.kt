package org.gameyfin.app.libraries.scan

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class ResizableScanSemaphoreTest {
    @Test fun `duplicate trigger refresh cannot inflate permits or strand old waiters`() {
        val pool = ResizableScanSemaphore(1)
        pool.acquire()
        val waiting = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread.ofVirtual().start {
            waiting.countDown()
            pool.acquire()
            try { entered.countDown() } finally { pool.release(); finished.countDown() }
        }
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            repeat(100) { pool.resize(1) }
            assertFalse(entered.await(50, TimeUnit.MILLISECONDS))
            assertEquals(0, pool.availablePermits())
            pool.release()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, pool.availablePermits())
        } finally { worker.interrupt(); worker.join(2000) }
    }

    @Test fun `overlapping batches share debt when configured limit shrinks`() {
        val pool = ResizableScanSemaphore(3)
        repeat(3) { pool.acquire() }
        pool.resize(1)
        assertEquals(-2, pool.availablePermits())
        repeat(2) { pool.release(); assertFalse(pool.tryAcquire()) }
        pool.release()
        assertTrue(pool.tryAcquire())
        assertFalse(pool.tryAcquire())
        pool.release()
        assertEquals(1, pool.availablePermits())
        pool.resize(4)
        repeat(4) { assertTrue(pool.tryAcquire()) }
        assertFalse(pool.tryAcquire())
        repeat(4) { pool.release() }
        assertEquals(4, pool.availablePermits())
    }

    @Test fun `processing error releases acquired permit and canceled waiter does not release`() {
        val pool = ResizableScanSemaphore(1)
        assertFailsWith<IllegalStateException> {
            pool.acquire()
            try { error("synthetic processing failure") } finally { pool.release() }
        }
        assertEquals(1, pool.availablePermits())
        pool.acquire()
        val interrupted = AtomicBoolean()
        val worker = Thread.ofVirtual().start {
            try { pool.acquire(); try { fail("Canceled waiter acquired") } finally { pool.release() } }
            catch (_: InterruptedException) { interrupted.set(true) }
        }
        worker.interrupt()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(interrupted.get())
        assertEquals(0, pool.availablePermits())
        pool.release()
        assertEquals(1, pool.availablePermits())
    }

    @Test fun `invalid resizing leaves current limit unchanged`() {
        val pool = ResizableScanSemaphore(2)
        assertFailsWith<IllegalArgumentException> { pool.resize(0) }
        assertFailsWith<IllegalArgumentException> { pool.resize(-1) }
        assertEquals(2, pool.availablePermits())
    }
}
