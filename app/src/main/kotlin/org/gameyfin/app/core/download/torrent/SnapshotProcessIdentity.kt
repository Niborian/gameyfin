package org.gameyfin.app.core.download.torrent

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.gameyfin.snapshot.SnapshotProtocol

/** Identity is scoped to one kernel boot and PID namespace. Linux's per-boot UUID
 * distinguishes hosts without assuming Docker images contain a usable machine-id.
 * A copied database,
 * another container namespace or reboot is NOT evidence that a recorded worker exited.
 * Unknown/foreign scopes require explicit operator reconciliation, never local PID absence.
 */
internal data class SnapshotProcessIdentity(val scope: String, val pid: Long, val startedMillis: Long) {
    init { require(scope.matches(Regex("[0-9a-f]{64}")) && pid > 0 && startedMillis >= 0) }
    companion object {
        fun current(): SnapshotProcessIdentity = of(ProcessHandle.current())
        fun of(process: ProcessHandle): SnapshotProcessIdentity = SnapshotProcessIdentity(scope(), process.pid(),
            process.info().startInstant().orElseThrow { IllegalStateException("Process start identity unavailable") }.toEpochMilli())
        fun scope(): String {
            require(System.getProperty("os.name") == "Linux")
            val boot = UUID.fromString(Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim())
            require(boot != UUID(0, 0))
            val namespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString()
            require(namespace.matches(Regex("pid:\\[[0-9]+]"))) { "PID namespace identity unavailable" }
            return SnapshotProtocol.digest("linux-kernel-boot-pid-scope-v1\n$boot\n$namespace".toByteArray())
        }
    }
}

internal sealed interface SnapshotProcessObservation {
    data object Absent : SnapshotProcessObservation
    data object Unknown : SnapshotProcessObservation
    data class Present(val startedMillis: Long) : SnapshotProcessObservation
}

internal interface SnapshotProcessProbe {
    fun scope(): String?
    fun observe(pid: Long): SnapshotProcessObservation
    fun exitProved(identity: SnapshotProcessIdentity): Boolean {
        if (scope() != identity.scope) return false
        return when (val process = observe(identity.pid)) {
            SnapshotProcessObservation.Absent -> true
            SnapshotProcessObservation.Unknown -> false
            is SnapshotProcessObservation.Present -> process.startedMillis != identity.startedMillis
        }
    }
    companion object {
        val SYSTEM = object : SnapshotProcessProbe {
            override fun scope(): String? = runCatching { SnapshotProcessIdentity.scope() }.getOrNull()
            override fun observe(pid: Long): SnapshotProcessObservation = runCatching {
                val handle = ProcessHandle.of(pid).orElse(null) ?: return@runCatching SnapshotProcessObservation.Absent
                if (!handle.isAlive) SnapshotProcessObservation.Absent
                else handle.info().startInstant().map<SnapshotProcessObservation> { SnapshotProcessObservation.Present(it.toEpochMilli()) }
                    .orElse(SnapshotProcessObservation.Unknown)
            }.getOrDefault(SnapshotProcessObservation.Unknown)
        }
    }
}
