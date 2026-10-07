package org.gameyfin.app.requests

import io.mockk.every
import io.mockk.mockk
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.config.ConfigProperties
import org.gameyfin.app.config.ConfigService
import org.gameyfin.app.core.Role
import org.junit.jupiter.api.Test
import kotlin.test.*

class AcquisitionScopePolicyTest {
    private val config = mockk<ConfigService>()
    private val policy = AcquisitionScopePolicy(config)

    @Test fun `approved configuration never implies an active provider or indexer`() {
        every { config.get(ConfigProperties.Requests.Acquisition.ApprovedIndexerIds) } returns arrayOf("12", "2", "12")
        val summary = policy.summary()
        assertEquals(listOf("2", "12"), summary.approvedIndexerIds)
        assertTrue(summary.activeIndexerIds.isEmpty())
        assertFalse(summary.providerEnabled)
        assertEquals("gameyfin-acquisition", summary.category)
    }

    @Test fun `empty scope denies every indexer`() {
        every { config.get(ConfigProperties.Requests.Acquisition.ApprovedIndexerIds) } returns emptyArray()
        assertFailsWith<IllegalArgumentException> { policy.requireApprovedIndexer("1") }
    }

    @Test fun `only exact approved numeric IDs are permitted`() {
        every { config.get(ConfigProperties.Requests.Acquisition.ApprovedIndexerIds) } returns arrayOf("2", "12")
        policy.requireApprovedIndexer("2")
        assertFailsWith<IllegalArgumentException> { policy.requireApprovedIndexer("1") }
        assertFailsWith<IllegalArgumentException> { policy.requireApprovedIndexer("all") }
        assertFailsWith<IllegalArgumentException> { policy.requireApprovedIndexer("02") }
    }

    @Test fun `wildcards zero and oversized indexer IDs fail closed`() {
        for (id in listOf("*", "all", "0", "-1", "1,2", "999999999999999999999999")) {
            every { config.get(ConfigProperties.Requests.Acquisition.ApprovedIndexerIds) } returns arrayOf(id)
            assertFailsWith<IllegalArgumentException> { policy.summary() }
        }
    }

    @Test fun `ownership scope requires category and exact request candidate tags`() {
        val owned = policy.ownershipTags(7, 11)
        policy.requireOwnedTorrent(AcquisitionScopePolicy.CATEGORY, owned, 7, 11)
        assertFailsWith<IllegalArgumentException> { policy.requireOwnedTorrent("movies", owned, 7, 11) }
        assertFailsWith<IllegalArgumentException> { policy.requireOwnedTorrent(AcquisitionScopePolicy.CATEGORY, setOf(AcquisitionScopePolicy.MANAGED_TAG), 7, 11) }
        assertFailsWith<IllegalArgumentException> { policy.requireOwnedTorrent(AcquisitionScopePolicy.CATEGORY, owned, 8, 11) }
        assertFailsWith<IllegalArgumentException> { policy.ownershipTags(0, 11) }
    }

    @Test fun `scope visibility endpoint is restricted to administrators`() {
        assertContentEquals(arrayOf(Role.Names.ADMIN), AcquisitionScopeEndpoint::class.java.getAnnotation(RolesAllowed::class.java).value)
    }
}
