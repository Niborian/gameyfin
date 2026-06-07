package org.gameyfin.app.games

import com.vaadin.flow.server.auth.AnonymousAllowed
import com.vaadin.hilla.Endpoint
import jakarta.annotation.security.RolesAllowed
import org.gameyfin.app.core.Role
import org.gameyfin.app.core.annotations.DynamicPublicAccess
import org.gameyfin.app.core.plugins.dto.ExternalProviderIdDto
import org.gameyfin.app.core.security.isCurrentUserAdmin
import org.gameyfin.app.games.dto.*
import org.gameyfin.app.games.extensions.toAdminDto
import org.gameyfin.app.games.variants.GameVariantGroupingService
import org.gameyfin.app.libraries.LibraryCoreService
import org.gameyfin.app.libraries.LibraryService
import org.gameyfin.pluginapi.gamemetadata.Platform
import reactor.core.publisher.Flux
import java.nio.file.Path

@Endpoint
@DynamicPublicAccess
@AnonymousAllowed
class GameEndpoint(
    private val gameService: GameService,
    private val libraryService: LibraryService,
    private val libraryCoreService: LibraryCoreService,
    private val gameVariantGroupingService: GameVariantGroupingService
) {
    fun subscribe(): Flux<out List<GameEvent>> {
        return if (isCurrentUserAdmin()) {
            GameService.subscribeAdmin()
        } else {
            GameService.subscribeUser()
        }
    }

    fun getAll(): List<GameDto> = gameService.getAll()

    fun getPotentialMatches(searchTerm: String, platformFilter: Set<Platform>): List<GameSearchResultDto> {
        return gameService.getPotentialMatches(searchTerm, platformFilter)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun updateGame(game: GameUpdateDto) = gameService.edit(game)

    @RolesAllowed(Role.Names.ADMIN)
    fun getGroupingSuggestions(libraryId: Long): List<GameGroupingSuggestionDto> {
        return gameVariantGroupingService.getGroupingSuggestions(libraryId)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun groupGameAsVariant(targetGameId: Long, request: GroupGameAsVariantRequestDto): GameAdminDto {
        return gameVariantGroupingService.groupGameAsVariant(targetGameId, request).toAdminDto()
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun attachVariantContent(targetGameId: Long, request: AttachVariantContentRequestDto): GameAdminDto {
        return gameVariantGroupingService.attachVariantContent(targetGameId, request).toAdminDto()
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun updateVariantContent(
        targetGameId: Long,
        variantId: Long,
        contentId: Long,
        request: UpdateVariantContentRequestDto
    ): GameAdminDto {
        return gameVariantGroupingService.updateVariantContent(targetGameId, variantId, contentId, request).toAdminDto()
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun deleteVariantContent(targetGameId: Long, variantId: Long, contentId: Long): GameAdminDto {
        return gameVariantGroupingService.deleteVariantContent(targetGameId, variantId, contentId).toAdminDto()
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun removeDuplicateVariantSource(targetGameId: Long, sourceGameId: Long): GameAdminDto {
        return gameVariantGroupingService.removeDuplicateVariantSource(targetGameId, sourceGameId).toAdminDto()
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun deleteGame(gameId: Long) {
        libraryCoreService.deleteGameFromLibrary(gameId)
        gameService.delete(gameId)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun matchManually(
        originalIds: Map<String, ExternalProviderIdDto>,
        path: String,
        libraryId: Long,
        replaceGameId: Long?
    ) {
        val library = libraryService.getById(libraryId)
        val game = gameService.matchManually(originalIds, Path.of(path), library, replaceGameId)
        if (game != null) {
            libraryCoreService.addGamesToLibrary(listOf(game), library, true)
        }
    }

    /**
     * This endpoint is necessary to fetch enum property values from the backend.
     * Hilla only generates enums directly from their respective values and ignores the displayName property.
     */
    @RolesAllowed(Role.Names.ADMIN)
    fun getEnumPropertyValues(): GameEnumPropertyValuesDto {
        return gameService.getEnumPropertyValues()
    }
}
