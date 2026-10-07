package org.gameyfin.app.libraries.scan

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

class BoundedScanTasksTest {
    @Test
    fun `large scans construct at most one window before completed work is released`() {
        val created = AtomicInteger()
        val completed = AtomicInteger()
        val peakPending = AtomicInteger()
        val tasks = (0 until 10000).asSequence().map { index ->
            val pending = created.incrementAndGet() - completed.get()
            peakPending.accumulateAndGet(pending, ::maxOf)
            Callable {
                completed.incrementAndGet()
                index
            }
        }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeBounded(tasks, 4)
            assertEquals((0 until 10000).toList(), results)
            assertEquals(10000, completed.get())
            assertTrue(peakPending.get() <= 4, "Constructed ${peakPending.get()} pending tasks")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a failed window propagates and prevents subsequent work from being submitted`() {
        val created = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (0 until 100).asSequence().map { index ->
                created.incrementAndGet()
                Callable { if (index == 0) error("database unavailable") else index }
            }
            assertThrows(ExecutionException::class.java) { executor.invokeBounded(tasks, 2) }
            assertEquals(2, created.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
