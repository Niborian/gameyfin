package org.gameyfin.app.games.variants

import org.gameyfin.app.core.security.getCurrentAuth
import org.gameyfin.app.games.dto.SetVariantRetirementStateRequestDto
import org.gameyfin.app.games.dto.VariantRetirementDecisionDto
import org.gameyfin.app.games.dto.MarkVariantSupersededRequestDto
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantRetirementDecision
import org.gameyfin.app.games.entities.VariantRetirementState
import org.gameyfin.app.games.extensions.toDto
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.repositories.VariantRetirementDecisionRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Records application metadata only. This service deliberately contains no filesystem or torrent-client operation.
 */
@Service
class VariantRetirementDecisionService(
    private val gameRepository: GameRepository,
    private val decisionRepository: VariantRetirementDecisionRepository
) {
    @Transactional
    fun markSuperseded(gameId: Long, variantId: Long, request: MarkVariantSupersededRequestDto): VariantRetirementDecisionDto {
        val variant = findVariant(gameId, variantId)
        val replacement = variant.game.variants.firstOrNull { it.id == request.replacementVariantId }
            ?: throw IllegalArgumentException("Replacement variant does not belong to this game")
        require(replacement.retirementState == VariantRetirementState.ACTIVE && replacement.name == variant.name &&
            VariantVersionComparator.compare(replacement.version, variant.version) > 0) {
            "Supersession requires an active newer version of the same variant"
        }
        if (variant.supersededAt == null) variant.supersededAt = Instant.now()
        variant.supersededByVariantId = replacement.id
        gameRepository.save(variant.game)
        return decisionRepository.save(VariantRetirementDecision(
            variant = variant, previousState = variant.retirementState, newState = variant.retirementState,
            actor = getCurrentAuth()?.name?.takeIf { it.isNotBlank() } ?: "system",
            reason = request.reason?.trim()?.takeIf { it.isNotEmpty() } ?: "Observed newer version ${replacement.version}",
            supersededAt = variant.supersededAt, supersededByVariantId = replacement.id
        )).toDto()
    }

    @Transactional
    fun setState(gameId: Long, variantId: Long, request: SetVariantRetirementStateRequestDto): VariantRetirementDecisionDto {
        val variant = findVariant(gameId, variantId)
        validateTransition(variant, request.state)
        val previousState = variant.retirementState
        if (request.state == VariantRetirementState.ARCHIVED) {
            val replacement = variant.game.variants.filter {
                it.retirementState == VariantRetirementState.ACTIVE && it.name == variant.name &&
                    VariantVersionComparator.compare(it.version, variant.version) > 0
            }.maxWithOrNull { first, second -> VariantVersionComparator.compare(first.version, second.version) }
            require(replacement != null) { "Archive requires an active newer version of the same variant" }
            if (variant.supersededAt == null) variant.supersededAt = Instant.now()
            variant.supersededByVariantId = replacement.id
        }

        variant.retirementState = request.state
        variant.retirementReviewAt = request.reviewAt.takeIf { request.state == VariantRetirementState.ARCHIVED }
        gameRepository.save(variant.game)

        return decisionRepository.save(
            VariantRetirementDecision(
                variant = variant,
                previousState = previousState,
                newState = request.state,
                actor = getCurrentAuth()?.name?.takeIf { it.isNotBlank() } ?: "system",
                reason = request.reason?.trim()?.takeIf { it.isNotEmpty() },
                reviewAt = variant.retirementReviewAt,
                supersededAt = variant.supersededAt,
                supersededByVariantId = variant.supersededByVariantId
            )
        ).toDto()
    }

    @Transactional(readOnly = true)
    fun history(gameId: Long, variantId: Long): List<VariantRetirementDecisionDto> {
        findVariant(gameId, variantId)
        return decisionRepository.findAllByVariantIdOrderByDecidedAtAsc(variantId).map { it.toDto() }
    }

    private fun findVariant(gameId: Long, variantId: Long): GameVariant {
        val game = gameRepository.findByIdOrNull(gameId) ?: throw IllegalArgumentException("Game $gameId not found")
        return game.variants.firstOrNull { it.id == variantId }
            ?: throw IllegalArgumentException("Variant $variantId does not belong to game $gameId")
    }

    private fun validateTransition(variant: GameVariant, targetState: VariantRetirementState) {
        if (targetState == VariantRetirementState.ACTIVE) return
        require(variant.retirementState != VariantRetirementState.ARCHIVED) { "Variant is already archived" }
        require(!variant.isDefault && !variant.defaultLocked) { "Selected or pinned default variants cannot be archived" }
        require(!variant.isLatestForVariant) { "Latest variants cannot be archived" }
        // Required/default-selected content describes this variant's download bundle, not a
        // filesystem dependency. Hiding metadata preserves all content and its hardlinks.
        // Direct sources may be hidden too; eligibility for deletion is a separate decision.
    }
}
