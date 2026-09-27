package org.gameyfin.app.games.repositories

import org.gameyfin.app.games.entities.VariantClassificationReviewDecision
import org.springframework.data.jpa.repository.JpaRepository

interface VariantClassificationReviewDecisionRepository : JpaRepository<VariantClassificationReviewDecision, Long> {
    fun findAllByReviewIdOrderByDecidedAtAsc(reviewId: Long): List<VariantClassificationReviewDecision>
}
