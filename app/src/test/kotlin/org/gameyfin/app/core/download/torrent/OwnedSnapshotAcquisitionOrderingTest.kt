package org.gameyfin.app.core.download.torrent

import io.mockk.*
import org.gameyfin.app.core.download.files.DownloadPathLeases
import org.gameyfin.app.core.download.files.DownloadSelectionResolver
import org.gameyfin.app.games.entities.Game
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantContent
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
    @Test fun `torrent canonical selection ignores raw row order while retaining grouped exact paths`() {
        val resolver = mockk<DownloadSelectionResolver>()
        val canonical = OwnedSnapshotAcquisitionService(resolver, mockk(), registry, mockk(),
            OwnedSnapshotSettings(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize(), 1024, 8, 0))
        val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("synthetic-only")
        val game = mockk<Game>(); val variant = mockk<GameVariant>()
        every { game.id } returns 42
        every { variant.id } returns 84
        every { variant.path } returns root.resolve("game.part01.rar").toString()
        val base = VariantContent(id = 1, variant = variant, name = "Base", path = root.resolve("game.part01.rar").toString(),
            paths = mutableListOf(root.resolve("game.part02.rar").toString(), root.resolve("game.part01.rar").toString()), required = true)
        val optional = VariantContent(id = 2, variant = variant, name = "Optional", path = root.resolve("optional.bin").toString())
        every { resolver.resolveSelection(game, 84, any()) } returnsMany listOf(variant to listOf(optional, base), variant to listOf(base, optional))
        val first = canonical.resolveCanonicalSelection(game, 84, listOf(2, 1))
        val second = canonical.resolveCanonicalSelection(game, 84, listOf(1, 2))
        assertEquals(first, second)
        every { resolver.resolveMembership(game, 84, any()) } returns (variant to listOf(optional, base))
        assertEquals(first, canonical.resolveCanonicalMembership(game, 84, listOf(2, 1)))
        verify(exactly = 2) { resolver.resolveSelection(game, 84, any()) }
        verify(exactly = 1) { resolver.resolveMembership(game, 84, any()) }
        assertEquals(listOf(1L, 2L), first.contentIds)
        assertEquals(listOf("content-1/game.part01.rar", "content-1/game.part02.rar", "content-2/optional.bin"), first.selections.map { it.generatedRelative })
        assertEquals(2, base.paths.size, "Torrent canonicalization must not reorder/mutate ZIP source rows")
        assertTrue(base.paths.first().endsWith("game.part02.rar"))
    }
}
