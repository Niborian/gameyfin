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
import org.gameyfin.app.games.entities.VariantRetirementState
import java.time.Instant
import java.time.temporal.ChronoUnit

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
        val pathUsers = mutableMapOf<String, MutableSet<Long>>()
        game.variants.forEach { variant ->
            val id = requireNotNull(variant.id)
            pathUsers.getOrPut(variant.path) { mutableSetOf() }.add(id)
            variant.contents.forEach { content -> content.effectivePaths().forEach { path ->
                pathUsers.getOrPut(path) { mutableSetOf() }.add(id)
            } }
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
                    policy?.mode == LibraryRetentionPolicyMode.GRACE_PERIOD -> {
                        val replacement = game.variants.firstOrNull { it.id == variant.supersededByVariantId }
                        val observedAt = variant.supersededAt
                        val validEvidence = observedAt != null && replacement != null &&
                            replacement.retirementState == VariantRetirementState.ACTIVE && replacement.name == variant.name &&
                            VariantVersionComparator.compare(replacement.version, variant.version) > 0
                        val expired = validEvidence && !Instant.now().isBefore(
                            requireNotNull(observedAt).plus(requireNotNull(policy.gracePeriodDays).toLong(), ChronoUnit.DAYS))
                        !expired to if (expired) "Observed supersession grace period elapsed; administrator archive review required"
                            else "Retained until valid dated supersession evidence proves the ${policy.gracePeriodDays}-day grace period elapsed"
                    }
                    else -> true to "Keep all versions"
                }
                val paths = (listOf(variant.path) + variant.contents.flatMap { it.effectivePaths() }).toSet()
                val dependencies = paths.flatMap { pathUsers[it].orEmpty() }.distinct().filter { it != variant.id }
                previewVariant(variant).copy(retainedByPolicy = retain, policyReason = reason,
                    supersededAt = variant.supersededAt, supersededByVariantId = variant.supersededByVariantId,
                    catalogDependentVariantIds = dependencies, quarantinePath = variant.quarantinePath,
                    archiveAllowed = !protected && variant.retirementState == VariantRetirementState.ACTIVE && game.variants.any {
                        it.name == variant.name && it.retirementState == VariantRetirementState.ACTIVE &&
                            VariantVersionComparator.compare(it.version, variant.version) > 0
                    })
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
