package org.gameyfin.app.games.repositories

import org.gameyfin.app.games.entities.VariantQuarantineRecord
import org.springframework.data.jpa.repository.JpaRepository

interface VariantQuarantineRepository : JpaRepository<VariantQuarantineRecord, Long> {
    fun findAllByVariantIdOrderByQuarantinedAtAsc(variantId: Long): List<VariantQuarantineRecord>
}
