package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantLinkStatus

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
    val disposition: VariantRetirementDisposition,
    val reason: String
)
