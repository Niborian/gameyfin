package org.gameyfin.app.games.repositories

import org.gameyfin.app.games.entities.VariantRetirementDecision
import org.springframework.data.jpa.repository.JpaRepository

interface VariantRetirementDecisionRepository : JpaRepository<VariantRetirementDecision, Long> {
    fun findAllByVariantIdOrderByDecidedAtAsc(variantId: Long): List<VariantRetirementDecision>
}
