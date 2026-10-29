package org.gameyfin.app.games.variants

import com.vaadin.flow.server.auth.AnonymousAllowed
import com.vaadin.hilla.Endpoint
import org.gameyfin.app.core.annotations.DynamicPublicAccess
import org.gameyfin.app.core.security.isCurrentUserAdmin
import org.gameyfin.app.games.dto.VariantDisplayDto
import org.gameyfin.app.games.entities.VariantRetirementState
import org.gameyfin.app.games.repositories.VariantDetailsRepository
import org.springframework.transaction.annotation.Transactional

/** Same public/auth policy as GameEndpoint, without exposing the administrator editor. */
@Endpoint
@DynamicPublicAccess
@AnonymousAllowed
class VariantDisplayEndpoint(private val repository: VariantDetailsRepository) {
    @Transactional(readOnly = true)
    fun getForGame(gameId: Long): List<VariantDisplayDto> {
        require(gameId > 0)
        return repository.findDisplayForGame(gameId, isCurrentUserAdmin(), VariantRetirementState.ACTIVE)
    }
}
