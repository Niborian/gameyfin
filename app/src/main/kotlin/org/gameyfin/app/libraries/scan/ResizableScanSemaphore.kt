package org.gameyfin.app.libraries.scan

import java.util.concurrent.Semaphore

/** Stable shared permit identity. Shrinking records permit debt until active work exits;
 * it never creates a second pool or revokes permits from already running tasks. */
internal class ResizableScanSemaphore(initialLimit: Int) : Semaphore(initialLimit, true) {
    private var limit = initialLimit

    init { require(initialLimit > 0) }

    @Synchronized
    fun resize(newLimit: Int) {
        require(newLimit > 0)
        val delta = newLimit - limit
        if (delta > 0) release(delta) else if (delta < 0) reducePermits(-delta)
        limit = newLimit
    }
}
