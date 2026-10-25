package org.gameyfin.app.core.download.torrent

import io.mockk.*
import org.gameyfin.app.core.plugins.PluginAudience
import org.gameyfin.app.core.plugins.PluginEndpointAccess
import org.gameyfin.app.core.plugins.PluginAccountSnapshot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.nio.file.Path
import org.springframework.security.access.AccessDeniedException
import kotlin.test.*

/** No native worker or client execution: focused settings authority/secret/disable contracts. */
class OwnedTorrentSettingsTest {
    private val repository = mockk<OwnedTorrentSettingsRepository>()
    private val requests = mockk<OwnedTorrentRequestRepository>()
    private val mapper = mockk<ObjectMapper>()
    private val access = mockk<PluginEndpointAccess>()
    private val manager = mockk<PlatformTransactionManager>(relaxed = true)
    private val row = OwnedTorrentSettingsRow(username = "synthetic-client", password = "synthetic-password")
    private var configuration = OwnedTorrentConfiguration()
    private val service = OwnedTorrentSettings(repository, requests, mapper, access, manager, false)
    private val actor = mockk<PluginAudience>()
    @BeforeEach fun setup() {
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        every { repository.locked() } returns row
        every { repository.saveAndFlush(any()) } answers { firstArg() }
        every { access.captureAdministrator() } returns actor
        every { actor.account } returns PluginAccountSnapshot(7, "synthetic-admin", null, true, true)
        every { access.requireCurrent(any()) } just Runs
        every { mapper.readValue(any<String>(), OwnedTorrentConfiguration::class.java) } answers { configuration }
        every { mapper.writeValueAsString(any<OwnedTorrentConfiguration>()) } answers {
            configuration = firstArg(); "synthetic-configuration"
        }
    }
    @Test fun `default remains disabled and view never serializes stored credentials`() {
        val result = service.view()
        assertFalse(result.configuration.enabled); assertFalse(result.coreParityEnabled)
        assertTrue(result.credentialsConfigured)
        val json = ObjectMapper().writeValueAsString(result)
        assertFalse(json.contains("synthetic-client")); assertFalse(json.contains("synthetic-password"))
        verify(exactly = 0) { repository.saveAndFlush(any()) }
    }
    @Test fun `stale settings revision refuses persistence`() {
        assertThrows<IllegalArgumentException> {
            service.update(OwnedTorrentSettingsUpdate("1", configuration))
        }
        verify(exactly = 0) { repository.saveAndFlush(any()) }
    }
    @Test fun `admission may be disabled with retained ownership without rebinding credentials`() {
        configuration = valid().copy(enabled = true)
        val result = service.update(OwnedTorrentSettingsUpdate("0", configuration.copy(enabled = false)))
        assertEquals("1", result.revision); assertFalse(result.configuration.enabled)
        assertEquals("synthetic-password", row.password)
        verify(exactly = 0) { requests.countByStateNotIn(any()) }
    }
    @Test fun `historical cleanup profile is available with parity disabled and never adopts current credentials`() {
        configuration = valid()
        val request = OwnedTorrentRequestRow(profile = "fixture", profileUsername = "historical-client", profilePassword = "historical-password")
        val runtime = service.historical(request)
        assertEquals("historical-client", runtime.credentials.username)
        assertEquals("historical-password", runtime.credentials.password)
        assertThrows<IllegalArgumentException> { service.runtime() }
        verify(exactly = 0) { repository.saveAndFlush(any()) }
    }
    @Test fun `administrator revoked while waiting for settings lock cannot read or save`() {
        var revoked = false
        every { repository.locked() } answers { revoked = true; row }
        every { access.requireCurrent(any()) } answers { if (revoked) throw AccessDeniedException("Synthetic revocation") }
        assertThrows<AccessDeniedException> { service.view() }
        revoked = false
        assertThrows<AccessDeniedException> { service.update(OwnedTorrentSettingsUpdate("0", configuration)) }
        verify(exactly = 0) { repository.saveAndFlush(any()) }
    }
    @Test fun `explicit same-profile rotation preserves original secrets and audits only matching retained cleanup overrides`() {
        configuration = valid()
        val matching = OwnedTorrentRequestRow(id = UUID(0, 1), profile = "matching-profile",
            profileUsername = "original-user", profilePassword = "original-password")
        val other = OwnedTorrentRequestRow(id = UUID(0, 2), profile = "other-profile",
            profileUsername = "other-user", profilePassword = "other-password")
        val projections = listOf(matching, other).map { request -> mockk<OwnedTorrentRetainedRow>().also { projection ->
            every { projection.id } returns request.id; every { projection.state } returns "UNCERTAIN"
        } }
        every { requests.retained(any()) } returns projections
        every { requests.locked(matching.id) } returns matching
        every { requests.locked(other.id) } returns other
        every { requests.saveAndFlush(any()) } answers { firstArg() }
        every { mapper.readValue("other-profile", OwnedTorrentConfiguration::class.java) } returns configuration.copy(clientId = UUID(0, 99).toString())
        service.update(OwnedTorrentSettingsUpdate("0", configuration, "rotated-user", "rotated-password", rotateRetainedCredentials = true))
        assertEquals("matching-profile", matching.profile); assertEquals("original-password", matching.profilePassword)
        assertEquals("rotated-password", service.historical(matching).credentials.password)
        assertEquals(7L, matching.cleanupCredentialActorId); assertEquals(1L, matching.cleanupCredentialRevision)
        assertNotNull(matching.cleanupCredentialAt); assertNotNull(matching.cleanupProfileDigest)
        assertNull(other.cleanupPassword); assertEquals("other-password", other.profilePassword)
        matching.cleanupProfileDigest = "0".repeat(64)
        assertThrows<IllegalArgumentException> { service.historical(matching) }
        verify(exactly = 0) { requests.saveAndFlush(match { it.id == other.id }) }
    }
    @Test fun `retained ownership forbids clearing credentials or rotating into a different mapping`() {
        configuration = valid()
        every { requests.countByStateNotIn(any()) } returns 1
        assertThrows<IllegalArgumentException> { service.update(OwnedTorrentSettingsUpdate("0", configuration, clearCredentials = true)) }
        assertThrows<IllegalArgumentException> {
            service.update(OwnedTorrentSettingsUpdate("0", configuration.copy(clientId = UUID(0, 99).toString()),
                "new-user", "new-password", rotateRetainedCredentials = true))
        }
        verify(exactly = 0) { repository.saveAndFlush(any()) }
    }
    private fun valid() = OwnedTorrentConfiguration(clientId = UUID(0, 1).toString(), apiOrigin = "http://127.0.0.1:8080",
        hostCacheRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("owned-cache").toString(),
        qbCacheRoot = "/owned/cache", cacheIdentity = "fixture-only",
        trackers = listOf("https://tracker.invalid/announce"), readOnlyMappingConfirmed = true, privatePeerEgressConfirmed = true)
}
