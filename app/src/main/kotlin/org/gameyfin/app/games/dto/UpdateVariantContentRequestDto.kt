package org.gameyfin.app.games.dto

import org.gameyfin.app.games.entities.VariantContentType

data class UpdateVariantContentRequestDto(
    val path: String,
    val contentName: String,
    val contentType: VariantContentType,
    val required: Boolean,
    val defaultSelected: Boolean,
    val tags: Set<String>?,
    val setAsVariantPath: Boolean?
)
