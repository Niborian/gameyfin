package org.gameyfin.app.core.download.torrent

import io.mockk.*
import org.gameyfin.app.core.download.files.DownloadPathLeases
import org.gameyfin.app.core.download.files.DownloadSelectionResolver
import org.gameyfin.snapshot.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/** No filesystem/native worker is needed to prove durable host ordering. */
class OwnedSnapshotAcquisitionOrderingTest {
    private val registry = mockk<OwnedSnapshotRegistry>()
    private val service = OwnedSnapshotAcquisitionService(mockk<DownloadSelectionResolver>(), mockk<DownloadPathLeases>(),
        registry, mockk<SnapshotWorkerSupervisor>(), OwnedSnapshotSettings(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(), 1024, 8, 0))
    private val owner = SnapshotProcessIdentity("a".repeat(64), 100, 1000)
    private val worker = owner.copy(pid = 200)
    private fun request() = SnapshotCapture(UUID.randomUUID(), UUID.randomUUID(), "/source", "/cache", "root",
        listOf(SnapshotSelection("base.bin", "base")), 1024, 8, 0)
    @Test fun `committed reservation then durable caller linkage precede begin and native preparation`() {
        val events = mutableListOf<String>(); val request = request()
        every { registry.reserve(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            assertEquals("e".repeat(64), arg<String?>(9))
            events += "reserved"; mockk()
        }
        every { registry.begin(request.snapshotId) } answers { events += "begin"; mockk() }
        service.reserveAndBegin(request, 1, 2, owner, worker, { id -> assertEquals(request.snapshotId, id); events += "linked" },
            hostRequestDigest = "e".repeat(64))
        assertEquals(listOf("reserved", "linked", "begin"), events)
    }
    @Test fun `failed linkage cannot begin or prepare and known identity cannot be declared absent`() {
        val request = request()
        every { registry.reserve(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns mockk()
        assertFails { service.reserveAndBegin(request, 1, 2, owner, worker, { error("synthetic linkage outage") }) }
        verify(exactly = 0) { registry.begin(any()) }
        val failure = OwnedSnapshotAcquisitionFailure(request.snapshotId, IllegalStateException("ambiguous commit"))
        assertFalse(failure.safeToReject)
        assertFails { failure.confirmNoRetainedState() }
    }
    @Test fun `only typed prewrite refusal may prove intended identity absent after cleanup`() {
        val intended = UUID.randomUUID()
        val refused = OwnedSnapshotAcquisitionFailure(intended, SnapshotAdmissionRefused("full synthetic budget"), reservationRefused = true)
        assertTrue(refused.reservationRefused)
        assertFalse(refused.safeToReject, "Admission proof alone is not worker/source cleanup proof")
        refused.confirmNoRetainedState()
        assertTrue(refused.safeToReject)
        assertEquals(intended, refused.snapshotId, "Retain intended identity for diagnostics without fabricating a row")
        val ambiguous = OwnedSnapshotAcquisitionFailure(intended, IllegalArgumentException("not a typed capacity outcome"))
        assertFalse(ambiguous.reservationRefused)
        assertFails { ambiguous.confirmNoRetainedState() }
        // Even a same-typed exception originating in caller linkage is NOT proof
        // that this service's own already-committed reservation was refused.
        val request = request(); var admissionProof = false
        every { registry.reserve(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns mockk()
        assertFails { service.reserveAndBegin(request, 1, 2, owner, worker,
            { throw SnapshotAdmissionRefused("different nested operation") }, onAdmissionRefused = { admissionProof = true }) }
        assertFalse(admissionProof)
    }
}
