package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantLinkStatus
import org.gameyfin.app.games.entities.VariantRetirementState
import java.time.Instant

enum class VariantRetirementDisposition {
    RETAIN,
    REVIEW_MANAGED_MIRROR
}

/** Read-only evidence for a future retirement decision. It never performs a filesystem operation. */
data class VariantRetirementPreviewDto(
    val variantId: Long,
    val name: String,
    val version: String,
    val isDefault: Boolean,
    val isLatestForVariant: Boolean,
    val linkStatus: VariantLinkStatus,
    val managedBytes: Long,
    val selectedContentNames: List<String>,
    val effectivePaths: List<String>,
    /** Metadata-only state; it does not indicate that a filesystem archive or deletion occurred. */
    val retirementState: VariantRetirementState,
    val retirementReviewAt: Instant?,
    val latestDecisionAt: Instant?,
    val latestDecisionState: VariantRetirementState?,
    val disposition: VariantRetirementDisposition,
    val reason: String,
    val retainedByPolicy: Boolean = true,
    val policyReason: String = "Keep all versions"
)
