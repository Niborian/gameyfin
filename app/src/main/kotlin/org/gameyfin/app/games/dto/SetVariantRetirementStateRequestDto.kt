package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantRetirementState
import java.time.Instant

data class SetVariantRetirementStateRequestDto(
    val state: VariantRetirementState,
    val reason: String? = null,
    val reviewAt: Instant? = null
)
