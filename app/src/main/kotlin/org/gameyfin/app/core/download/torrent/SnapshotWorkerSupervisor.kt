package org.gameyfin.app.core.download.torrent

import org.gameyfin.snapshot.SnapshotProtocol
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No configurable executable, shell, inherited APP_KEY/provider secrets, Spring or PF4J
 * classpath. Native access belongs solely to the embedded Kotlin worker subprocess.
 */
internal class SnapshotWorkerSupervisor(private val timeout: Duration) {
    init { require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofHours(1)) }
    fun ownerIdentity(): SnapshotProcessIdentity = SnapshotProcessIdentity.current()
    fun start(): SnapshotWorkerConnection {
        require(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "amd64") { "Snapshot acquisition unavailable on this platform" }
        val directory = Files.createTempDirectory("gameyfin-snapshot-worker-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val jar = directory.resolve("worker.jar")
        try {
            val embedded = requireNotNull(javaClass.getResourceAsStream("/snapshot-worker/gameyfin-snapshot-worker.jar")) { "Packaged snapshot worker missing" }
            embedded.use { source -> Files.newOutputStream(jar, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use(source::copyTo) }
            Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("r--------"))
            val builder = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "--illegal-native-access=deny", "-Xms16m", "-Xmx64m", "-XX:MaxDirectMemorySize=8m", "-jar", jar.toString(),
                ProcessHandle.current().pid().toString(), timeout.toMillis().toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().clear()
            return SnapshotWorkerConnection(builder.start(), timeout) {
                // Exact owned packaging material only; never snapshot/source/cache trees.
                Files.deleteIfExists(jar); Files.deleteIfExists(directory)
            }
        } catch (failure: Throwable) { Files.deleteIfExists(jar); Files.deleteIfExists(directory); throw failure }
    }
}

internal class SnapshotWorkerConnection(private val process: Process, private val timeout: Duration,
    private val cleanup: () -> Unit) {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private var ended = false
    fun identity(): SnapshotProcessIdentity = SnapshotProcessIdentity.of(process.toHandle())
    @Synchronized fun exchange(request: ByteArray): ByteArray = exchange(request, timeout)
    @Synchronized private fun exchange(request: ByteArray, wait: Duration): ByteArray {
        check(!ended && process.isAlive) { "Snapshot worker unavailable" }
        val result = executor.submit<ByteArray> {
            SnapshotProtocol.frame(process.outputStream, request)
            val response = SnapshotProtocol.frame(process.inputStream)
            require(response.first() == 0.toByte()) { "Snapshot worker refused operation" }
            response.copyOfRange(1, response.size)
        }
        return try { result.get(wait.toMillis(), TimeUnit.MILLISECONDS) }
        catch (failure: Throwable) { result.cancel(true); terminate(); throw IllegalStateException("Snapshot operation uncertain", failure) }
    }
    /** true only after OS process exit is observed. Unknown exit never releases a lease. */
    @Synchronized fun finish(): Boolean {
        if (!ended && alive()) runCatching {
            exchange(SnapshotProtocol.encode { writeInt(SnapshotProtocol.VERSION); writeInt(SnapshotProtocol.CLOSE) }, minOf(timeout, Duration.ofSeconds(2)))
        }
        return terminate()
    }
    @Synchronized private fun terminate(): Boolean {
        if (ended) return !alive()
        // Closing a broken pipe or an interrupted caller must not skip termination.
        // Do not close the writer before signalling the process: a pipe close can block
        // behind an outstanding write. Unknown exit never releases permits/leases.
        var interrupted = Thread.interrupted()
        fun await(seconds: Long) {
            try { process.waitFor(seconds, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted = true; Thread.interrupted() }
            catch (_: Exception) { /* still attempt the next escalation */ }
        }
        try {
            if (alive()) { runCatching { process.destroy() }; await(1) }
            if (alive()) { runCatching { process.destroyForcibly() }; await(2) }
            val exited = !alive()
            if (exited) {
                ended = true
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                executor.shutdownNow()
                runCatching(cleanup)
            }
            return exited
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    private fun alive(): Boolean = runCatching { process.isAlive }.getOrDefault(true)
}
