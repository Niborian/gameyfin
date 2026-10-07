package org.gameyfin.app.core.download.files

import org.springframework.stereotype.Component
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes path retirement against downloads, including lazy ZIP stream creation. */
@Component
class DownloadPathLeases {
    private val active = mutableMapOf<Path, Int>()

    @Synchronized
    fun acquire(paths: List<Path>): AutoCloseable {
        val normalized = paths.map { it.toAbsolutePath().normalize() }.distinct()
        normalized.forEach { active[it] = (active[it] ?: 0) + 1 }
        val released = AtomicBoolean(false)
        return AutoCloseable { if (released.compareAndSet(false, true)) synchronized(this) {
            normalized.forEach { path ->
                val count = requireNotNull(active[path]) - 1
                if (count == 0) active.remove(path) else active[path] = count
            }
        } }
    }

    @Synchronized
    fun <T> whenUnused(root: Path, operation: () -> T): T {
        val normalized = root.toAbsolutePath().normalize()
        require(active.keys.none { it.startsWith(normalized) || normalized.startsWith(it) }) { "Mirror is in use by a download" }
        return operation()
    }

    fun guard(input: InputStream, lease: AutoCloseable): InputStream = object : FilterInputStream(input) {
        private val released = AtomicBoolean(false)
        private fun release() { if (released.compareAndSet(false, true)) lease.close() }
        override fun read(): Int = try { super.read().also { if (it == -1) release() } } catch (error: Exception) { release(); throw error }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
            input.read(buffer, offset, length).also { if (it == -1) release() }
        } catch (error: Exception) { release(); throw error }
        override fun close() { try { super.close() } finally { release() } }
    }
}
