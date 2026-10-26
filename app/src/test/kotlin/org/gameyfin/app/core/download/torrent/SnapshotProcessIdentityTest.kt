package org.gameyfin.app.core.download.torrent

import org.junit.jupiter.api.Test
import kotlin.test.*

class SnapshotProcessIdentityTest {
    private val recorded = SnapshotProcessIdentity("a".repeat(64), 1234, 1000)
    private fun probe(scope: String?, observation: SnapshotProcessObservation) = object : SnapshotProcessProbe {
        override fun scope() = scope
        override fun observe(pid: Long): SnapshotProcessObservation { assertEquals(recorded.pid, pid); return observation }
    }
    @Test fun `local PID absence is not proof in foreign host boot or namespace`() {
        assertFalse(probe("b".repeat(64), SnapshotProcessObservation.Absent).exitProved(recorded))
        assertFalse(probe(null, SnapshotProcessObservation.Absent).exitProved(recorded))
    }
    @Test fun `same scoped process stays owned while missing start information stays uncertain`() {
        assertFalse(probe(recorded.scope, SnapshotProcessObservation.Present(1000)).exitProved(recorded))
        assertFalse(probe(recorded.scope, SnapshotProcessObservation.Unknown).exitProved(recorded))
    }
    @Test fun `scoped absence or different process start proves only original process exit`() {
        assertTrue(probe(recorded.scope, SnapshotProcessObservation.Absent).exitProved(recorded))
        assertTrue(probe(recorded.scope, SnapshotProcessObservation.Present(2000)).exitProved(recorded))
    }
}
