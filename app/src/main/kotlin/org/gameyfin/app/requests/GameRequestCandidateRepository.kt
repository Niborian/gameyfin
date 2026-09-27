package org.gameyfin.app.requests

import org.gameyfin.app.requests.entities.GameRequestCandidate
import org.springframework.data.jpa.repository.JpaRepository

interface GameRequestCandidateRepository : JpaRepository<GameRequestCandidate, Long> {
    fun findAllByGameRequestIdOrderByRecordedAtAsc(gameRequestId: Long): List<GameRequestCandidate>
}
