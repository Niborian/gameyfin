package org.gameyfin.app.games.repositories

import org.gameyfin.app.games.entities.VariantDetails
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.gameyfin.app.games.dto.VariantDisplayDto
import org.gameyfin.app.games.entities.VariantRetirementState

interface VariantDetailsRepository : JpaRepository<VariantDetails, Long> {
    @Query("""select new org.gameyfin.app.games.dto.VariantDisplayDto(d.variantId,d.libraryVersion,d.edition)
        from VariantDetails d join GameVariant v on v.id=d.variantId
        where d.gameId=:gameId and v.game.id=:gameId
        and (:includeRetired=true or v.retirementState=:active)
        order by d.variantId""")
    fun findDisplayForGame(gameId: Long, includeRetired: Boolean, active: VariantRetirementState): List<VariantDisplayDto>
}
