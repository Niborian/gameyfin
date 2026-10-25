package org.gameyfin.app.core.download.torrent

import io.mockk.*
import jakarta.servlet.http.HttpSession
import org.gameyfin.app.core.download.files.DownloadPathLeases
import org.gameyfin.app.core.download.files.DownloadSelectionResolver
import org.gameyfin.app.core.plugins.PluginAccountSnapshot
import org.gameyfin.app.core.plugins.PluginAudience
import org.gameyfin.app.core.plugins.PluginEndpointAccess
import org.gameyfin.app.games.GameService
import org.gameyfin.snapshot.SnapshotProtocol
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import tools.jackson.databind.ObjectMapper
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OwnedTorrentFacadeBoundaryTest {
    @Test fun `captured actor is checked after lookups and retained inventory is bounded scalar-only`() {
        val requests = mockk<OwnedTorrentRequestRepository>()
        val settings = mockk<OwnedTorrentSettings>()
        val journal = mockk<QbDistributionJournal>()
        val access = mockk<PluginEndpointAccess>()
        val manager = mockk<PlatformTransactionManager>(relaxed = true)
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        val actor = PluginAudience(mockk<Authentication>(), mockk<HttpSession>(), "fixture-session",
            PluginAccountSnapshot(1L, "fixture", null, true, true), true)
        every { access.capture() } returns actor
        every { access.captureAdministrator() } returns actor
        var revoked = false
        every { access.requireCurrent(actor) } answers { if (revoked) throw AccessDeniedException("Synthetic revocation") }
        val id = UUID(0L, 1L)
        val row = OwnedTorrentRequestRow(id = id, state = "READY", ownerKey =
            SnapshotProtocol.digest("1\nfixture\n\nfixture-session".toByteArray()))
        every { requests.findById(id) } answers { revoked = true; Optional.of(row) }
        val facade = OwnedTorrentFacade(settings, requests, journal, mockk<OwnedSnapshotRegistry>(),
            mockk<DownloadSelectionResolver>(), mockk<DownloadPathLeases>(), mockk<GameService>(), access,
            mockk<ObjectMapper>(), manager)
        try {
            assertThrows<AccessDeniedException> { facade.status(id) }
            revoked = false
            assertThrows<AccessDeniedException> { facade.metadata(id) }
            verify(exactly = 0) { journal.get(any()) }
            verify(exactly = 0) { settings.runtime(any()) }
            val retained = mockk<OwnedTorrentRetainedRow>()
            every { retained.id } returns id
            every { retained.state } returns "READY"
            revoked = false
            every { requests.retained(any()) } answers { revoked = true; listOf(retained) }
            assertThrows<AccessDeniedException> { facade.retained() }
            revoked = false
            every { requests.retained(any()) } returns listOf(retained)
            val result = facade.retained().single()
            assertEquals(id.toString(), result.id); assertEquals("READY", result.state); assertNull(result.downloadUrl)
            verify(exactly = 2) { requests.retained(match { it.pageSize == 64 && it.pageNumber == 0 }) }
        } finally { facade.shutdown() }
    }
}
