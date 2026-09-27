package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.ClassificationReviewState

data class UpdateVariantClassificationReviewStateRequestDto(val state: ClassificationReviewState, val reason: String? = null)
