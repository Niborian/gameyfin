package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantRetirementState
import java.time.Instant

data class VariantRetirementDecisionDto(
    val id: Long,
    val previousState: VariantRetirementState,
    val newState: VariantRetirementState,
    val decidedAt: Instant,
    val actor: String,
    val reason: String?,
    val reviewAt: Instant?,
    val supersededAt: Instant? = null,
    val supersededByVariantId: Long? = null
)
