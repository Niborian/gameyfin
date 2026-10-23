package org.gameyfin.app.games.variants

import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.games.dto.*

@Endpoint
@RolesAllowed(Role.Names.ADMIN)
class VariantDetailsEndpoint(private val service: VariantDetailsService) {
    fun getDetails(gameId: Long, variantId: Long) = service.getDetails(gameId, variantId)
    fun update(gameId: Long, variantId: Long, request: UpdateVariantDetailsRequestDto) = service.update(gameId, variantId, request)
    fun preview(gameId: Long, variantId: Long) = service.preview(gameId, variantId)
    fun applyPreview(gameId: Long, variantId: Long, request: ApplyVariantDetailsPreviewRequestDto) = service.applyPreview(gameId, variantId, request)
}
