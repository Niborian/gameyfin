package org.gameyfin.app.games.variants

import org.gameyfin.app.games.dto.VariantRetirementDisposition
import org.gameyfin.app.games.dto.VariantRetirementPreviewDto
import org.gameyfin.app.games.entities.GameVariant
import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.games.entities.effectivePaths
import org.gameyfin.app.games.repositories.GameRepository
import org.gameyfin.app.games.repositories.VariantRetirementDecisionRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

@Service
class VariantRetirementPreviewService(
    private val gameRepository: GameRepository,
    private val decisionRepository: VariantRetirementDecisionRepository
) {
    fun preview(gameId: Long): List<VariantRetirementPreviewDto> {
        val game = gameRepository.findByIdOrNull(gameId)
            ?: throw IllegalArgumentException("Game $gameId not found")

        return game.variants
            .sortedWith(compareBy<GameVariant> { it.name }.thenBy { it.version })
            .map(::previewVariant)
    }

    private fun previewVariant(variant: GameVariant): VariantRetirementPreviewDto {
        val latestDecision = decisionRepository
            .findAllByVariantIdOrderByDecidedAtAsc(requireNotNull(variant.id))
            .lastOrNull()
        val effectivePaths = (listOf(variant.path) + variant.contents.flatMap { it.effectivePaths() }).distinct()
        val selectedContent = variant.contents
            .filter { it.required || it.defaultSelected }
            .map { it.name }
            .distinct()

        val (disposition, reason) = when {
            variant.isDefault -> VariantRetirementDisposition.RETAIN to "Retained because it is the selected default variant"
            variant.isLatestForVariant -> VariantRetirementDisposition.RETAIN to "Retained because it is the latest ${variant.name} variant"
            variant.linkStatus != VariantLinkStatus.HARDLINKED -> VariantRetirementDisposition.RETAIN to
                "Retained because its path is direct or otherwise not proven to be an application-managed mirror"
            else -> VariantRetirementDisposition.REVIEW_MANAGED_MIRROR to
                "Superseded hardlink-mirror variant; a future explicit archive or quarantine review is required before cleanup"
        }

        return VariantRetirementPreviewDto(
            variantId = requireNotNull(variant.id) { "Variant retirement preview requires a persisted variant" },
            name = variant.name,
            version = variant.version,
            isDefault = variant.isDefault,
            isLatestForVariant = variant.isLatestForVariant,
            linkStatus = variant.linkStatus,
            managedBytes = variant.fileSize ?: 0,
            selectedContentNames = selectedContent,
            effectivePaths = effectivePaths,
            retirementState = variant.retirementState,
            retirementReviewAt = variant.retirementReviewAt,
            latestDecisionAt = latestDecision?.decidedAt,
            latestDecisionState = latestDecision?.newState,
            disposition = disposition,
            reason = reason
        )
    }
}
