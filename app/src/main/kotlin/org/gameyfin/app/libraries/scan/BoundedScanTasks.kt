package org.gameyfin.app.libraries.scan

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future

/**
 * Construct and submit at most one window of outstanding work. A semaphore inside a task
 * limits active processing, but does not bound the queued tasks or waiting
 * virtual threads when an entire library is submitted with invokeAll.
 */
internal fun <T> ExecutorService.invokeBounded(tasks: Sequence<Callable<T>>, windowSize: Int): List<T> {
    require(windowSize > 0)
    val completion = ExecutorCompletionService<Pair<Int, T>>(this)
    val pending = mutableSetOf<Future<Pair<Int, T>>>()
    val iterator = tasks.iterator()
    val results = mutableListOf<T?>()
    fun submitNext(): Boolean {
        if (!iterator.hasNext()) return false
        val task = iterator.next()
        val index = results.size
        results.add(null)
        pending.add(completion.submit(Callable { index to task.call() }))
        return true
    }
    try {
        repeat(windowSize) { submitNext() }
        while (pending.isNotEmpty()) {
            val finished = completion.take()
            pending.remove(finished)
            val (index, result) = finished.get()
            results[index] = result
            submitNext()
        }
    } finally {
        pending.forEach { it.cancel(true) }
    }
    // Every slot is filled before returning, including legitimate nullable task results.
    @Suppress("UNCHECKED_CAST")
    return results as List<T>
}
