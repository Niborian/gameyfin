package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantStore
import org.gameyfin.app.games.entities.VariantDetailsSource
import java.time.Instant

data class VariantDetailsDto(
    val gameId: Long,
    val variantId: Long,
    val store: VariantStore,
    val storeProductId: String?,
    val edition: String?,
    val libraryVersion: String?,
    val buildId: String?,
    val detailsManualOverride: Boolean,
    /** Decimal string preserves exact revisions beyond JavaScript's safe integer range. */
    val detailsRevision: String,
    val detailsSource: VariantDetailsSource?,
    val detailsEvidence: String?,
    val detailsObservedAt: Instant?,
)

data class UpdateVariantDetailsRequestDto(
    val expectedRevision: String,
    val store: VariantStore,
    val storeProductId: String?,
    val edition: String?,
    val libraryVersion: String?,
    val buildId: String?,
    val manualOverride: Boolean = true,
)

data class VariantDetailsPreviewDto(
    val current: VariantDetailsDto,
    val previewDigest: String,
    val suggestions: List<VariantLocalVersionSuggestionDto>,
    val message: String,
    val truncated: Boolean,
)

data class ApplyVariantDetailsPreviewRequestDto(
    val expectedRevision: String,
    val previewDigest: String,
    val suggestionIndex: Int,
)
