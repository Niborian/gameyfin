package org.gameyfin.app.games.dto

data class AttachVariantContentRequestDto(
    val sourceGameId: Long?,
    val sourceRootPath: String,
    val targetVariantId: Long?,
    val targetVariantIds: List<Long>? = null,
    val variantName: String?,
    val version: String?,
    val entries: List<AttachVariantContentEntryDto>
)
