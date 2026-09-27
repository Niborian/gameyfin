package org.gameyfin.app.games.repositories

import org.gameyfin.app.games.entities.VariantTimestampEvidence
import org.springframework.data.jpa.repository.JpaRepository

interface VariantTimestampEvidenceRepository : JpaRepository<VariantTimestampEvidence, Long> {
    fun findAllByVariantIdOrderByKindAscRecordedAtAsc(variantId: Long): List<VariantTimestampEvidence>
}
