package org.gameyfin.app.games.variants

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.games.dto.GameAdminDto
import org.gameyfin.app.games.dto.LearnedBaseSplitReviewDto
import org.gameyfin.app.games.dto.LearnedBaseSplitRuleDto
import org.gameyfin.app.games.extensions.toAdminDto

@Endpoint
@RolesAllowed(Role.Names.ADMIN)
class LearnedBaseSplitEndpoint(private val service: LearnedBaseSplitService) {
    fun review(gameId: Long): LearnedBaseSplitReviewDto = service.review(gameId)

    fun decide(gameId: Long, revision: String, approve: Boolean): GameAdminDto? =
        service.decide(gameId, revision, approve)?.toAdminDto()

    fun settings(): List<LearnedBaseSplitRuleDto> = service.settings()

    fun setPreference(id: String, choice: String): LearnedBaseSplitRuleDto = service.setPreference(id, choice)
}
