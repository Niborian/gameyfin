package org.gameyfin.app.requests

import org.gameyfin.app.requests.entities.GameRequestCandidateApproval
import org.springframework.data.jpa.repository.JpaRepository

interface GameRequestCandidateApprovalRepository : JpaRepository<GameRequestCandidateApproval, Long> {
    fun existsByCandidateId(candidateId: Long): Boolean
    fun findAllByCandidateIdOrderByApprovedAtAsc(candidateId: Long): List<GameRequestCandidateApproval>
}
