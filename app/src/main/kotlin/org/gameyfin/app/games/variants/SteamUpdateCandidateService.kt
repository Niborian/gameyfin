package org.gameyfin.app.games.variants

import org.gameyfin.app.games.dto.SteamUpdateCandidateDto
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.repositories.GameRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SteamUpdateCandidateService(private val gameRepository: GameRepository) {
    /**
     * Returns evidence that the local build marker and the owner-recorded public marker differ.
     * It does not infer a version ordering and does not initiate any acquisition action.
     */
    @Transactional(readOnly = true)
    fun candidates(gameId: Long): List<SteamUpdateCandidateDto> {
        val game = gameRepository.findByIdOrNull(gameId)
            ?: throw IllegalArgumentException("Target game $gameId not found")
        return game.variants.mapNotNull(::candidateFor)
    }

    private fun candidateFor(variant: GameVariant): SteamUpdateCandidateDto? {
        val appId = variant.steamAppId ?: return null
        if (variant.steamAppIdVerifiedAt == null) return null
        val local = variant.localBuildVersion ?: return null
        val remote = variant.steamUpdateMarker ?: return null
        val observedAt = variant.steamMetadataObservedAt ?: return null
        val source = variant.steamMetadataSource ?: return null
        if (local == remote) return null
        return SteamUpdateCandidateDto(
            variantId = requireNotNull(variant.id),
            steamAppId = appId,
            localBuildVersion = local,
            steamUpdateMarker = remote,
            observedAt = observedAt,
            source = source,
            ignored = remote == variant.steamUpdateIgnoredMarker,
            snoozedUntil = variant.steamUpdateSnoozedUntil
        )
    }
}
