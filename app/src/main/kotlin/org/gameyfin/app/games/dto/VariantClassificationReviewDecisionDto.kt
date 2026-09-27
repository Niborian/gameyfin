package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.ClassificationReviewState
import java.time.Instant

data class VariantClassificationReviewDecisionDto(
    val id: Long, val previousState: ClassificationReviewState, val newState: ClassificationReviewState,
    val decidedAt: Instant, val actor: String, val reason: String?
)
