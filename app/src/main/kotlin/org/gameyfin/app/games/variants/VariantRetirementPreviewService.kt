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
import org.gameyfin.app.libraries.LibraryRetentionPolicyRepository
import org.gameyfin.app.libraries.entities.LibraryRetentionPolicyMode

@Service
class VariantRetirementPreviewService(
    private val gameRepository: GameRepository,
    private val decisionRepository: VariantRetirementDecisionRepository,
    private val policyRepository: LibraryRetentionPolicyRepository
) {
    fun preview(gameId: Long): List<VariantRetirementPreviewDto> {
        val game = gameRepository.findByIdOrNull(gameId)
            ?: throw IllegalArgumentException("Game $gameId not found")

        val policy = policyRepository.findByLibraryId(requireNotNull(game.library.id))
        val keptVersions = game.variants.groupBy { it.name }.mapValues { (_, variants) ->
            variants.map { it.version }.distinct().sortedWith(VariantVersionComparator.reversed())
                .take(policy?.keepLatestCount ?: 1).toSet()
        }
        return game.variants
            .sortedWith(compareBy<GameVariant> { it.name }.thenBy { it.version })
            .map { variant ->
                val protected = variant.isDefault || variant.defaultLocked || variant.isLatestForVariant
                val (retain, reason) = when {
                    protected -> true to "Protected default, pinned, or latest version"
                    policy?.mode == LibraryRetentionPolicyMode.KEEP_LATEST_N -> {
                        val keep = variant.version in keptVersions.getValue(variant.name)
                        keep to if (keep) "Within latest ${policy.keepLatestCount} versions of ${variant.name}"
                            else "Outside latest ${policy.keepLatestCount} versions; administrator archive review required"
                    }
                    policy?.mode == LibraryRetentionPolicyMode.GRACE_PERIOD -> true to
                        "Retained until dated supersession evidence can prove the ${policy.gracePeriodDays}-day grace period elapsed"
                    else -> true to "Keep all versions"
                }
                previewVariant(variant).copy(retainedByPolicy = retain, policyReason = reason)
            }
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
            variant.isDefault || variant.defaultLocked -> VariantRetirementDisposition.RETAIN to "Retained because it is the selected or pinned default variant"
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
