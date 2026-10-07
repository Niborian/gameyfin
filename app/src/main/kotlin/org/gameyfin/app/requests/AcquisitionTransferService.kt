package org.gameyfin.app.requests

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.requests.entities.GameRequestCandidate
import org.gameyfin.app.requests.status.GameRequestStatus
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

data class AcquisitionTransferDto(val candidateId: Long, val indexerId: String, val displayName: String, val torrentHash: String, val state: String)
data class AcquisitionAuditDto(val operation: String, val actor: String, val reason: String, val recordedAt: java.time.Instant)

/** Commits an intent before HTTP. Ambiguous failure never becomes an automatic retry or duplicate add. */
@Service
class AcquisitionTransferService(
    private val provider: AcquisitionProvider,
    private val policy: AcquisitionScopePolicy,
    private val requests: GameRequestRepository,
    private val candidates: GameRequestCandidateRepository,
    private val approvals: GameRequestCandidateApprovalRepository,
    private val transfers: AcquisitionTransferRepository,
    private val audits: AcquisitionTransferAuditRepository,
    manager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(manager)

    fun list(requestId: Long): List<AcquisitionTransferDto> = requireNotNull(transaction.execute {
        transfers.findAllByCandidateGameRequestId(requestId).map { dto(it) }
    })

    fun search(requestId: Long, indexerId: String, query: String): List<AcquisitionTransferDto> {
        val results = provider.search(indexerId, query)
        return requireNotNull(transaction.execute {
            val request = requests.findByIdOrNull(requestId) ?: error("Request not found")
            require(request.status == GameRequestStatus.AWAITING_APPROVAL) { "Search requires a request awaiting review" }
            results.distinctBy { it.hash }.filterNot { transfers.existsByTorrentHash(it.hash) }.map { result ->
                val candidate = candidates.save(GameRequestCandidate(gameRequest = request, providerLabel = "Prowlarr indexer ${result.indexerId}",
                    displayName = result.title, externalReference = "urn:btih:${result.hash}", recordedBy = actor()))
                val transfer = transfers.save(AcquisitionTransfer(candidate = candidate, indexerId = result.indexerId, torrentHash = result.hash, magnet = result.magnet))
                audit(transfer, "SEARCH_RECORDED", "Approved indexer result recorded for administrator review; no download")
                dto(transfer)
            }
        })
    }

    fun submit(candidateId: Long, authorizationReason: String): AcquisitionTransferDto = action(candidateId, authorizationReason, "SUBMIT", setOf("REVIEW")) { transfer ->
        provider.add(AuthorizedSearchResult(transfer.indexerId, transfer.candidate.displayName, transfer.magnet, transfer.torrentHash),
            requireNotNull(transfer.candidate.gameRequest.id), candidateId)
        "ACTIVE"
    }

    /** Stop only: never remove a torrent or delete source files. */
    fun cancel(candidateId: Long, reason: String): AcquisitionTransferDto = action(candidateId, reason, "STOP", setOf("ACTIVE")) { transfer ->
        provider.stop(transfer.torrentHash, requireNotNull(transfer.candidate.gameRequest.id), candidateId)
        "STOPPED"
    }

    /** Deliberate resume of the persisted hash, not a fresh add or arbitrary URL. */
    fun retry(candidateId: Long, authorizationReason: String): AcquisitionTransferDto = action(candidateId, authorizationReason, "START", setOf("STOPPED")) { transfer ->
        provider.start(transfer.torrentHash, requireNotNull(transfer.candidate.gameRequest.id), candidateId)
        "ACTIVE"
    }

    fun reconcile(candidateId: Long, reason: String): AcquisitionTransferDto = action(candidateId, reason, "RECONCILE", setOf("UNCERTAIN", "IN_FLIGHT")) { transfer ->
        val owned = provider.lookup(transfer.torrentHash) ?: error("Torrent absent; retain uncertainty and inspect isolated provider manually")
        policy.requireOwnedTorrent(owned.category, owned.tags, requireNotNull(transfer.candidate.gameRequest.id), candidateId)
        // Stop after identity confirmation so an uncertain operation has a deterministic safe state.
        provider.stop(transfer.torrentHash, requireNotNull(transfer.candidate.gameRequest.id), candidateId)
        "STOPPED"
    }

    fun audit(candidateId: Long): List<AcquisitionAuditDto> = audits.findAllByTransferCandidateIdOrderByRecordedAtAsc(candidateId)
        .map { AcquisitionAuditDto(it.operation, it.actor, it.reason, it.recordedAt) }

    private fun action(candidateId: Long, reason: String, operation: String, allowedStates: Set<String>, call: (AcquisitionTransfer) -> String): AcquisitionTransferDto {
        require(reason.isNotBlank() && reason.length <= 4096) { "An authorization or action reason is required" }
        val operationToken = java.util.UUID.randomUUID().toString()
        val transfer = requireNotNull(transaction.execute {
            val owned = transfers.findByCandidateId(candidateId) ?: error("No provider-backed candidate")
            require(owned.state in allowedStates) { "Transfer state does not permit this action; reconcile uncertain operations first" }
            if (owned.state == "IN_FLIGHT") {
                require(owned.updatedAt.isBefore(java.time.Instant.now().minusSeconds(600))) { "Wait for the bounded provider call before reconciling an in-flight operation" }
            }
            if (operation == "SUBMIT" || operation == "START") {
                require(approvals.existsByCandidateId(candidateId) && owned.candidate.selected) { "Deliberate administrator approval and selection are required" }
                require(owned.candidate.gameRequest.status == GameRequestStatus.QUEUED) { "Request must remain queued by approval" }
                policy.requireApprovedIndexer(owned.indexerId)
            }
            owned.state = "IN_FLIGHT"
            owned.updatedAt = java.time.Instant.now()
            owned.operationToken = operationToken
            transfers.saveAndFlush(owned)
            audit(owned, "${operation}_INTENT", reason)
            // Eagerly read identities while the transaction is open; provider calls use no lazy state.
            owned.candidate.displayName
            owned.candidate.gameRequest.id
            owned
        })
        val state = try { call(transfer) } catch (failure: Exception) {
            if (operation == "SUBMIT" && failure is AcquisitionPreflightRefusal) {
                complete(candidateId, operationToken, "REVIEW", "SUBMIT_REFUSED", "Preflight refused before any add; correct isolated provider setup and deliberately submit again")
                throw failure
            }
            complete(candidateId, operationToken, "UNCERTAIN", "${operation}_UNCERTAIN", "Provider outcome uncertain; inspect and reconcile before any retry")
            throw IllegalStateException("Provider outcome uncertain; no automatic retry", failure)
        }
        return complete(candidateId, operationToken, state, "${operation}_CONFIRMED", reason)
    }

    private fun complete(candidateId: Long, operationToken: String, state: String, operation: String, reason: String) = requireNotNull(transaction.execute {
        val transfer = transfers.findByCandidateId(candidateId) ?: error("Transfer disappeared")
        require(transfer.state == "IN_FLIGHT" && transfer.operationToken == operationToken) { "Concurrent transfer modification; stale completion refused" }
        transfer.state = state
        transfer.updatedAt = java.time.Instant.now()
        transfers.save(transfer)
        audit(transfer, operation, reason)
        dto(transfer)
    })

    private fun audit(transfer: AcquisitionTransfer, operation: String, reason: String) {
        audits.save(AcquisitionTransferAudit(transfer = transfer, operation = operation, actor = actor(), reason = reason))
    }
    private fun dto(transfer: AcquisitionTransfer) = AcquisitionTransferDto(requireNotNull(transfer.candidate.id), transfer.indexerId, transfer.candidate.displayName, transfer.torrentHash, transfer.state)
    private fun actor() = getCurrentAuth()?.name?.takeIf { it.isNotBlank() } ?: "system"
}

@Endpoint
@RolesAllowed(Role.Names.ADMIN)
class AcquisitionTransferEndpoint(private val service: AcquisitionTransferService) {
    fun list(requestId: Long) = service.list(requestId)
    fun search(requestId: Long, indexerId: String, query: String) = service.search(requestId, indexerId, query)
    fun submit(candidateId: Long, authorizationReason: String) = service.submit(candidateId, authorizationReason)
    fun cancel(candidateId: Long, reason: String) = service.cancel(candidateId, reason)
    fun retry(candidateId: Long, authorizationReason: String) = service.retry(candidateId, authorizationReason)
    fun reconcile(candidateId: Long, reason: String) = service.reconcile(candidateId, reason)
    fun audit(candidateId: Long) = service.audit(candidateId)
}
