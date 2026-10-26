package org.gameyfin.app.core.download.torrent

import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SnapshotWorkerSupervisorTest {
    private class BrokenProcess(private val forceWorks: Boolean, private val interruptWait: Boolean = false) : Process() {
        var running = true; var destroys = 0; var forces = 0
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { throw IOException("synthetic pipe failure") }
            override fun close() { throw IOException("synthetic close failure") }
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = error("Unbounded wait must not be used")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (interruptWait) throw InterruptedException("synthetic interruption")
            return !running
        }
        override fun exitValue(): Int { check(!running); return 1 }
        override fun isAlive() = running
        override fun destroy() { destroys++ }
        override fun destroyForcibly(): Process { forces++; if (forceWorks) running = false; return this }
    }
    @Test fun `broken pipe and close cannot skip owned process escalation`() {
        val process = BrokenProcess(true); var cleanups = 0
        val connection = SnapshotWorkerConnection(process, Duration.ofMillis(50)) { cleanups++ }
        assertFails { connection.exchange(byteArrayOf(1)) }
        assertTrue(connection.finish())
        assertTrue(process.destroys > 0); assertTrue(process.forces > 0)
        assertEquals(1, cleanups)
    }
    @Test fun `interrupted waits still force termination and preserve interruption`() {
        val process = BrokenProcess(true, true)
        val connection = SnapshotWorkerConnection(process, Duration.ofMillis(50)) {}
        try {
            assertTrue(connection.finish())
            assertTrue(process.forces > 0)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun `unproven exit returns false and does not clean owned packaging material`() {
        val process = BrokenProcess(false); var cleanups = 0
        val connection = SnapshotWorkerConnection(process, Duration.ofMillis(50)) { cleanups++ }
        assertFalse(connection.finish())
        assertTrue(process.forces > 0)
        assertEquals(0, cleanups)
        // Complete the exact fake process so the test does not retain its executor.
        process.running = false
        assertTrue(connection.finish())
    }
}
