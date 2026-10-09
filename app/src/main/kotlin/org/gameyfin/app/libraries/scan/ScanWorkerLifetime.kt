package org.gameyfin.app.libraries.scan

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Cancel admission/work, then prove body termination, not merely cancelled Future state.
 * A false result requires the caller to retain library ownership until isTerminated.
 * A stuck provider is not automatically recoverable and is never forcibly killed.
 */
internal fun drainScanWorkers(workers: ExecutorService, timeout: Long, unit: TimeUnit): Boolean {
    var interrupted = Thread.interrupted()
    val deadline = System.nanoTime() + unit.toNanos(timeout)
    try {
        workers.shutdownNow().forEach { queued -> (queued as? Future<*>)?.cancel(false) }
        while (!workers.isTerminated) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            try {
                if (workers.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return true
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return true
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
