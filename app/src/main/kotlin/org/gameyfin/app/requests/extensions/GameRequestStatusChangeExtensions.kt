package org.gameyfin.app.requests.extensions

import org.gameyfin.app.requests.dto.GameRequestStatusChangeDto
import org.gameyfin.app.requests.entities.GameRequestStatusChange

fun GameRequestStatusChange.toDto() = GameRequestStatusChangeDto(
    id = requireNotNull(id),
    previousStatus = previousStatus,
    newStatus = newStatus,
    changedAt = changedAt,
    actor = actor,
    reason = reason
)
