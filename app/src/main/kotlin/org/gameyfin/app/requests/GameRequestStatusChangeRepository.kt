package org.gameyfin.app.requests

import org.gameyfin.app.requests.entities.GameRequestStatusChange
import org.springframework.data.jpa.repository.JpaRepository

interface GameRequestStatusChangeRepository : JpaRepository<GameRequestStatusChange, Long> {
    fun findAllByGameRequestIdOrderByChangedAtAsc(gameRequestId: Long): List<GameRequestStatusChange>
}
