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
import org.gameyfin.app.games.variants.SteamUpdateCandidateService
import org.gameyfin.app.games.variants.VariantRetirementPreviewService
import org.gameyfin.app.games.variants.VariantRetirementDecisionService
import org.gameyfin.app.games.variants.VariantTimestampEvidenceService
import org.gameyfin.app.games.variants.VariantQuarantineService
import org.gameyfin.app.libraries.LibraryCoreService
import org.gameyfin.app.libraries.LibraryService
import org.gameyfin.app.requests.dto.GameRequestCandidateDto
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
    private val gameVariantGroupingService: GameVariantGroupingService,
    private val steamUpdateCandidateService: SteamUpdateCandidateService,
    private val variantRetirementPreviewService: VariantRetirementPreviewService,
    private val variantRetirementDecisionService: VariantRetirementDecisionService,
    private val variantTimestampEvidenceService: VariantTimestampEvidenceService,
    private val variantQuarantineService: VariantQuarantineService
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
    fun getVariantRetirementPreview(gameId: Long): List<VariantRetirementPreviewDto> {
        return variantRetirementPreviewService.preview(gameId)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun markVariantSuperseded(gameId: Long, variantId: Long, request: MarkVariantSupersededRequestDto): VariantRetirementDecisionDto =
        variantRetirementDecisionService.markSuperseded(gameId, variantId, request)

    @RolesAllowed(Role.Names.ADMIN)
    fun quarantineVariantMirror(gameId: Long, variantId: Long, request: QuarantineVariantRequestDto): VariantQuarantineDto =
        variantQuarantineService.quarantine(gameId, variantId, request)

    @RolesAllowed(Role.Names.ADMIN)
    fun restoreVariantMirror(gameId: Long, variantId: Long, recordId: Long, confirmation: String): VariantQuarantineDto =
        variantQuarantineService.restore(gameId, variantId, recordId, confirmation)

    @RolesAllowed(Role.Names.ADMIN)
    fun getVariantQuarantineHistory(gameId: Long, variantId: Long): List<VariantQuarantineDto> =
        variantQuarantineService.history(gameId, variantId)

    @RolesAllowed(Role.Names.ADMIN)
    fun setVariantRetirementState(
        gameId: Long,
        variantId: Long,
        request: SetVariantRetirementStateRequestDto
    ): VariantRetirementDecisionDto = variantRetirementDecisionService.setState(gameId, variantId, request)

    @RolesAllowed(Role.Names.ADMIN)
    fun getVariantRetirementHistory(gameId: Long, variantId: Long): List<VariantRetirementDecisionDto> {
        return variantRetirementDecisionService.history(gameId, variantId)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun recordVariantTimestampEvidence(
        gameId: Long,
        variantId: Long,
        request: RecordVariantTimestampEvidenceRequestDto
    ): VariantTimestampEvidenceDto = variantTimestampEvidenceService.record(gameId, variantId, request)

    @RolesAllowed(Role.Names.ADMIN)
    fun getVariantTimestampEvidence(gameId: Long, variantId: Long): List<VariantTimestampEvidenceDto> {
        return variantTimestampEvidenceService.history(gameId, variantId)
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
    fun setDefaultVariant(targetGameId: Long, variantId: Long): GameAdminDto {
        return gameVariantGroupingService.setDefaultVariant(targetGameId, variantId).toAdminDto()
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun setVariantSteamAppId(
        targetGameId: Long,
        variantId: Long,
        request: SetVariantSteamAppIdRequestDto
    ): GameAdminDto {
        return gameVariantGroupingService.setVariantSteamAppId(targetGameId, variantId, request.steamAppId).toAdminDto()
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun recordVariantSteamMetadata(
        targetGameId: Long,
        variantId: Long,
        request: RecordVariantSteamMetadataRequestDto
    ): GameAdminDto {
        return gameVariantGroupingService.recordVariantSteamMetadata(targetGameId, variantId, request).toAdminDto()
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun getSteamUpdateCandidates(gameId: Long): List<SteamUpdateCandidateDto> {
        return steamUpdateCandidateService.candidates(gameId)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun getSteamNewsEvents(gameId: Long): List<SteamNewsEventDto> {
        return steamUpdateCandidateService.newsEvents(gameId)
    }

    @RolesAllowed(Role.Names.ADMIN)
    fun ignoreSteamUpdateCandidate(
        gameId: Long,
        variantId: Long,
        request: ReviewSteamUpdateCandidateRequestDto
    ): SteamUpdateCandidateDto = steamUpdateCandidateService.review(gameId, variantId, request, ignore = true)

    @RolesAllowed(Role.Names.ADMIN)
    fun snoozeSteamUpdateCandidate(
        gameId: Long,
        variantId: Long,
        request: ReviewSteamUpdateCandidateRequestDto
    ): SteamUpdateCandidateDto = steamUpdateCandidateService.review(gameId, variantId, request, ignore = false)

    @RolesAllowed(Role.Names.ADMIN)
    fun routeSteamUpdateCandidateToRequestReview(
        gameId: Long,
        variantId: Long,
        request: RouteSteamUpdateCandidateRequestDto
    ): GameRequestCandidateDto = steamUpdateCandidateService.routeToRequestReview(gameId, variantId, request)

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
