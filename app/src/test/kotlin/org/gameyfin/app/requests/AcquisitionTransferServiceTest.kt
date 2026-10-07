package org.gameyfin.app.requests

import io.mockk.*
import org.gameyfin.app.requests.entities.GameRequest
import org.gameyfin.app.requests.entities.GameRequestCandidate
import org.gameyfin.app.requests.status.GameRequestStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Instant
import java.util.Optional
import kotlin.test.*

class AcquisitionTransferServiceTest {
    private val provider = mockk<AcquisitionProvider>()
    private val policy = mockk<AcquisitionScopePolicy>(relaxed = true)
    private val requests = mockk<GameRequestRepository>()
    private val candidates = mockk<GameRequestCandidateRepository>()
    private val approvals = mockk<GameRequestCandidateApprovalRepository>()
    private val transfers = mockk<AcquisitionTransferRepository>()
    private val audits = mockk<AcquisitionTransferAuditRepository>(relaxed = true)
    private val manager = mockk<PlatformTransactionManager>(relaxed = true)
    private val request = mockk<GameRequest>(relaxed = true)
    private val candidate = GameRequestCandidate(id = 11, gameRequest = request, providerLabel = "fixture", displayName = "Open source fixture", externalReference = "fixture", recordedBy = "fixture", selected = true)
    private val transfer = AcquisitionTransfer(id = 1, candidate = candidate, indexerId = "2", torrentHash = "a".repeat(40), magnet = "magnet:?xt=urn:btih:${"a".repeat(40)}")
    private val service = AcquisitionTransferService(provider, policy, requests, candidates, approvals, transfers, audits, manager)

    @BeforeEach fun setup() {
        every { manager.getTransaction(any()) } returns SimpleTransactionStatus()
        every { request.id } returns 7
        every { request.status } returns GameRequestStatus.QUEUED
        every { approvals.existsByCandidateId(11) } returns true
        every { transfers.findByCandidateId(11) } returns transfer
        every { transfers.saveAndFlush(any()) } answers { firstArg() }
        every { transfers.save(any()) } answers { firstArg() }
        every { audits.save(any()) } answers { firstArg() }
        every { provider.add(any(), 7, 11) } just Runs
        every { provider.stop(any(), 7, 11) } just Runs
        every { provider.start(any(), 7, 11) } just Runs
    }

    @Test fun `submission commits intent and requires selected administrator approval before provider add`() {
        every { approvals.existsByCandidateId(11) } returns false
        assertFailsWith<IllegalArgumentException> { service.submit(11, "I own redistribution rights") }
        verify(exactly = 0) { provider.add(any(), any(), any()) }
        every { approvals.existsByCandidateId(11) } returns true
        assertEquals("ACTIVE", service.submit(11, "I own redistribution rights").state)
        verifyOrder {
            transfers.saveAndFlush(transfer)
            manager.commit(any())
            provider.add(any(), 7, 11)
        }
        verify { audits.save(match { it.operation == "SUBMIT_INTENT" && it.reason == "I own redistribution rights" }) }
        verify { audits.save(match { it.operation == "SUBMIT_CONFIRMED" }) }
        assertFailsWith<IllegalArgumentException> { service.submit(11, "deliberate duplicate") }
        verify(exactly = 1) { provider.add(any(), any(), any()) }
    }

    @Test fun `ambiguous submission becomes auditable uncertainty and never blind retries`() {
        every { provider.add(any(), 7, 11) } throws IllegalStateException("fixture timeout")
        assertFailsWith<IllegalStateException> { service.submit(11, "Authorized open source content") }
        assertEquals("UNCERTAIN", transfer.state)
        assertFailsWith<IllegalArgumentException> { service.retry(11, "Retry") }
        assertFailsWith<IllegalArgumentException> { service.submit(11, "Retry") }
        verify(exactly = 1) { provider.add(any(), any(), any()) }
        verify { audits.save(match { it.operation == "SUBMIT_UNCERTAIN" && !it.reason.contains("timeout") }) }
    }

    @Test fun `stop preserves persisted torrent and retry resumes same identity with fresh reason`() {
        transfer.state = "ACTIVE"
        assertEquals("STOPPED", service.cancel(11, "Stop this owned acquisition").state)
        assertFailsWith<IllegalArgumentException> { service.retry(11, "") }
        assertEquals("ACTIVE", service.retry(11, "Continue authorized content").state)
        verify { provider.stop(transfer.torrentHash, 7, 11) }
        verify { provider.start(transfer.torrentHash, 7, 11) }
        verify(exactly = 0) { provider.add(any(), any(), any()) }
    }

    @Test fun `crashed intent requires expired lease and exact ownership reconciliation then stops`() {
        transfer.state = "IN_FLIGHT"
        assertFailsWith<IllegalArgumentException> { service.reconcile(11, "Inspect crashed intent") }
        verify(exactly = 0) { provider.lookup(any()) }
        transfer.updatedAt = Instant.now().minusSeconds(660)
        every { provider.lookup(transfer.torrentHash) } returns OwnedTorrent(transfer.torrentHash, "fixture", setOf("fixture"))
        assertEquals("STOPPED", service.reconcile(11, "Inspect crashed intent").state)
        verify { policy.requireOwnedTorrent("fixture", setOf("fixture"), 7, 11) }
        verify { provider.stop(transfer.torrentHash, 7, 11) }
    }

    @Test fun `unselected or unqueued candidate cannot submit or resume`() {
        candidate.selected = false
        assertFailsWith<IllegalArgumentException> { service.submit(11, "Authorization") }
        candidate.selected = true
        every { request.status } returns GameRequestStatus.CANCELLED
        assertFailsWith<IllegalArgumentException> { service.submit(11, "Authorization") }
        transfer.state = "STOPPED"
        assertFailsWith<IllegalArgumentException> { service.retry(11, "Authorization") }
        verify(exactly = 0) { provider.add(any(), any(), any()) }
        verify(exactly = 0) { provider.start(any(), any(), any()) }
    }

    @Test fun `stale HTTP completion cannot overwrite another operation identity`() {
        every { provider.add(any(), 7, 11) } answers { transfer.operationToken = "another-operation" }
        assertFailsWith<IllegalArgumentException> { service.submit(11, "Authorized fixture") }
        assertEquals("IN_FLIGHT", transfer.state)
        verify(exactly = 0) { audits.save(match { it.operation == "SUBMIT_CONFIRMED" }) }
    }

    @Test fun `duplicate search hashes create only one candidate and audit record`() {
        val result = AuthorizedSearchResult("2", "fixture", transfer.magnet, transfer.torrentHash)
        every { request.status } returns GameRequestStatus.AWAITING_APPROVAL
        every { provider.search("2", "fixture") } returns listOf(result, result)
        every { requests.findById(7) } returns Optional.of(request)
        every { transfers.existsByTorrentHash(any()) } returns false
        every { candidates.save(any()) } answers { firstArg<GameRequestCandidate>().also { it.id = 11 } }
        assertEquals(1, service.search(7, "2", "fixture").size)
        verify(exactly = 1) { candidates.save(any()) }
        verify(exactly = 1) { audits.save(match { it.operation == "SEARCH_RECORDED" }) }
    }

    @Test fun `generic record changes refuse provider-backed requests rather than pretending to stop them`() {
        every { transfers.existsByCandidateGameRequestIdAndStateNot(7, "REVIEW") } returns true
        val records = GameRequestService(mockk(), mockk(), requests, mockk(), mockk(), transfers)
        assertFailsWith<IllegalArgumentException> { records.cancelRequest(7, "Record cancellation") }
        assertFailsWith<IllegalArgumentException> { records.retryRequest(7, "Record retry") }
        assertFailsWith<IllegalArgumentException> { records.changeRequestStatus(7, GameRequestStatus.REJECTED) }
        verify(exactly = 0) { provider.stop(any(), any(), any()) }
        verify(exactly = 0) { requests.findById(any()) }
    }

    @Test fun `known preflight refusal returns to review while post-add failures remain uncertain`() {
        every { provider.add(any(), 7, 11) } throws AcquisitionPreflightRefusal(IllegalStateException("fixture category absent"))
        assertFailsWith<AcquisitionPreflightRefusal> { service.submit(11, "Authorized fixture") }
        assertEquals("REVIEW", transfer.state)
        verify { audits.save(match { it.operation == "SUBMIT_REFUSED" }) }
        every { provider.add(any(), 7, 11) } just Runs
        assertEquals("ACTIVE", service.submit(11, "Deliberate retry after setup correction").state)
    }
}
