package org.gameyfin.app.libraries.scan

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

/**
 * Construct and submit only one window at a time. A semaphore inside a task
 * limits active processing, but does not bound the queued tasks or waiting
 * virtual threads when an entire library is submitted with invokeAll.
 */
internal fun <T> ExecutorService.invokeBounded(tasks: Sequence<Callable<T>>, windowSize: Int): List<T> {
    require(windowSize > 0)
    val results = mutableListOf<T>()
    for (window in tasks.chunked(windowSize)) {
        results.addAll(invokeAll(window).map { it.get() })
    }
    return results
}
