package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.ClassificationReviewState
import java.time.Instant

data class VariantClassificationReviewDto(
    val id: Long, val sourceGameId: Long, val sourceVariantId: Long, val targetGameId: Long,
    val confidence: Int, val explanation: String, val evidence: String, val state: ClassificationReviewState,
    val createdAt: Instant, val createdBy: String
)
