package org.gameyfin.app.requests

import io.mockk.*
import org.gameyfin.app.requests.dto.RecordGameRequestCandidateDto
import org.gameyfin.app.requests.entities.GameRequest
import org.gameyfin.app.requests.entities.GameRequestCandidate
import org.gameyfin.app.requests.status.GameRequestStatus
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GameRequestCandidateServiceTest {
    private lateinit var requests: GameRequestRepository
    private lateinit var candidates: GameRequestCandidateRepository
    private lateinit var approvals: GameRequestCandidateApprovalRepository
    private lateinit var service: GameRequestCandidateService
    private val gameRequest = GameRequest(id = 1L, title = "Example", release = null, platform = Platform.PC_MICROSOFT_WINDOWS, status = GameRequestStatus.AWAITING_APPROVAL)

    @BeforeEach fun setup() {
        requests = mockk(); candidates = mockk(); approvals = mockk()
        service = GameRequestCandidateService(requests, candidates, approvals)
        mockkStatic("org.gameyfin.app.core.security.SecurityUtilsKt")
        every { org.gameyfin.app.core.security.getCurrentAuth() } returns null
    }
    @AfterEach fun cleanup() = unmockkAll()

    @Test fun `recording candidate stores bounded metadata without provider action`() {
        every { requests.findById(1L) } returns Optional.of(gameRequest)
        every { candidates.save(any()) } answers { firstArg<GameRequestCandidate>().also { it.id = 2L } }

        val recorded = service.record(1L, RecordGameRequestCandidateDto("manual review", "Example release", "reference-42"))

        assertEquals("manual review", recorded.providerLabel)
        verify(exactly = 1) { candidates.save(any()) }
        verify(exactly = 0) { approvals.save(any()) }
    }

    @Test fun `selection requires an approval record`() {
        val candidate = GameRequestCandidate(id = 2L, gameRequest = gameRequest, providerLabel = "manual", displayName = "Example", externalReference = "ref", recordedBy = "admin")
        every { candidates.findById(2L) } returns Optional.of(candidate)
        every { approvals.existsByCandidateId(2L) } returns false

        assertFailsWith<IllegalArgumentException> { service.select(2L) }
        verify(exactly = 0) { candidates.save(any()) }
    }
}
