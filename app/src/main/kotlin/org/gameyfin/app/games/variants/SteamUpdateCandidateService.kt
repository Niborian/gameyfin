package org.gameyfin.app.games.variants

import org.gameyfin.app.games.dto.SteamUpdateCandidateDto
import org.gameyfin.app.games.dto.SteamNewsEventDto
import org.gameyfin.app.games.dto.ReviewSteamUpdateCandidateRequestDto
import org.gameyfin.app.games.dto.RouteSteamUpdateCandidateRequestDto
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.requests.GameRequestCandidateService
import org.gameyfin.app.requests.dto.GameRequestCandidateDto
import org.gameyfin.app.requests.dto.RecordGameRequestCandidateDto
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SteamUpdateCandidateService(
    private val gameRepository: GameRepository,
    private val steamNewsClient: SteamNewsClient,
    private val newsClassifier: SteamNewsContentUpdateClassifier,
    private val requestCandidateService: GameRequestCandidateService
) {
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

    /** Polls only Steam's public ISteamNews endpoint and returns classified evidence for administrator review. */
    @Transactional(readOnly = true)
    fun newsEvents(gameId: Long): List<SteamNewsEventDto> {
        val game = gameRepository.findByIdOrNull(gameId)
            ?: throw IllegalArgumentException("Target game $gameId not found")
        return game.variants.flatMap { variant ->
            val appId = variant.steamAppId ?: return@flatMap emptyList()
            if (variant.steamAppIdVerifiedAt == null) return@flatMap emptyList()
            steamNewsClient.latest(appId).map { event ->
                val result = newsClassifier.classify(event.title, event.tags, event.contents)
                SteamNewsEventDto(
                    variantId = requireNotNull(variant.id),
                    eventId = event.id,
                    title = event.title,
                    url = event.url,
                    publishedAt = event.publishedAt,
                    tags = event.tags,
                    classification = result.classification,
                    classificationReason = result.reason
                )
            }
        }.filter { it.classification != SteamNewsClassification.NOT_CONTENT_UPDATE }
    }

    /**
     * Records an administrator's decision for one exact candidate marker. This only changes review state;
     * it cannot contact a provider, alter a library path, or initiate a download.
     */
    @Transactional
    fun review(gameId: Long, variantId: Long, request: ReviewSteamUpdateCandidateRequestDto, ignore: Boolean): SteamUpdateCandidateDto {
        val game = gameRepository.findByIdOrNull(gameId)
            ?: throw IllegalArgumentException("Target game $gameId not found")
        val variant = game.variants.firstOrNull { it.id == variantId }
            ?: throw IllegalArgumentException("Variant $variantId does not belong to game $gameId")
        val marker = request.marker.trim()
        require(marker.isNotEmpty()) { "Steam update marker is required" }
        require(variant.steamUpdateMarker == marker) { "The observed Steam update marker has changed" }
        require(candidateFor(variant) != null) { "No reviewable Steam update candidate exists" }

        if (ignore) {
            variant.steamUpdateIgnoredMarker = marker
            variant.steamUpdateSnoozedUntil = null
        } else {
            val until = requireNotNull(request.snoozedUntil) { "A snooze expiry is required" }
            require(until.isAfter(Instant.now())) { "Snooze expiry must be in the future" }
            variant.steamUpdateIgnoredMarker = null
            variant.steamUpdateSnoozedUntil = until
        }
        gameRepository.save(game)
        return requireNotNull(candidateFor(variant))
    }

    /**
     * Adds explicitly selected evidence to the existing request-review queue. Approval and later selection
     * remain separate audited actions in that workflow; this method has no provider or download action.
     */
    @Transactional
    fun routeToRequestReview(gameId: Long, variantId: Long, request: RouteSteamUpdateCandidateRequestDto): GameRequestCandidateDto {
        require(request.requestId > 0) { "A valid game request is required" }
        val game = gameRepository.findByIdOrNull(gameId)
            ?: throw IllegalArgumentException("Target game $gameId not found")
        val variant = game.variants.firstOrNull { it.id == variantId }
            ?: throw IllegalArgumentException("Variant $variantId does not belong to game $gameId")
        val marker = request.marker.trim()
        require(marker.isNotEmpty() && marker == variant.steamUpdateMarker) { "The observed Steam update marker has changed" }
        val candidate = requireNotNull(candidateFor(variant)) { "No reviewable Steam update candidate exists" }
        require(!candidate.ignored) { "The observed Steam update marker is ignored" }
        require(candidate.snoozedUntil?.isAfter(Instant.now()) != true) { "The observed Steam update marker is snoozed" }

        return requestCandidateService.record(
            request.requestId,
            RecordGameRequestCandidateDto(
                providerLabel = "Steam public metadata",
                displayName = "${game.title} — ${variant.name} update ${candidate.steamUpdateMarker}",
                externalReference = candidate.source,
                notes = "Steam app ${candidate.steamAppId}; local build ${candidate.localBuildVersion}; observed ${candidate.observedAt}."
            )
        )
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
