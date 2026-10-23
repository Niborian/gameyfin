package org.gameyfin.app.games.dto

/** Preview evidence only. No filesystem path outside the selected configured library is returned. */
data class VariantLocalVersionPreviewDto(
    val suggestions: List<VariantLocalVersionSuggestionDto>,
    val message: String,
    val truncated: Boolean
)

data class VariantLocalVersionSuggestionDto(
    val kind: String,
    val version: String?,
    val build: String?,
    val message: String,
    val source: String
)
