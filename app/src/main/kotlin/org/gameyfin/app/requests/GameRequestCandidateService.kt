package org.gameyfin.app.requests

import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.requests.dto.*
import org.gameyfin.app.requests.entities.GameRequestCandidate
import org.gameyfin.app.requests.entities.GameRequestCandidateApproval
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Review-record service only: it deliberately has no provider, torrent-client, network, or filesystem call. */
@Service
class GameRequestCandidateService(
    private val requestRepository: GameRequestRepository,
    private val candidateRepository: GameRequestCandidateRepository,
    private val approvalRepository: GameRequestCandidateApprovalRepository,
) {
    @Transactional fun record(requestId: Long, request: RecordGameRequestCandidateDto): GameRequestCandidateDto {
        val gameRequest = requestRepository.findByIdOrNull(requestId) ?: throw IllegalArgumentException("Game request not found")
        val candidate = candidateRepository.save(GameRequestCandidate(
            gameRequest = gameRequest,
            providerLabel = text(request.providerLabel, "Provider label", 128),
            displayName = text(request.displayName, "Display name", 512),
            externalReference = text(request.externalReference, "External reference", 2048),
            notes = request.notes?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 4096) },
            recordedBy = actor(),
        ))
        return candidate.toDto()
    }
    @Transactional fun approve(candidateId: Long, request: ApproveGameRequestCandidateDto): GameRequestCandidateApprovalDto {
        val candidate = candidateRepository.findByIdOrNull(candidateId) ?: throw IllegalArgumentException("Request candidate not found")
        return approvalRepository.save(GameRequestCandidateApproval(
            candidate = candidate,
            approvedBy = actor(),
            reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }?.also { require(it.length <= 4096) },
        )).toDto()
    }
    @Transactional fun select(candidateId: Long): GameRequestCandidateDto {
        val candidate = candidateRepository.findByIdOrNull(candidateId) ?: throw IllegalArgumentException("Request candidate not found")
        require(approvalRepository.existsByCandidateId(candidateId)) { "An administrator approval record is required before selection" }
        candidateRepository.findAllByGameRequestIdOrderByRecordedAtAsc(requireNotNull(candidate.gameRequest.id)).forEach { it.selected = it.id == candidateId; candidateRepository.save(it) }
        return candidate.toDto()
    }
    @Transactional(readOnly = true) fun list(requestId: Long) = candidateRepository.findAllByGameRequestIdOrderByRecordedAtAsc(requestId).map { it.toDto() }
    @Transactional(readOnly = true) fun approvals(candidateId: Long) = approvalRepository.findAllByCandidateIdOrderByApprovedAtAsc(candidateId).map { it.toDto() }
    private fun text(value: String, label: String, max: Int) = value.trim().also { require(it.isNotEmpty()) { "$label is required" }; require(it.length <= max) { "$label must not exceed $max characters" } }
    private fun actor() = getCurrentAuth()?.name?.takeIf { it.isNotBlank() } ?: "system"
    private fun GameRequestCandidate.toDto() = GameRequestCandidateDto(requireNotNull(id), providerLabel, displayName, externalReference, notes, recordedAt, recordedBy, selected)
    private fun GameRequestCandidateApproval.toDto() = GameRequestCandidateApprovalDto(requireNotNull(id), approvedAt, approvedBy, reason)
}
