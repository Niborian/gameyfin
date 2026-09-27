package org.gameyfin.app.requests.dto

import org.gameyfin.app.requests.status.GameRequestStatus
import java.time.Instant

data class GameRequestStatusChangeDto(
    val id: Long,
    val previousStatus: GameRequestStatus,
    val newStatus: GameRequestStatus,
    val changedAt: Instant,
    val actor: String,
    val reason: String?
)
