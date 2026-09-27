package org.gameyfin.app.games

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.games.dto.*
import org.gameyfin.app.games.variants.VariantClassificationReviewService

@Endpoint
@RolesAllowed(Role.Names.ADMIN)
class VariantClassificationReviewEndpoint(private val reviewService: VariantClassificationReviewService) {
    fun create(request: CreateVariantClassificationReviewRequestDto): VariantClassificationReviewDto = reviewService.create(request)
    fun updateState(reviewId: Long, request: UpdateVariantClassificationReviewStateRequestDto): VariantClassificationReviewDecisionDto =
        reviewService.updateState(reviewId, request)
    fun history(reviewId: Long): List<VariantClassificationReviewDecisionDto> = reviewService.history(reviewId)
}
