package org.gameyfin.app.core.download.torrent

import io.mockk.*
import org.gameyfin.snapshot.SnapshotManifest
import org.gameyfin.snapshot.SnapshotMember
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class OwnedSnapshotLeaseTest {
    private val id = UUID.randomUUID()
    private val token = UUID.randomUUID()
    private val manifest = SnapshotManifest(id, UUID.randomUUID(), "a".repeat(64), "root", "directory",
        listOf(SnapshotMember("base", 4, "b".repeat(64), "copy", "source")))

    @Test fun `database outage retains durable lease but exact release can retry after proven child exit`() {
        val worker = mockk<SnapshotWorkerConnection>(); val registry = mockk<OwnedSnapshotRegistry>()
        var releasedSlots = 0; var attempts = 0
        every { worker.finish() } returns true
        every { registry.release(id, token) } answers {
            attempts++; if (attempts == 1) error("synthetic database outage")
        }
        val lease = OwnedSnapshotLease(id, token, manifest, worker, registry) { releasedSlots++ }
        assertFails { lease.close() }
        assertFails { lease.openMember("base") }
        lease.close(); lease.close()
        assertEquals(1, releasedSlots)
        verify(exactly = 1) { worker.finish() }
        verify(exactly = 2) { registry.release(id, token) }
    }
    @Test fun `unknown child exit cannot release durable lease or worker capacity`() {
        val worker = mockk<SnapshotWorkerConnection>(); val registry = mockk<OwnedSnapshotRegistry>()
        var releasedSlots = 0
        every { worker.finish() } returnsMany listOf(false, true)
        every { registry.uncertain(id) } throws IllegalStateException("synthetic database outage")
        every { registry.release(id, token) } just Runs
        val lease = OwnedSnapshotLease(id, token, manifest, worker, registry) { releasedSlots++ }
        assertFails { lease.close() }
        assertEquals(0, releasedSlots)
        verify(exactly = 0) { registry.release(any(), any()) }
        lease.close()
        assertEquals(1, releasedSlots)
        verify(exactly = 1) { registry.release(id, token) }
    }
    @Test fun `member streams cannot escape manifest or outlive the owning lease`() {
        val worker = mockk<SnapshotWorkerConnection>(); val registry = mockk<OwnedSnapshotRegistry>()
        every { worker.finish() } returns true
        every { registry.release(id, token) } just Runs
        every { worker.exchange(any()) } returns "test".toByteArray()
        val lease = OwnedSnapshotLease(id, token, manifest, worker, registry) {}
        assertFails { lease.openMember("../outside") }
        val stream = lease.openMember("base")
        assertContentEquals("test".toByteArray(), stream.readNBytes(4))
        assertEquals(-1, stream.read())
        val remaining = lease.openMember("base")
        lease.close()
        assertFails { remaining.read() }
    }
    @Test fun `required exit fails closed even when uncertainty persistence succeeds and can retry exact worker`() {
        val worker = mockk<SnapshotWorkerConnection>(); val registry = mockk<OwnedSnapshotRegistry>()
        every { worker.finish() } returnsMany listOf(false, true)
        every { registry.uncertain(id) } returns mockk()
        every { registry.release(id, token) } just Runs
        var releasedSlots = 0
        val lease = OwnedSnapshotLease(id, token, manifest, worker, registry) { releasedSlots++ }
        assertFails { lease.closeAndRequireExit() }
        assertEquals(0, releasedSlots)
        verify(exactly = 0) { registry.release(any(), any()) }
        lease.closeAndRequireExit()
        assertEquals(1, releasedSlots)
        verify(exactly = 1) { registry.release(id, token) }
    }
    @Test fun `source proof results preserve manifest binding and unknown failures are not mismatches`() {
        val worker = mockk<SnapshotWorkerConnection>(); val registry = mockk<OwnedSnapshotRegistry>()
        val lease = OwnedSnapshotLease(id, token, manifest, worker, registry) {}
        val request = org.gameyfin.snapshot.SnapshotCapture(id, manifest.operation, "/source", "/cache", manifest.cacheIdentity,
            listOf(org.gameyfin.snapshot.SnapshotSelection("base.bin", "base")), 1024, 8, 0)
        for (result in org.gameyfin.snapshot.SnapshotSourceVerification.entries) {
            val proof = org.gameyfin.snapshot.SnapshotSourceProof(result, org.gameyfin.snapshot.SnapshotProtocol.digest(manifest.bytes()), "c".repeat(64))
            every { worker.exchange(any()) } returns proof.bytes()
            assertEquals(proof, lease.verifyCurrentSources(request))
        }
        every { worker.exchange(any()) } throws IllegalStateException("synthetic unknown I/O")
        assertFailsWith<IllegalStateException> { lease.verifyCurrentSources(request) }
        verify(exactly = 0) { registry.uncertain(any()) }
        every { worker.exchange(any()) } returns org.gameyfin.snapshot.SnapshotSourceProof(
            org.gameyfin.snapshot.SnapshotSourceVerification.MATCH, "d".repeat(64), "c".repeat(64)).bytes()
        assertFails { lease.verifyCurrentSources(request) }
    }
}
