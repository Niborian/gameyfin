package org.gameyfin.app.libraries.scan

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class ScanWorkerLifetimeTest {
    @Test
    fun `cancelled future does not prove quiescence and queued work is cancelled`() {
        val workers = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedRan = AtomicBoolean(false)
        val running = workers.submit {
            entered.countDown()
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { }
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val queued = workers.submit { queuedRan.set(true) }
            running.cancel(true)
            assertTrue(running.isDone)
            assertFalse(drainScanWorkers(workers, 20, TimeUnit.MILLISECONDS))
            assertFalse(workers.isTerminated)
            assertFalse(queuedRan.get())
            assertTrue(queued.isCancelled)
        } finally {
            release.countDown()
            assertTrue(drainScanWorkers(workers, 2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `repeated coordinator interrupts do not release active bodies and flag is restored`() {
        val workers = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val flag = AtomicBoolean(false)
        workers.submit {
            entered.countDown()
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { }
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val coordinator = Thread {
            Thread.currentThread().interrupt()
            assertTrue(drainScanWorkers(workers, 2, TimeUnit.SECONDS))
            flag.set(Thread.currentThread().isInterrupted)
            drained.countDown()
        }
        try {
            coordinator.start()
            val drainingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (!workers.isShutdown && System.nanoTime() < drainingDeadline) Thread.sleep(1)
            assertTrue(workers.isShutdown)
            repeat(5) { coordinator.interrupt(); Thread.sleep(2) }
            assertFalse(drained.await(30, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(drained.await(2, TimeUnit.SECONDS))
            assertTrue(flag.get())
        } finally {
            release.countDown()
            coordinator.join(3000)
            workers.shutdownNow()
        }
    }
}
