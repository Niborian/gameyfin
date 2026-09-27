package org.gameyfin.app.requests

import io.mockk.*
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.requests.dto.RecordGameRequestCandidateDto
import org.gameyfin.app.requests.entities.GameRequest
import org.gameyfin.app.requests.entities.GameRequestCandidate
import org.gameyfin.app.requests.entities.GameRequestStatusChange
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
    private lateinit var statusChanges: GameRequestStatusChangeRepository
    private lateinit var service: GameRequestCandidateService
    private val gameRequest = GameRequest(id = 1L, title = "Example", release = null, platform = Platform.PC_MICROSOFT_WINDOWS, status = GameRequestStatus.AWAITING_APPROVAL)

    @BeforeEach fun setup() {
        requests = mockk(); candidates = mockk(); approvals = mockk(); statusChanges = mockk()
        service = GameRequestCandidateService(requests, candidates, approvals, statusChanges)
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

    @Test fun `selecting an approved candidate queues the request with an audit record and no provider call`() {
        val candidate = GameRequestCandidate(id = 2L, gameRequest = gameRequest, providerLabel = "manual", displayName = "Example", externalReference = "ref", recordedBy = "admin")
        every { candidates.findById(2L) } returns Optional.of(candidate)
        every { approvals.existsByCandidateId(2L) } returns true
        every { candidates.findAllByGameRequestIdOrderByRecordedAtAsc(1L) } returns listOf(candidate)
        every { candidates.save(any()) } answers { firstArg<GameRequestCandidate>() }
        every { requests.save(any()) } answers { firstArg<GameRequest>() }
        every { statusChanges.save(any()) } answers { firstArg<GameRequestStatusChange>() }

        service.select(2L)

        assertEquals(GameRequestStatus.QUEUED, gameRequest.status)
        verify(exactly = 1) { requests.save(gameRequest) }
        verify(exactly = 1) { statusChanges.save(match { it.previousStatus == GameRequestStatus.AWAITING_APPROVAL && it.newStatus == GameRequestStatus.QUEUED }) }
    }

    @Test fun `selection does not queue a request outside the approval state`() {
        gameRequest.status = GameRequestStatus.PENDING
        val candidate = GameRequestCandidate(id = 2L, gameRequest = gameRequest, providerLabel = "manual", displayName = "Example", externalReference = "ref", recordedBy = "admin")
        every { candidates.findById(2L) } returns Optional.of(candidate)
        every { approvals.existsByCandidateId(2L) } returns true

        assertFailsWith<IllegalArgumentException> { service.select(2L) }

        verify(exactly = 0) { candidates.save(any()) }
        verify(exactly = 0) { requests.save(any()) }
        verify(exactly = 0) { statusChanges.save(any()) }
    }

    @Test fun `record rejects blank provider metadata before persistence`() {
        every { requests.findById(1L) } returns Optional.of(gameRequest)

        assertFailsWith<IllegalArgumentException> {
            service.record(1L, RecordGameRequestCandidateDto(" ", "Example", "reference"))
        }
        verify(exactly = 0) { candidates.save(any()) }
    }

    @Test fun `candidate endpoint is restricted to administrators`() {
        val roles = requireNotNull(GameRequestCandidateEndpoint::class.java.getAnnotation(RolesAllowed::class.java))

        assertEquals(listOf(Role.Names.ADMIN), roles.value.toList())
    }
}
