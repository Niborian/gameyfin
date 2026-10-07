package org.gameyfin.app.requests

import io.mockk.every
import io.mockk.mockk
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper
import kotlin.test.*

/** Runs only against the disposable CI pair; never discovers an operator's providers. */
@EnabledIfEnvironmentVariable(named = "GAMEYFIN_ACQUISITION_FIXTURE", matches = "true")
class AcquisitionLiveProviderTest {
    @Test fun `real isolated pair searches only approved fixture and adds stops resumes same identity`() {
        val settings = AcquisitionProviderSettings().apply {
            enabled = true; dedicatedClientAcknowledged = true
            prowlarrUrl = "http://127.0.0.1:39696"
            prowlarrApiKey = requireNotNull(System.getenv("FIXTURE_PROWLARR_KEY"))
            qbittorrentUrl = "http://127.0.0.1:39695"
            qbittorrentUsername = "fixture"
            qbittorrentPassword = requireNotNull(System.getenv("FIXTURE_QB_PASSWORD"))
            category = "fixture-acquisition"; managedTag = "fixture-managed"
            savePath = "/downloads/fixture-acquisition"
        }
        val indexer = requireNotNull(System.getenv("FIXTURE_INDEXER_ID"))
        val config = mockk<ConfigService>()
        every { config.get(ConfigProperties.Requests.Acquisition.ApprovedIndexerIds) } returns arrayOf(indexer)
        val provider = AcquisitionProvider(settings, AcquisitionScopePolicy(config, settings), JsonMapper.builder().build())
        assertEquals(listOf(indexer), provider.activeIndexers())
        assertFailsWith<IllegalArgumentException> { provider.search("999999", "fixture") }
        val candidate = provider.search(indexer, "fixture").single()
        assertEquals("a".repeat(40), candidate.hash)
        assertNull(provider.lookup(candidate.hash))
        try {
            provider.add(candidate, 7, 11)
        } catch (failure: Exception) {
            // Synthetic fixture only: no operator identity, path or secret.
            println("Synthetic owned state after add refusal: ${provider.lookup(candidate.hash)?.state}")
            throw failure
        }
        assertEquals("fixture-acquisition", provider.lookup(candidate.hash)?.category)
        settings.savePath = "/downloads/other-fixture"
        assertFailsWith<IllegalArgumentException> { provider.stop(candidate.hash, 7, 11) }
        settings.savePath = "/downloads/fixture-acquisition"
        settings.category = "unowned-fixture"
        assertFailsWith<IllegalArgumentException> { provider.stop(candidate.hash, 7, 11) }
        settings.category = "fixture-acquisition"
        provider.stop(candidate.hash, 7, 11)
        assertTrue(provider.lookup(candidate.hash)?.state in setOf("stoppedDL", "stoppedUP"))
        provider.start(candidate.hash, 7, 11)
        provider.stop(candidate.hash, 7, 11)
        assertFailsWith<AcquisitionPreflightRefusal> { provider.add(candidate, 7, 11) }
        assertEquals(setOf("fixture-managed", "fixture-managed-request-7", "fixture-managed-candidate-11"), provider.lookup(candidate.hash)?.tags)
    }
}
