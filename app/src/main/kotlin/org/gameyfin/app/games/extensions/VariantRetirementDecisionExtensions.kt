package org.gameyfin.app.games.extensions

import org.gameyfin.app.games.dto.VariantRetirementDecisionDto
import org.gameyfin.app.games.entities.VariantRetirementDecision

fun VariantRetirementDecision.toDto() = VariantRetirementDecisionDto(
    id = requireNotNull(id),
    previousState = previousState,
    newState = newState,
    decidedAt = decidedAt,
    actor = actor,
    reason = reason,
    reviewAt = reviewAt
)
