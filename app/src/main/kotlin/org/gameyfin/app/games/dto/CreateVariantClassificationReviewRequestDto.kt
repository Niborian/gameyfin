package org.gameyfin.app.games.dto

data class CreateVariantClassificationReviewRequestDto(
    val sourceGameId: Long,
    val sourceVariantId: Long,
    val targetGameId: Long,
    val confidence: Int,
    val explanation: String,
    val evidence: String
)
